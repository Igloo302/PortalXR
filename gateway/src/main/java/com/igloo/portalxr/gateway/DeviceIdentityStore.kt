package com.igloo.portalxr.gateway

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.util.PrivateKeyInfoFactory
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Device identity containing Ed25519 key pair
 */
@Serializable
data class DeviceIdentity(
    val deviceId: String,
    val publicKeyRawBase64: String,
    val privateKeyPkcs8Base64: String,
    val createdAtMs: Long,
)

/**
 * Manages device identity and Ed25519 cryptographic operations
 */
class DeviceIdentityStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val identityFile = File(context.filesDir, "portalxr/identity/device.json")
    @Volatile private var cachedIdentity: DeviceIdentity? = null

    /**
     * Load existing identity or create a new one
     */
    @Synchronized
    fun loadOrCreate(): DeviceIdentity {
        cachedIdentity?.let { return it }
        val existing = load()
        if (existing != null) {
            val derived = deriveDeviceId(existing.publicKeyRawBase64)
            if (derived != null && derived != existing.deviceId) {
                val updated = existing.copy(deviceId = derived)
                save(updated)
                cachedIdentity = updated
                return updated
            }
            cachedIdentity = existing
            return existing
        }
        val fresh = generate()
        save(fresh)
        cachedIdentity = fresh
        return fresh
    }

    /**
     * Sign a payload using Ed25519 private key
     */
    fun signPayload(payload: String, identity: DeviceIdentity): String? {
        return try {
            val privateKeyBytes = Base64.decode(identity.privateKeyPkcs8Base64, Base64.DEFAULT)
            val pkInfo = PrivateKeyInfo.getInstance(privateKeyBytes)
            val parsed = pkInfo.parsePrivateKey()
            val rawPrivate = DEROctetString.getInstance(parsed).octets
            val privateKey = Ed25519PrivateKeyParameters(rawPrivate, 0)
            val signer = Ed25519Signer()
            signer.init(true, privateKey)
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            signer.update(payloadBytes, 0, payloadBytes.size)
            base64UrlEncode(signer.generateSignature())
        } catch (e: Throwable) {
            Log.e("DeviceAuth", "signPayload FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * Verify a signature using Ed25519 public key
     */
    fun verifySelfSignature(payload: String, signatureBase64Url: String, identity: DeviceIdentity): Boolean {
        return try {
            val rawPublicKey = Base64.decode(identity.publicKeyRawBase64, Base64.DEFAULT)
            val pubKey = Ed25519PublicKeyParameters(rawPublicKey, 0)
            val sigBytes = base64UrlDecode(signatureBase64Url)
            val verifier = Ed25519Signer()
            verifier.init(false, pubKey)
            val payloadBytes = payload.toByteArray(Charsets.UTF_8)
            verifier.update(payloadBytes, 0, payloadBytes.size)
            verifier.verifySignature(sigBytes)
        } catch (e: Throwable) {
            Log.e("DeviceAuth", "self-verify exception: ${e.message}", e)
            false
        }
    }

    /**
     * Get public key in Base64URL format
     */
    fun publicKeyBase64Url(identity: DeviceIdentity): String? {
        return try {
            val raw = Base64.decode(identity.publicKeyRawBase64, Base64.DEFAULT)
            base64UrlEncode(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun load(): DeviceIdentity? {
        return readIdentity(identityFile)
    }

    private fun readIdentity(file: File): DeviceIdentity? {
        return try {
            if (!file.exists()) return null
            val raw = file.readText(Charsets.UTF_8)
            val decoded = json.decodeFromString(DeviceIdentity.serializer(), raw)
            if (decoded.deviceId.isBlank() ||
                decoded.publicKeyRawBase64.isBlank() ||
                decoded.privateKeyPkcs8Base64.isBlank()
            ) {
                null
            } else {
                decoded
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun save(identity: DeviceIdentity) {
        try {
            identityFile.parentFile?.mkdirs()
            val encoded = json.encodeToString(DeviceIdentity.serializer(), identity)
            identityFile.writeText(encoded, Charsets.UTF_8)
        } catch (_: Throwable) {
            // best-effort only
        }
    }

    private fun generate(): DeviceIdentity {
        // Use Bouncy Castle lightweight API directly
        val kpGen = Ed25519KeyPairGenerator()
        kpGen.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val kp = kpGen.generateKeyPair()
        val pubKey = kp.public as Ed25519PublicKeyParameters
        val privKey = kp.private as Ed25519PrivateKeyParameters
        val rawPublic = pubKey.encoded  // 32 bytes
        val deviceId = sha256Hex(rawPublic)
        // Encode private key as PKCS8 for storage
        val privKeyInfo = PrivateKeyInfoFactory.createPrivateKeyInfo(privKey)
        val pkcs8Bytes = privKeyInfo.encoded
        return DeviceIdentity(
            deviceId = deviceId,
            publicKeyRawBase64 = Base64.encodeToString(rawPublic, Base64.NO_WRAP),
            privateKeyPkcs8Base64 = Base64.encodeToString(pkcs8Bytes, Base64.NO_WRAP),
            createdAtMs = System.currentTimeMillis(),
        )
    }

    private fun deriveDeviceId(publicKeyRawBase64: String): String? {
        return try {
            val raw = Base64.decode(publicKeyRawBase64, Base64.DEFAULT)
            sha256Hex(raw)
        } catch (_: Throwable) {
            null
        }
    }

    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val out = CharArray(digest.size * 2)
        var i = 0
        for (byte in digest) {
            val v = byte.toInt() and 0xff
            out[i++] = HEX[v ushr 4]
            out[i++] = HEX[v and 0x0f]
        }
        return String(out)
    }

    private fun base64UrlDecode(input: String): ByteArray {
        val normalized = input.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
