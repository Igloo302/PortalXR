package com.igloo.projecportalxr

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.igloo.portalxr.gateway.*
import com.igloo.projecportalxr.chat.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import org.a2ui.compose.service.A2UIService
import org.a2ui.compose.rendering.A2UIRenderer
import org.a2ui.compose.rendering.ActionHandler

class PortalXRViewModel(application: Application) : AndroidViewModel(application), A2UIMessageHandler, ChatEventHandler {

    companion object {
        private const val PREFS_NAME = "portalxr_prefs"
        private const val KEY_HOST = "gateway_host"
        private const val KEY_PORT = "gateway_port"
        private const val KEY_TOKEN = "gateway_token"
    }

    // A2UI 渲染服务
    val a2uiService: A2UIService

    // Gateway 运行时
    private val runtime: PortalXRRuntime

    // 聊天控制器
    lateinit var chatController: ChatController
        private set

    // SharedPreferences for persistence
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // 初始化 A2UI 服务
        a2uiService = A2UIService(A2UIRenderer())

        // 设置 Action 处理器 - 用户点击按钮时回传网关
        a2uiService.rendererState.renderer.setActionHandler(object : ActionHandler {
            override fun onAction(surfaceId: String, actionName: String, context: Map<String, Any>) {
                viewModelScope.launch {
                    runtime.sendUserAction(surfaceId, actionName, context)
                }
            }

            override fun openUrl(url: String) {
                // 打开 URL
            }

            override fun showToast(message: String) {
                // 显示 Toast
            }
        })

        // 初始化 Gateway 运行时，传入 A2UI 消息处理器和聊天事件处理器
        runtime = PortalXRRuntime(application.applicationContext, this, this)

        // 初始化聊天控制器
        chatController = ChatController(viewModelScope, runtime.session)
    }

    // Connection state - 使用 mutableStateOf 以支持 Compose 重组
    private var _isConnected by mutableStateOf(false)
    val isConnected: Boolean get() = _isConnected

    private var _statusText by mutableStateOf("Offline")
    val statusText: String get() = _statusText

    val deviceId: String
        get() = runtime.deviceId

    // 监听 runtime 状态变化
    init {
        viewModelScope.launch {
            runtime.isConnected.collect { connected ->
                _isConnected = connected
                if (connected) {
                    // 连接成功后加载聊天
                    chatController.load("main")
                }
            }
        }
        viewModelScope.launch {
            runtime.statusText.collect { status ->
                _statusText = status
            }
        }
    }

    // Gateway configuration - 从 SharedPreferences 加载
    var gatewayHost by mutableStateOf(prefs.getString(KEY_HOST, "") ?: "")
    var gatewayPort by mutableStateOf(prefs.getString(KEY_PORT, "18789") ?: "18789")
    var gatewayToken by mutableStateOf(prefs.getString(KEY_TOKEN, "") ?: "")

    // Capabilities
    private var _cameraEnabled by mutableStateOf(false)
    val cameraEnabled: Boolean get() = _cameraEnabled

    private var _locationEnabled by mutableStateOf(false)
    val locationEnabled: Boolean get() = _locationEnabled

    // A2UI Surface IDs - 使用 StateFlow 来触发 UI 更新
    private var _surfaceIds by mutableStateOf<List<String>>(emptyList())
    val surfaceIds: List<String> get() = _surfaceIds

    // 更新 surfaceIds 的方法
    private fun updateSurfaceIds() {
        _surfaceIds = a2uiService.rendererState.getAllSurfaceIds()
    }

    fun enableCamera(value: Boolean) {
        _cameraEnabled = value
        runtime.setCameraEnabled(value)
    }

    fun enableLocation(value: Boolean) {
        _locationEnabled = value
        runtime.setLocationEnabled(value)
    }

    fun connect() {
        val host = gatewayHost.trim()
        val port = gatewayPort.trim().toIntOrNull() ?: 18789
        if (host.isEmpty()) {
            android.util.Log.w("PortalXR", "Connect failed: host is empty")
            return
        }

        // 保存连接参数
        prefs.edit()
            .putString(KEY_HOST, host)
            .putString(KEY_PORT, gatewayPort.trim())
            .putString(KEY_TOKEN, gatewayToken.trim())
            .apply()

        android.util.Log.i("PortalXR", "Connecting to $host:$port")

        val endpoint = GatewayEndpoint.manual(host = host, port = port)
        val auth = GatewayConnectAuth(
            token = gatewayToken.trim().takeIf { it.isNotEmpty() },
            bootstrapToken = null,
            password = null
        )
        runtime.connect(endpoint, auth)
    }

    fun disconnect() {
        runtime.disconnect()
    }

    /**
     * 实现 A2UIMessageHandler - 接收 Gateway 的 A2UI 消息
     */
    override fun onA2UIMessage(message: String) {
        android.util.Log.i("PortalXR", "onA2UIMessage: ${message.take(500)}")
        val result = a2uiService.processMessage(message)

        // 在主线程上更新状态以触发 UI 重组
        viewModelScope.launch(Dispatchers.Main) {
            updateSurfaceIds()  // 触发 UI 重组

            // 调试：打印所有组件
            surfaceIds.forEach { surfaceId ->
                val ctx = a2uiService.rendererState.renderer.getSurfaceContext(surfaceId)
                val root = a2uiService.rendererState.renderer.getComponent(surfaceId, "root")
                android.util.Log.i("PortalXR", "Surface $surfaceId: ctx=$ctx, root=$root")
            }

            android.util.Log.i("PortalXR", "processMessage result: $result, surfaces: $surfaceIds")
        }
    }

    /**
     * 实现 ChatEventHandler - 接收 Gateway 的聊天事件
     */
    override fun handleGatewayEvent(event: String, payloadJson: String?) {
        chatController.handleGatewayEvent(event, payloadJson)
    }

    override fun onDisconnected(message: String) {
        chatController.onDisconnected(message)
    }

    override fun onCleared() {
        super.onCleared()
        runtime.disconnect()
        a2uiService.close()
    }
}
