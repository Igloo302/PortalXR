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
 *
 * Uses dual session architecture:
 * - nodeSession: handles A2UI push (invoke) and user actions (agent.request)
 * - operatorSession: handles chat events and messages
 */
class PortalXRRuntime(
    private val context: Context,
    private val a2uiHandler: A2UIMessageHandler? = null,
    private val chatHandler: ChatEventHandler? = null,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val identityStore = DeviceIdentityStore(appContext)

    // Connection states - node session
    private val _nodeConnected = MutableStateFlow(false)
    val nodeConnected: StateFlow<Boolean> = _nodeConnected.asStateFlow()

    // Connection states - operator session
    private val _operatorConnected = MutableStateFlow(false)
    val operatorConnected: StateFlow<Boolean> = _operatorConnected.asStateFlow()

    // Combined connection state
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

    // Invoke dispatcher for node session
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
            jsonl.lines().filter { it.isNotBlank() }.forEach { line ->
                android.util.Log.i("PortalXR", "A2UI line: $line")
                a2uiHandler?.onA2UIMessage(line)
            }
            GatewaySession.InvokeResult.ok("""{"pushed":true}""")
        },
    )

    // Node session - handles A2UI push (invoke) and user actions
    private val nodeSession: GatewaySession = GatewaySession(
        scope = scope,
        identityStore = identityStore,
        onConnected = { name, remote, mainSessionKey ->
            _nodeConnected.value = true
            _serverName.value = name
            updateConnectionState()
            android.util.Log.i("PortalXR", "Node session connected")
        },
        onDisconnected = { message ->
            _nodeConnected.value = false
            updateConnectionState()
            android.util.Log.i("PortalXR", "Node session disconnected: $message")
        },
        onEvent = { event, payloadJson ->
            // Node session doesn't handle events
        },
        onInvoke = { req ->
            invokeDispatcher.handleInvoke(req.command, req.paramsJson)
        },
    )

    // Operator session - handles chat events
    private val operatorSession: GatewaySession = GatewaySession(
        scope = scope,
        identityStore = identityStore,
        onConnected = { name, remote, mainSessionKey ->
            _operatorConnected.value = true
            updateConnectionState()
            android.util.Log.i("PortalXR", "Operator session connected")
        },
        onDisconnected = { message ->
            _operatorConnected.value = false
            updateConnectionState()
            chatHandler?.onDisconnected(message)
            android.util.Log.i("PortalXR", "Operator session disconnected: $message")
        },
        onEvent = { event, payloadJson ->
            handleGatewayEvent(event, payloadJson)
        },
        onInvoke = null, // Operator session doesn't handle invoke
    )

    // Expose operator session for ChatController
    val chatSession: GatewaySession get() = operatorSession

    val deviceId: String
        get() = identityStore.loadOrCreate().deviceId

    private fun updateConnectionState() {
        val bothConnected = _nodeConnected.value && _operatorConnected.value
        _isConnected.value = bothConnected
        _statusText.value = when {
            bothConnected -> "Connected"
            _nodeConnected.value -> "Node connected"
            _operatorConnected.value -> "Operator connected"
            else -> "Offline"
        }
    }

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

        // Connect both sessions in parallel
        // Each session handles its own nonce/signature
        nodeSession.connect(
            endpoint = endpoint,
            token = auth.token,
            bootstrapToken = auth.bootstrapToken,
            password = auth.password,
            options = buildNodeConnectOptions(),
            tls = null,
        )

        operatorSession.connect(
            endpoint = endpoint,
            token = auth.token,
            bootstrapToken = auth.bootstrapToken,
            password = auth.password,
            options = buildOperatorConnectOptions(),
            tls = null,
        )
    }

    fun disconnect() {
        connectedEndpoint = null
        nodeSession.disconnect()
        operatorSession.disconnect()
    }

    fun reconnect() {
        nodeSession.reconnect()
        operatorSession.reconnect()
    }

    suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean {
        return nodeSession.sendNodeEvent(event, payloadJson)
    }

    suspend fun request(method: String, paramsJson: String?): String {
        return operatorSession.request(method, paramsJson)
    }

    /**
     * 发送用户操作回网关 (via node session)
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
        // Use agent.request event for user actions
        return nodeSession.sendNodeEvent("agent.request", buildAgentRequestPayload(surfaceId, actionName))
    }

    private fun buildAgentRequestPayload(surfaceId: String, actionName: String): String {
        return """{"message":"action:$actionName","sessionKey":"main","thinking":"low","deliver":false,"key":"$surfaceId:$actionName"}"""
    }

    private fun buildNodeConnectOptions(): GatewayConnectOptions {
        return GatewayConnectOptions(
            role = "node",
            scopes = emptyList(),
            caps = listOf("canvas", "device"),  // Advertise capabilities
            commands = listOf(
                // Canvas commands
                "canvas.present", "canvas.hide", "canvas.navigate", "canvas.eval", "canvas.snapshot",
                // A2UI commands
                "canvas.a2ui.push", "canvas.a2ui.pushJSONL", "canvas.a2ui.reset",
                // Device commands
                "device.status", "device.info", "device.permissions", "device.health",
                // XR commands
                "xr.showPanel", "xr.hidePanel", "xr.updatePanel",
            ),
            permissions = emptyMap(),
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

    private fun buildOperatorConnectOptions(): GatewayConnectOptions {
        return GatewayConnectOptions(
            role = "operator",
            scopes = listOf("operator.read", "operator.write"),
            caps = emptyList(),
            commands = emptyList(),
            permissions = emptyMap(),
            client = GatewayClientInfo(
                id = "openclaw-android",
                displayName = "PortalXR",
                version = "1.0.0",
                platform = "android",
                mode = "ui",
                instanceId = deviceId,
                deviceFamily = Build.BRAND,
                modelIdentifier = Build.MODEL,
            ),
            userAgent = "PortalXR/1.0.0 (Android ${Build.VERSION.SDK_INT})",
        )
    }

    private fun handleGatewayEvent(event: String, payloadJson: String?) {
        // Forward to chat handler
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
