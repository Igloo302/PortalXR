package com.igloo.portalxr.gateway

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Gateway session managing WebSocket connection with Ed25519 authentication
 */
class GatewaySession(
    private val scope: CoroutineScope,
    private val identityStore: DeviceIdentityStore,
    private val onConnected: (serverName: String?, remoteAddress: String?, mainSessionKey: String?) -> Unit,
    private val onDisconnected: (message: String) -> Unit,
    private val onEvent: (event: String, payloadJson: String?) -> Unit,
    private val onInvoke: (suspend (InvokeRequest) -> InvokeResult)? = null,
) {
    companion object {
        private const val GATEWAY_PROTOCOL_VERSION = 3
        private const val CONNECT_RPC_TIMEOUT_MS = 12_000L
    }

    data class InvokeRequest(
        val id: String,
        val nodeId: String,
        val command: String,
        val paramsJson: String?,
        val timeoutMs: Long?,
    )

    data class InvokeResult(val ok: Boolean, val payloadJson: String?, val error: ErrorShape?) {
        companion object {
            fun ok(payloadJson: String?) = InvokeResult(ok = true, payloadJson = payloadJson, error = null)
            fun error(code: String, message: String) =
                InvokeResult(ok = false, payloadJson = null, error = ErrorShape(code = code, message = message))
        }
    }

    data class ErrorShape(
        val code: String,
        val message: String,
    )

    data class RpcResult(val ok: Boolean, val payloadJson: String?, val error: ErrorShape?)

    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<RpcResponse>>()

    @Volatile private var canvasHostUrl: String? = null
    @Volatile private var mainSessionKey: String? = null

    private data class DesiredConnection(
        val endpoint: GatewayEndpoint,
        val token: String?,
        val bootstrapToken: String?,
        val password: String?,
        val options: GatewayConnectOptions,
        val tls: GatewayTlsParams?,
    )

    private var desired: DesiredConnection? = null
    private var job: Job? = null
    @Volatile private var currentConnection: Connection? = null

    fun connect(
        endpoint: GatewayEndpoint,
        token: String?,
        bootstrapToken: String?,
        password: String?,
        options: GatewayConnectOptions,
        tls: GatewayTlsParams? = null,
    ) {
        desired = DesiredConnection(endpoint, token, bootstrapToken, password, options, tls)
        if (job == null) {
            job = scope.launch(Dispatchers.IO) { runLoop() }
        }
    }

    fun disconnect() {
        desired = null
        currentConnection?.closeQuietly()
        scope.launch(Dispatchers.IO) {
            job?.cancelAndJoin()
            job = null
            canvasHostUrl = null
            mainSessionKey = null
            onDisconnected("Offline")
        }
    }

    fun reconnect() {
        currentConnection?.closeQuietly()
    }

    fun currentCanvasHostUrl(): String? = canvasHostUrl
    fun currentMainSessionKey(): String? = mainSessionKey

    suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean {
        val conn = currentConnection ?: return false
        val params = buildJsonObject {
            put("event", JsonPrimitive(event))
            put("payloadJSON", JsonPrimitive(payloadJson ?: "{}"))
        }
        return try {
            conn.request("node.event", params, timeoutMs = 8_000)
            true
        } catch (err: Throwable) {
            Log.w("PortalXRGateway", "node.event failed: ${err.message ?: err::class.java.simpleName}")
            false
        }
    }

    suspend fun request(method: String, paramsJson: String?, timeoutMs: Long = 15_000): String {
        val res = requestDetailed(method = method, paramsJson = paramsJson, timeoutMs = timeoutMs)
        if (res.ok) return res.payloadJson ?: ""
        val err = res.error
        throw IllegalStateException("${err?.code ?: "UNAVAILABLE"}: ${err?.message ?: "request failed"}")
    }

    suspend fun requestDetailed(method: String, paramsJson: String?, timeoutMs: Long = 15_000): RpcResult {
        val conn = currentConnection ?: throw IllegalStateException("not connected")
        val params = if (paramsJson.isNullOrBlank()) {
            null
        } else {
            json.parseToJsonElement(paramsJson)
        }
        val res = conn.request(method, params, timeoutMs)
        return RpcResult(ok = res.ok, payloadJson = res.payloadJson, error = res.error)
    }

    private data class RpcResponse(val id: String, val ok: Boolean, val payloadJson: String?, val error: ErrorShape?)

    private inner class Connection(
        private val endpoint: GatewayEndpoint,
        private val token: String?,
        private val bootstrapToken: String?,
        private val password: String?,
        private val options: GatewayConnectOptions,
        private val tls: GatewayTlsParams?,
    ) {
        private val connectDeferred = CompletableDeferred<Unit>()
        private val closedDeferred = CompletableDeferred<Unit>()
        private val isClosed = java.util.concurrent.atomic.AtomicBoolean(false)
        private val connectNonceDeferred = CompletableDeferred<String>()
        private val client: OkHttpClient = buildClient()
        private var socket: WebSocket? = null

        val remoteAddress: String = "${endpoint.host}:${endpoint.port}"

        suspend fun connect() {
            val url = buildGatewayWebSocketUrl(endpoint.host, endpoint.port, tls != null)
            val request = Request.Builder().url(url).build()
            socket = client.newWebSocket(request, Listener())
            connectDeferred.await()
        }

        suspend fun request(method: String, params: JsonElement?, timeoutMs: Long): RpcResponse {
            val id = UUID.randomUUID().toString()
            val deferred = CompletableDeferred<RpcResponse>()
            pending[id] = deferred
            val frame = buildJsonObject {
                put("type", JsonPrimitive("req"))
                put("id", JsonPrimitive(id))
                put("method", JsonPrimitive(method))
                if (params != null) put("params", params)
            }
            sendJson(frame)
            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (err: TimeoutCancellationException) {
                pending.remove(id)
                throw IllegalStateException("request timeout")
            }
        }

        suspend fun sendJson(obj: JsonObject) {
            val jsonString = obj.toString()
            writeLock.withLock {
                socket?.send(jsonString)
            }
        }

        suspend fun awaitClose() = closedDeferred.await()

        fun closeQuietly() {
            if (isClosed.compareAndSet(false, true)) {
                socket?.close(1000, "bye")
                socket = null
                closedDeferred.complete(Unit)
            }
        }

        private fun buildClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()
        }

        private inner class Listener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                scope.launch {
                    try {
                        val nonce = awaitConnectNonce()
                        sendConnect(nonce)
                    } catch (err: Throwable) {
                        connectDeferred.completeExceptionally(err)
                        closeQuietly()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                scope.launch { handleMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!connectDeferred.isCompleted) {
                    connectDeferred.completeExceptionally(t)
                }
                if (isClosed.compareAndSet(false, true)) {
                    failPending()
                    closedDeferred.complete(Unit)
                    onDisconnected("Gateway error: ${t.message ?: t::class.java.simpleName}")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!connectDeferred.isCompleted) {
                    connectDeferred.completeExceptionally(IllegalStateException("Gateway closed: $reason"))
                }
                if (isClosed.compareAndSet(false, true)) {
                    failPending()
                    closedDeferred.complete(Unit)
                    onDisconnected("Gateway closed: $reason")
                }
            }
        }

        private suspend fun sendConnect(connectNonce: String) {
            val identity = identityStore.loadOrCreate()
            val payload = buildConnectParams(identity, connectNonce, token, bootstrapToken, password)
            val res = request("connect", payload, timeoutMs = CONNECT_RPC_TIMEOUT_MS)
            if (!res.ok) {
                val error = res.error ?: ErrorShape("UNAVAILABLE", "connect failed")
                throw IllegalStateException("${error.code}: ${error.message}")
            }
            handleConnectSuccess(res, identity.deviceId)
            connectDeferred.complete(Unit)
        }

        private fun buildConnectParams(
            identity: DeviceIdentity,
            connectNonce: String,
            token: String?,
            bootstrapToken: String?,
            password: String?,
        ): JsonObject {
            val client = options.client
            val locale = java.util.Locale.getDefault().toLanguageTag()

            val clientObj = buildJsonObject {
                put("id", JsonPrimitive(client.id))
                client.displayName?.let { put("displayName", JsonPrimitive(it)) }
                put("version", JsonPrimitive(client.version))
                put("platform", JsonPrimitive(client.platform))
                put("mode", JsonPrimitive(client.mode))
                client.instanceId?.let { put("instanceId", JsonPrimitive(it)) }
                client.deviceFamily?.let { put("deviceFamily", JsonPrimitive(it)) }
                client.modelIdentifier?.let { put("modelIdentifier", JsonPrimitive(it)) }
            }

            val authJson = when {
                token != null -> buildJsonObject { put("token", JsonPrimitive(token)) }
                bootstrapToken != null -> buildJsonObject { put("bootstrapToken", JsonPrimitive(bootstrapToken)) }
                password != null -> buildJsonObject { put("password", JsonPrimitive(password)) }
                else -> null
            }

            val signedAtMs = System.currentTimeMillis()
            val payload = buildDeviceAuthPayload(
                deviceId = identity.deviceId,
                clientId = client.id,
                clientMode = client.mode,
                role = options.role,
                scopes = options.scopes,
                signedAtMs = signedAtMs,
                nonce = connectNonce,
                platform = client.platform,
                deviceFamily = client.deviceFamily,
                token = token,
            )
            val signature = identityStore.signPayload(payload, identity)
            val publicKey = identityStore.publicKeyBase64Url(identity)

            val deviceJson = if (!signature.isNullOrBlank() && !publicKey.isNullOrBlank()) {
                buildJsonObject {
                    put("id", JsonPrimitive(identity.deviceId))
                    put("publicKey", JsonPrimitive(publicKey))
                    put("signature", JsonPrimitive(signature))
                    put("signedAt", JsonPrimitive(signedAtMs))
                    put("nonce", JsonPrimitive(connectNonce))
                }
            } else null

            return buildJsonObject {
                put("minProtocol", JsonPrimitive(GATEWAY_PROTOCOL_VERSION))
                put("maxProtocol", JsonPrimitive(GATEWAY_PROTOCOL_VERSION))
                put("client", clientObj)
                if (options.caps.isNotEmpty()) put("caps", JsonArray(options.caps.map(::JsonPrimitive)))
                if (options.commands.isNotEmpty()) put("commands", JsonArray(options.commands.map(::JsonPrimitive)))
                if (options.permissions.isNotEmpty()) {
                    put("permissions", buildJsonObject {
                        options.permissions.forEach { (key, value) ->
                            put(key, JsonPrimitive(value))
                        }
                    })
                }
                put("role", JsonPrimitive(options.role))
                if (options.scopes.isNotEmpty()) put("scopes", JsonArray(options.scopes.map(::JsonPrimitive)))
                authJson?.let { put("auth", it) }
                deviceJson?.let { put("device", it) }
                put("locale", JsonPrimitive(locale))
                options.userAgent?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    put("userAgent", JsonPrimitive(it))
                }
            }
        }

        private fun handleConnectSuccess(res: RpcResponse, deviceId: String) {
            val payloadJson = res.payloadJson ?: throw IllegalStateException("connect failed: missing payload")
            val obj = json.parseToJsonElement(payloadJson).asObjectOrNull()
                ?: throw IllegalStateException("connect failed")
            val serverName = obj?.get("server")?.asObjectOrNull()?.get("host")?.asStringOrNull()
            val sessionDefaults = obj?.get("snapshot")?.asObjectOrNull()?.get("sessionDefaults")?.asObjectOrNull()
            mainSessionKey = sessionDefaults?.get("mainSessionKey")?.asStringOrNull()
            canvasHostUrl = obj?.get("canvasHostUrl")?.asStringOrNull()
            onConnected(serverName, remoteAddress, mainSessionKey)
        }

        private suspend fun handleMessage(text: String) {
            val frame = json.parseToJsonElement(text).asObjectOrNull() ?: return
            when (frame["type"].asStringOrNull()) {
                "res" -> handleResponse(frame)
                "event" -> handleEvent(frame)
            }
        }

        private fun handleResponse(frame: JsonObject) {
            val id = frame["id"].asStringOrNull() ?: return
            val ok = frame["ok"].asBooleanOrNull() ?: false
            val payloadJson = frame["payload"]?.let { it.toString() }
            val error = frame["error"]?.asObjectOrNull()?.let { obj ->
                ErrorShape(
                    code = obj["code"].asStringOrNull() ?: "UNAVAILABLE",
                    message = obj["message"].asStringOrNull() ?: "request failed"
                )
            }
            pending.remove(id)?.complete(RpcResponse(id, ok, payloadJson, error))
        }

        private fun handleEvent(frame: JsonObject) {
            val event = frame["event"].asStringOrNull() ?: return
            val payloadJson = frame["payload"]?.let { it.toString() } ?: frame["payloadJSON"].asStringOrNull()
            if (event == "connect.challenge") {
                val nonce = extractConnectNonce(payloadJson)
                if (!connectNonceDeferred.isCompleted && !nonce.isNullOrBlank()) {
                    connectNonceDeferred.complete(nonce.trim())
                }
                return
            }
            if (event == "node.invoke.request" && payloadJson != null && onInvoke != null) {
                handleInvokeEvent(payloadJson)
                return
            }
            onEvent(event, payloadJson)
        }

        private suspend fun awaitConnectNonce(): String {
            return try {
                withTimeout(2_000) { connectNonceDeferred.await() }
            } catch (err: Throwable) {
                throw IllegalStateException("connect challenge timeout", err)
            }
        }

        private fun extractConnectNonce(payloadJson: String?): String? {
            if (payloadJson.isNullOrBlank()) return null
            val obj = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return null
            return obj["nonce"].asStringOrNull()
        }

        private fun handleInvokeEvent(payloadJson: String) {
            val payload = json.parseToJsonElement(payloadJson).asObjectOrNull() ?: return
            val id = payload["id"].asStringOrNull() ?: return
            val nodeId = payload["nodeId"].asStringOrNull() ?: return
            val command = payload["command"].asStringOrNull() ?: return
            val params = payload["paramsJSON"].asStringOrNull() ?: payload["params"]?.toString()
            val timeoutMs = payload["timeoutMs"].asLongOrNull()
            scope.launch {
                val result = try {
                    onInvoke?.invoke(InvokeRequest(id, nodeId, command, params, timeoutMs))
                        ?: InvokeResult.error("UNAVAILABLE", "invoke handler missing")
                } catch (err: Throwable) {
                    InvokeResult.error("INTERNAL_ERROR", err.message ?: "unknown error")
                }
                sendInvokeResult(id, nodeId, result, timeoutMs)
            }
        }

        private suspend fun sendInvokeResult(
            id: String,
            nodeId: String,
            result: InvokeResult,
            invokeTimeoutMs: Long?,
        ) {
            val params = buildJsonObject {
                put("id", JsonPrimitive(id))
                put("nodeId", JsonPrimitive(nodeId))
                put("ok", JsonPrimitive(result.ok))
                result.payloadJson?.let { put("payloadJSON", JsonPrimitive(it)) }
                result.error?.let { err ->
                    put("error", buildJsonObject {
                        put("code", JsonPrimitive(err.code))
                        put("message", JsonPrimitive(err.message))
                    })
                }
            }
            try {
                request("node.invoke.result", params.toString(), timeoutMs = 15_000)
            } catch (err: Throwable) {
                Log.w("PortalXRGateway", "node.invoke.result failed: ${err.message}")
            }
        }

        private fun failPending() {
            for ((_, waiter) in pending) {
                waiter.cancel()
            }
            pending.clear()
        }
    }

    private suspend fun runLoop() {
        var attempt = 0
        while (scope.isActive) {
            val target = desired
            if (target == null) {
                currentConnection?.closeQuietly()
                currentConnection = null
                delay(250)
                continue
            }

            try {
                onDisconnected(if (attempt == 0) "Connecting…" else "Reconnecting…")
                connectOnce(target)
                attempt = 0
            } catch (err: Throwable) {
                attempt += 1
                onDisconnected("Gateway error: ${err.message ?: err::class.java.simpleName}")
                val sleepMs = minOf(8_000L, (350.0 * Math.pow(1.7, attempt.toDouble())).toLong())
                delay(sleepMs)
            }
        }
    }

    private suspend fun connectOnce(target: DesiredConnection) = withContext(Dispatchers.IO) {
        val conn = Connection(
            target.endpoint,
            target.token,
            target.bootstrapToken,
            target.password,
            target.options,
            target.tls,
        )
        currentConnection = conn
        try {
            conn.connect()
            conn.awaitClose()
        } finally {
            currentConnection = null
            canvasHostUrl = null
            mainSessionKey = null
        }
    }
}

// Extension functions for JSON parsing
private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement?.asStringOrNull(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}
private fun JsonElement?.asBooleanOrNull(): Boolean? = when (this) {
    is JsonPrimitive -> content.trim().lowercase() == "true"
    else -> null
}
private fun JsonElement?.asLongOrNull(): Long? = when (this) {
    is JsonPrimitive -> content.toLongOrNull()
    else -> null
}

private fun buildGatewayWebSocketUrl(host: String, port: Int, useTls: Boolean): String {
    val scheme = if (useTls) "wss" else "ws"
    return "$scheme://$host:$port"
}

private fun buildDeviceAuthPayload(
    deviceId: String,
    clientId: String,
    clientMode: String,
    role: String,
    scopes: List<String>,
    signedAtMs: Long,
    nonce: String,
    platform: String,
    deviceFamily: String?,
    token: String?,
): String {
    // V3 format: v3|deviceId|clientId|clientMode|role|scopes|signedAtMs|token|nonce|platform|deviceFamily
    val scopesStr = scopes.joinToString(",")
    val tokenStr = token ?: ""
    val platformStr = platform.lowercase()
    val deviceFamilyStr = deviceFamily?.lowercase() ?: ""
    return listOf(
        "v3",
        deviceId,
        clientId,
        clientMode,
        role,
        scopesStr,
        signedAtMs.toString(),
        tokenStr,
        nonce,
        platformStr,
        deviceFamilyStr
    ).joinToString("|")
}
