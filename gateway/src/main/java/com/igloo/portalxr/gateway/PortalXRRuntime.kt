package com.igloo.portalxr.gateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chat event callback interface
 */
interface ChatEventHandler {
    fun handleGatewayEvent(event: String, payloadJson: String?)
    fun onDisconnected(message: String)
}

/**
 * A2UI 消息回调接口
 */
interface A2UIMessageHandler {
    fun onA2UIMessage(message: String)
}

/**
 * Main runtime for the PortalXR node
 */
class PortalXRRuntime(
    private val context: Context,
    private val a2uiHandler: A2UIMessageHandler? = null,
    private val chatHandler: ChatEventHandler? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val identityStore = DeviceIdentityStore(appContext)

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _statusText = MutableStateFlow("Offline")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _serverName = MutableStateFlow<String?>(null)
    val serverName: StateFlow<String?> = _serverName.asStateFlow()

    private val _canvasHostUrl = MutableStateFlow<String?>(null)
    val canvasHostUrl: StateFlow<String?> = _canvasHostUrl.asStateFlow()

    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val _cameraEnabled = MutableStateFlow(false)
    val cameraEnabled: StateFlow<Boolean> = _cameraEnabled.asStateFlow()

    private val _locationEnabled = MutableStateFlow(false)
    val locationEnabled: StateFlow<Boolean> = _locationEnabled.asStateFlow()

    private var connectedEndpoint: GatewayEndpoint? = null

    private val invokeDispatcher: InvokeDispatcher = InvokeDispatcher(
        context = appContext,
        isForeground = { _isForeground.value },
        cameraEnabled = { _cameraEnabled.value },
        locationEnabled = { _locationEnabled.value },
        onXrPanelCommand = { command, paramsJson ->
            GatewaySession.InvokeResult.ok("""{"command":"$command","executed":true}""")
        },
        onA2uiPush = { jsonl ->
            android.util.Log.i("PortalXR", "A2UI push received: ${jsonl.take(200)}")
            // Process each line of JSONL
            jsonl.lines().filter { it.isNotBlank() }.forEach { line ->
                android.util.Log.i("PortalXR", "A2UI line: $line")
                a2uiHandler?.onA2UIMessage(line)
            }
            GatewaySession.InvokeResult.ok("""{"pushed":true}""")
        },
    )

    private val _session: GatewaySession = GatewaySession(
        scope = scope,
        identityStore = identityStore,
        onConnected = { name, remote, mainSessionKey ->
            _isConnected.value = true
            _statusText.value = "Connected"
            _serverName.value = name
        },
        onDisconnected = { message ->
            _isConnected.value = false
            _statusText.value = message
            _serverName.value = null
            _canvasHostUrl.value = null
            chatHandler?.onDisconnected(message)
        },
        onEvent = { event, payloadJson ->
            handleGatewayEvent(event, payloadJson)
        },
        onInvoke = { req ->
            invokeDispatcher.handleInvoke(req.command, req.paramsJson)
        },
    )

    // Expose session for ChatController
    val session: GatewaySession get() = _session

    val deviceId: String
        get() = identityStore.loadOrCreate().deviceId

    fun setForeground(value: Boolean) {
        _isForeground.value = value
    }

    fun setCameraEnabled(value: Boolean) {
        _cameraEnabled.value = value
    }

    fun setLocationEnabled(value: Boolean) {
        _locationEnabled.value = value
    }

    fun connect(endpoint: GatewayEndpoint, auth: GatewayConnectAuth = GatewayConnectAuth(null, null, null)) {
        connectedEndpoint = endpoint
        val options = buildConnectOptions()
        session.connect(
            endpoint = endpoint,
            token = auth.token,
            bootstrapToken = auth.bootstrapToken,
            password = auth.password,
            options = options,
            tls = null,
        )
    }

    fun disconnect() {
        connectedEndpoint = null
        session.disconnect()
    }

    fun reconnect() {
        session.reconnect()
    }

    suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean {
        return session.sendNodeEvent(event, payloadJson)
    }

    suspend fun request(method: String, paramsJson: String?): String {
        return session.request(method, paramsJson)
    }

    /**
     * 发送用户操作回网关
     */
    suspend fun sendUserAction(surfaceId: String, actionName: String, context: Map<String, Any>): Boolean {
        val payload = buildString {
            append("{\"surfaceId\":\"$surfaceId\",\"actionName\":\"$actionName\"")
            if (context.isNotEmpty()) {
                append(",\"context\":{")
                context.entries.forEachIndexed { index, entry ->
                    if (index > 0) append(",")
                    append("\"${entry.key}\":\"${entry.value}\"")
                }
                append("}")
            }
            append("}")
        }
        return sendNodeEvent("user.action", payload)
    }

    private fun buildConnectOptions(): GatewayConnectOptions {
        val flags = NodeRuntimeFlags(
            cameraEnabled = _cameraEnabled.value,
            locationEnabled = _locationEnabled.value,
            xrModeEnabled = true,
        )

        return GatewayConnectOptions(
            role = "node",
            scopes = listOf("node.read", "node.write"),
            caps = InvokeCommandRegistry.advertisedCapabilities(flags),
            commands = InvokeCommandRegistry.advertisedCommands(flags),
            permissions = mapOf(
                "camera" to _cameraEnabled.value,
                "location" to _locationEnabled.value,
            ),
            client = GatewayClientInfo(
                id = "openclaw-android",
                displayName = "PortalXR Node",
                version = "1.0.0",
                platform = "android",
                mode = "node",
                instanceId = deviceId,
                deviceFamily = Build.BRAND,
                modelIdentifier = Build.MODEL,
            ),
            userAgent = "PortalXR/1.0.0 (Android ${Build.VERSION.SDK_INT})",
        )
    }

    private fun handleGatewayEvent(event: String, payloadJson: String?) {
        // Forward to chat handler first
        chatHandler?.handleGatewayEvent(event, payloadJson)

        when (event) {
            // A2UI 消息 - 转发给渲染器
            "canvas.a2ui.push",
            "canvas.a2ui.pushJSONL",
            "a2ui.push" -> {
                payloadJson?.let { a2uiHandler?.onA2UIMessage(it) }
            }
            // A2UI 重置
            "canvas.a2ui.reset",
            "a2ui.reset" -> {
                a2uiHandler?.onA2UIMessage("""{"version":"v0.10","deleteSurface":{"surfaceId":"main"}}""")
            }
            // 通用 A2UI 事件
            "a2ui.update" -> {
                payloadJson?.let { a2uiHandler?.onA2UIMessage(it) }
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
