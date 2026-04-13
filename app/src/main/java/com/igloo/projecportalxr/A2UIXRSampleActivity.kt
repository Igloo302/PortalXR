package com.igloo.projecportalxr

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.platform.LocalSpatialConfiguration
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.resizable
import androidx.xr.compose.subspace.layout.width
import com.igloo.projecportalxr.ui.theme.ProjecPortalXRTheme
import org.a2ui.compose.rendering.A2UIRenderer
import org.a2ui.compose.rendering.A2UILogger
import org.a2ui.compose.rendering.A2UILogLevel
import org.a2ui.compose.rendering.ActionHandler
import org.a2ui.compose.rendering.ComponentRegistry
import org.a2ui.compose.service.A2UIService
import org.a2ui.compose.theme.A2UITheme
import org.a2ui.compose.theme.A2UIThemeConfig

/**
 * Android XR A2UI Sample - AI 动态生成 UI 在空间计算环境中的演示
 *
 * 这个示例展示了如何在 Android XR 中使用 A2UI 协议 + Gemini API：
 * - 每个 A2UI Surface 渲染在独立的 SpatialPanel 中
 * - 每个面板都可以自由移动和调整大小
 * - 使用 Gemini API 根据用户提示生成 A2UI JSON
 * - 支持 A2UI 的所有特性：双向数据绑定、事件处理、多 Surface 等
 */
class A2UIXRSampleActivity : ComponentActivity() {

    private lateinit var service: A2UIService
    private val actionLog = mutableStateListOf<String>()
    private val client = OkHttpClient()
    private val gson = Gson()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 初始化 A2UI 服务
        val logger = object : A2UILogger {
            override fun log(level: A2UILogLevel, message: String) {
                android.util.Log.d("A2UI-XR", "[$level] $message")
            }
        }

        service = A2UIService(A2UIRenderer(logger))

        // 设置 Action 处理器
        service.rendererState.renderer.setActionHandler(object : ActionHandler {
            override fun onAction(surfaceId: String, actionName: String, context: Map<String, Any>) {
                val msg = "Action: $actionName | surface: $surfaceId | ctx: $context"
                android.util.Log.d("A2UI-XR", msg)
                actionLog.add(msg)
                Toast.makeText(this@A2UIXRSampleActivity, msg, Toast.LENGTH_SHORT).show()

                // 处理自定义动作
                when (actionName) {
                    "resetAllPanels" -> {
                        resetAllPanels()
                    }
                    "clearActionLog" -> {
                        actionLog.clear()
                    }
                }
            }

            override fun openUrl(url: String) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }

            override fun showToast(message: String) {
                Toast.makeText(this@A2UIXRSampleActivity, message, Toast.LENGTH_SHORT).show()
            }
        })

        // 预加载一些演示内容 + Gemini 控制面板
        loadPrebuiltDemos(service)

        setContent {
            ProjecPortalXRTheme {
                val spatialConfiguration = LocalSpatialConfiguration.current
                if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
                    Subspace {
                        A2UISpatialContent(
                            service = service,
                            actionLog = actionLog,
                            onRequestHomeSpaceMode = spatialConfiguration::requestHomeSpaceMode,
                            onGenerateUI = { apiKey, prompt ->
                                generateUIWithGemini(apiKey, prompt)
                            }
                        )
                    }
                } else {
                    A2UI2DContent(
                        service = service,
                        actionLog = actionLog,
                        onRequestFullSpaceMode = spatialConfiguration::requestFullSpaceMode,
                        onGenerateUI = { apiKey, prompt ->
                            generateUIWithGemini(apiKey, prompt)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        service.close()
    }

    private fun resetAllPanels() {
        service.rendererState.getAllSurfaceIds().forEach { id ->
            if (id != "gemini_panel" && id != "sample_dashboard") { // 保留默认面板
                service.processMessage("""{"version":"v0.10","deleteSurface":{"surfaceId":"$id"}}""")
            }
        }
    }

    /**
     * 使用 Gemini API 生成 A2UI UI
     */
    fun generateUIWithGemini(apiKey: String, prompt: String) {
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Please enter your Gemini API Key", Toast.LENGTH_SHORT).show()
            return
        }
        if (prompt.isBlank()) {
            Toast.makeText(this, "Please enter your UI description", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            try {
                // 创建一个新的 Surface ID
                val surfaceId = "generated_" + System.currentTimeMillis()

                // 构建提示词，要求 Gemini 返回 A2UI 格式的 JSON
                val systemPrompt = """
你是一个 UI 生成助手，基于 A2UI 协议生成 Android 原生 UI 的 JSON 描述。

A2UI 协议要求：
1. 输出必须是纯 JSON，格式为: {"version":"v0.10","createSurface":{"surfaceId":"$surfaceId","catalogId":"https://a2ui.org/specification/v0_10/standard_catalog.json","theme":{"primaryColor":"#2196F3"},"sendDataModel":true},"updateComponents": [...]}
2. 使用标准组件: Text, Button, TextField, Card, Column, Row, Tabs, CheckBox, ChoicePicker, Slider, DateTimeInput, Divider, Icon, Image, List 等
3. 使用数据绑定路径如 {"path":"/form/name"}
4. 只返回 JSON，不要有其他文字说明

用户需求：$prompt
""".trimIndent()

                val requestBodyJson = gson.toJson(mapOf(
                    "contents" to listOf(
                        mapOf(
                            "parts" to listOf(
                                mapOf("text" to systemPrompt)
                            )
                        )
                    ),
                    "generationConfig" to mapOf(
                        "temperature" to 0.7,
                        "topP" to 0.95,
                        "maxOutputTokens" to 8192
                    )
                ))

                val mediaType = "application/json".toMediaType()
                val requestBody = requestBodyJson.toRequestBody(mediaType)
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash-exp:generateContent?key=$apiKey")
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (!response.isSuccessful || responseBody == null) {
                    runOnUiThread {
                        Toast.makeText(this@A2UIXRSampleActivity, "API call failed: ${response.code}", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // 解析 Gemini 响应，提取 JSON
                val jsonResponse = JSONObject(responseBody)
                val candidates = jsonResponse.getJSONArray("candidates")
                if (candidates.length() == 0) {
                    runOnUiThread {
                        Toast.makeText(this@A2UIXRSampleActivity, "No response from Gemini", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                val candidate = candidates.getJSONObject(0)
                val content = candidate.getJSONObject("content")
                val parts = content.getJSONArray("parts")
                val part = parts.getJSONObject(0)
                val text = part.getString("text")

                // 提取 JSON（去掉可能的 markdown 代码块标记）
                var cleanContent = text
                    .replace(Regex("```json"), "")
                    .replace(Regex("```"), "")
                    .trim()

                runOnUiThread {
                    try {
                        // 处理 A2UI 消息
                        service.processMessage(cleanContent)
                        actionLog.add("Generated new UI: $surfaceId")
                        Toast.makeText(this@A2UIXRSampleActivity, "UI generated successfully: $surfaceId", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Toast.makeText(this@A2UIXRSampleActivity, "Failed to parse response: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this@A2UIXRSampleActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 加载一些预设的演示场景
     */
    private fun loadPrebuiltDemos(service: A2UIService) {
        // Gemini 控制面板 - 这个由 A2UI 自己定义输入框，但是我们在 2D 模式也会显示输入框
        val controlPanel = listOf(
            """{"version":"v0.10","createSurface":{"surfaceId":"gemini_panel","catalogId":"https://a2ui.org/specification/v0_10/standard_catalog.json","theme":{"primaryColor":"#2196F3","agentDisplayName":"Gemini A2UI Generator"},"sendDataModel":true}}""",
            """{"version":"v0.10","updateComponents":{"surfaceId":"gemini_panel","components":[
                {"id":"root","component":"Card","child":"root_col"},
                {"id":"root_col","component":"Column","children":["title","divider","description","api_key_field","prompt_field","generate_btn","reset_btn"],"align":"stretch","padding":16},
                {"id":"title","component":"Text","text":"✨ Gemini AI → A2UI on Android XR","variant":"h2"},
                {"id":"divider","component":"Divider","axis":"horizontal"},
                {"id":"description","component":"Text","text":"Describe the UI you want in the prompt below, Gemini will generate it as an A2UI JSON and render it as a movable spatial panel in XR!","variant":"body"},
                {"id":"api_key_field","component":"TextField","label":"Gemini API Key","value":{"path":"/gemini/apiKey"},"variant":"shortText","placeholder":"Enter your API key"},
                {"id":"prompt_field","component":"TextField","label":"UI Description (Prompt)","value":{"path":"/gemini/prompt"},"variant":"longText","placeholder":"e.g. 'A login form with email and password fields', 'A simple dashboard showing 3 stats cards', 'A product order form'..."},
                {"id":"reset_btn_text","component":"Text","text":"Reset All Generated Panels"},
                {"id":"reset_btn","component":"Button","child":"reset_btn_text","variant":"outlined","action":{"event":{"name":"resetAllPanels"}}}
            ]}}""",
            """{"version":"v0.10","updateDataModel":{"surfaceId":"gemini_panel","path":"/gemini","value":{"apiKey":"","prompt":"A simple calculator UI with number buttons and a display"}}"""
        )
        controlPanel.forEach { service.processMessage(it) }

        // 预加载一个示例仪表盘
        val dashboard = listOf(
            """{"version":"v0.10","createSurface":{"surfaceId":"sample_dashboard","catalogId":"https://a2ui.org/specification/v0_10/standard_catalog.json","theme":{"primaryColor":"#FF6F00","agentDisplayName":"Sample Dashboard"}}}""",
            """{"version":"v0.10","updateComponents":{"surfaceId":"sample_dashboard","components":[
                {"id":"root","component":"Column","children":["title","tabs","divider","stats_row"],"align":"stretch","padding":16},
                {"id":"title","component":"Text","text":"XR Analytics Dashboard","variant":"h3"},
                {"id":"tabs","component":"Tabs","tabs":[{"title":"Overview","child":"overview"},{"title":"Settings","child":"settings"}]},
                {"id":"overview","component":"Column","children":["active_users","revenue","engagement"],"align":"stretch"},
                {"id":"active_users","component":"Row","children":["users_icon","users_col"],"align":"center"},
                {"id":"users_icon","component":"Icon","name":"group"},
                {"id":"users_col","component":"Column","children":["users_value","users_label"]},
                {"id":"users_value","component":"Text","text":{"path":"/dashboard/activeUsers"},"variant":"h2"},
                {"id":"users_label","component":"Text","text":"Active Users","variant":"caption"},
                {"id":"revenue","component":"Row","children":["money_icon","revenue_col"],"align":"center"},
                {"id":"money_icon","component":"Icon","name":"attachMoney"},
                {"id":"revenue_col","component":"Column","children":["revenue_value","revenue_label"]},
                {"id":"revenue_value","component":"Text","text":{"path":"/dashboard/revenue"},"variant":"h2"},
                {"id":"revenue_label","component":"Text","text":"Revenue","variant":"caption"},
                {"id":"engagement","component":"Row","children":["engagement_col"],"align":"center"},
                {"id":"engagement_icon","component":"Icon","name":"showChart"},
                {"id":"engagement_col","component":"Column","children":["engagement_value","engagement_label"]},
                {"id":"engagement_value","component":"Text","text":{"path":"/dashboard/engagement"},"variant":"h2"},
                {"id":"engagement_label","component":"Text","text":"Engagement","variant":"caption"},
                {"id":"stats_row","component":"Row","children":["session_slider"],"align":"center"},
                {"id":"session_slider","component":"Slider","label":"Session Length","value":{"path":"/dashboard/sessionLength"},"min":0,"max":120},
                {"id":"settings","component":"Column","children":["dark_mode","notifications","refresh_rate"],"align":"stretch"},
                {"id":"dark_mode","component":"Switch","label":"Dark Mode","value":{"path":"/settings/darkMode"}},
                {"id":"notifications","component":"Switch","label":"Push Notifications","value":{"path":"/settings/notifications"}},
                {"id":"refresh_rate","component":"ChoicePicker","label":"Refresh Rate","variant":"mutuallyExclusive","options":[{"label":"10s","value":10},{"label":"30s","value":30},{"label":"1m","value":60},{"label":"5m","value":300}],"value":{"path":"/settings/refreshRate"}}
            ]}}""",
            """{"version":"v0.10","updateDataModel":{"surfaceId":"sample_dashboard","path":"/","value":{
                "dashboard":{"activeUsers":"1,234","revenue":"$56.7K","engagement":"78%","sessionLength":45},
                "settings":{"darkMode":false,"notifications":true,"refreshRate":30}
            }}}"""
        )
        dashboard.forEach { service.processMessage(it) }
    }

    @SuppressLint("RestrictedApi")
    @Composable
    fun A2UISpatialContent(
        service: A2UIService,
        actionLog: List<String>,
        onRequestHomeSpaceMode: () -> Unit,
        onGenerateUI: (String, String) -> Unit
    ) {
        // 在 Android XR 中，每个 A2UI Surface 渲染在独立的 SpatialPanel 中
        // 用户可以自由移动和调整每个面板的大小
        val surfaceIds = service.rendererState.getAllSurfaceIds()

        // 为每个 Surface 创建一个可移动可调整大小的 SpatialPanel
        surfaceIds.forEachIndexed { index, surfaceId ->
            val ctx = service.rendererState.renderer.getSurfaceContext(surfaceId)
            val root = service.rendererState.renderer.getComponent(surfaceId, "root")

            if (ctx != null && root != null) {
                // 根据类型调整面板大小
                val (panelWidth, panelHeight) = when {
                    surfaceId.startsWith("generated_") -> Pair(800.dp, 600.dp)
                    surfaceId == "gemini_panel" -> Pair(700.dp, 500.dp)
                    index == 1 -> Pair(800.dp, 600.dp)
                    else -> Pair(700.dp, 500.dp)
                }

                SpatialPanel(
                    SubspaceModifier
                        .width(panelWidth)
                        .height(panelHeight)
                        .resizable()
                        .movable()
                ) {
                    androidx.compose.material3.Surface {
                        A2UISurface(
                            service = service,
                            surfaceId = surfaceId,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    }
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    @Composable
    fun A2UI2DContent(
        service: A2UIService,
        actionLog: List<String>,
        onRequestFullSpaceMode: () -> Unit,
        onGenerateUI: (apiKey: String, prompt: String) -> Unit
    ) {
        androidx.compose.material3.Surface {
            A2UIDemoContent(
                service = service,
                actionLog = actionLog,
                modifier = Modifier.fillMaxSize(),
                onRequestFullSpaceMode = onRequestFullSpaceMode,
                onGenerateUI = onGenerateUI
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun A2UIDemoContent(
        service: A2UIService,
        actionLog: List<String>,
        modifier: Modifier = Modifier,
        onRequestFullSpaceMode: () -> Unit,
        onGenerateUI: (apiKey: String, prompt: String) -> Unit
    ) {
        var apiKey by remember { mutableStateOf(TextFieldValue("")) }
        var prompt by remember { mutableStateOf(TextFieldValue("")) }
        var isGenerating by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("A2UI + Gemini on Android XR") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    actions = {
                        // Preview does not current support XR sessions.
                        if (!LocalInspectionMode.current && LocalSession.current != null) {
                            FullSpaceModeIconButton(
                                onClick = onRequestFullSpaceMode
                            )
                        }
                    }
                )
            },
            bottomBar = {
                BottomAppBar {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        Button(
                            onClick = {
                                isGenerating = true
                                // 获取当前输入，然后生成
                                val currentApiKey = if (apiKey.text.isNotBlank()) apiKey.text else ""
                                val currentPrompt = if (prompt.text.isNotBlank()) prompt.text else ""
                                onGenerateUI(currentApiKey, currentPrompt)
                                isGenerating = false
                            },
                            enabled = !isGenerating && (apiKey.text.isNotEmpty() && prompt.text.isNotEmpty())
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text("Generate UI")
                        }
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Gemini 输入区域
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            "Generate UI with Gemini",
                            style = MaterialTheme.typography.titleMedium
                        )
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("Gemini API Key") },
                            placeholder = { Text("Enter your API key from Google AI Studio") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            label = { Text("UI Description") },
                            placeholder = { Text("Describe what UI you want to generate...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            maxLines = 4
                        )
                    }
                }

                // 渲染所有 A2UI surfaces
                val surfaceIds = service.rendererState.getAllSurfaceIds()
                surfaceIds.forEach { surfaceId ->
                    val ctx = service.rendererState.renderer.getSurfaceContext(surfaceId)
                    val root = service.rendererState.renderer.getComponent(surfaceId, "root")
                    if (ctx != null && root != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            A2UISurface(
                                service = service,
                                surfaceId = surfaceId,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // Action 日志
                if (actionLog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Action 日志：", style = MaterialTheme.typography.titleSmall)
                    actionLog.takeLast(5).forEach { log ->
                        Text(
                            text = log,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun A2UISurface(
        service: A2UIService,
        surfaceId: String,
        modifier: Modifier = Modifier
    ) {
        val ctx = service.rendererState.renderer.getSurfaceContext(surfaceId)
        val root = service.rendererState.renderer.getComponent(surfaceId, "root")

        if (ctx != null && root != null) {
            val registry = remember(surfaceId) { ComponentRegistry(service.rendererState.renderer) }
            A2UITheme(
                config = A2UIThemeConfig(
                    primaryColor = ctx.theme?.primaryColor ?: "#2196F3"
                )
            ) {
                registry.render(root, ctx)
            }
        }
    }
}