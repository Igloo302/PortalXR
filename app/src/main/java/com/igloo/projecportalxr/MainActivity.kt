package com.igloo.projecportalxr

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.igloo.projecportalxr.ui.chat.ChatPanel
import org.a2ui.compose.theme.A2UITheme
import org.a2ui.compose.theme.A2UIThemeConfig
import org.a2ui.compose.service.A2UISurface

class MainActivity : ComponentActivity() {

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ProjecPortalXRTheme {
                val spatialConfiguration = LocalSpatialConfiguration.current
                val spatialCapabilities = LocalSpatialCapabilities.current

                // 检查是否在 Full Space 模式
                if (spatialCapabilities.isSpatialUiEnabled) {
                    // Full Space 模式：可以显示多个面板
                    Subspace {
                        PortalXRSpatialContent(
                            onRequestHomeSpaceMode = spatialConfiguration::requestHomeSpaceMode
                        )
                    }
                } else {
                    // Home Space 模式：显示单个面板
                    PortalXRHomeSpaceContent(
                        onRequestFullSpaceMode = spatialConfiguration::requestFullSpaceMode
                    )
                }
            }
        }
    }
}

/**
 * Home Space 模式 - 单个面板
 * - A2UI Tab 始终显示，默认选中
 * - A2UI 内容动态更新
 */
@SuppressLint("RestrictedApi")
@Composable
fun PortalXRHomeSpaceContent(onRequestFullSpaceMode: () -> Unit) {
    val viewModel: PortalXRViewModel = viewModel()
    val surfaceIds = viewModel.surfaceIds

    // 默认选中 A2UI tab (index 2)
    var selectedTab by remember { mutableStateOf(2) }

    Surface {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部提示栏
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "PortalXR",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Button(onClick = onRequestFullSpaceMode) {
                        Text("Enter Full Space")
                    }
                }
            }

            // Tab bar - A2UI tab 始终显示
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Connection") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Chat") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("A2UI") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("Debug") }
                )
            }

            // Content
            when (selectedTab) {
                0 -> ConnectionPanel(
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
                1 -> ChatPanel(
                    controller = viewModel.chatController,
                    modifier = Modifier.fillMaxSize()
                )
                // A2UI tab - 始终显示，内容动态更新
                2 -> A2UITabContent(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
                // Debug tab
                3 -> DebugPanel(
                    viewModel = viewModel,
                    isFullSpaceMode = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }
        }
    }
}

/**
 * Full Space 模式 - 多面板
 * - 主面板：Connection/Chat/Debug tabs
 * - A2UI 窗口：独立显示
 */
@SuppressLint("RestrictedApi")
@Composable
fun PortalXRSpatialContent(onRequestHomeSpaceMode: () -> Unit) {
    val viewModel: PortalXRViewModel = viewModel()
    val surfaceIds = viewModel.surfaceIds
    var selectedTab by remember { mutableStateOf(1) } // 默认选中 Chat

    // A2UI 窗口显示控制
    var showA2uiPanel by remember { mutableStateOf(true) }

    // 主面板
    SpatialPanel(
        SubspaceModifier
            .width(900.dp)
            .height(700.dp)
            .resizable()
            .movable()
    ) {
        Surface {
            Column(modifier = Modifier.fillMaxSize()) {
                // 顶部提示栏
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "PortalXR",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Button(onClick = onRequestHomeSpaceMode) {
                            Text("Exit to Home Space")
                        }
                    }
                }

                // Tab bar - 没有 A2UI tab
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Connection") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Chat") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Debug") }
                    )
                }

                // Content
                when (selectedTab) {
                    0 -> ConnectionPanel(
                        viewModel = viewModel,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                    1 -> ChatPanel(
                        controller = viewModel.chatController,
                        modifier = Modifier.fillMaxSize()
                    )
                    2 -> DebugPanel(
                        viewModel = viewModel,
                        isFullSpaceMode = true,
                        showA2uiPanel = showA2uiPanel,
                        onShowA2uiPanelChange = { showA2uiPanel = it },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                }
            }
        }
    }

    // A2UI 窗口 - 只在有内容且用户选择显示时出现
    if (surfaceIds.isNotEmpty() && showA2uiPanel) {
        SpatialPanel(
            SubspaceModifier
                .width(700.dp)
                .height(500.dp)
                .resizable()
                .movable()
        ) {
            Surface {
                A2UIPanelContent(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * A2UI Tab 内容 - Home Space 模式
 */
@Composable
fun A2UITabContent(
    viewModel: PortalXRViewModel,
    modifier: Modifier = Modifier
) {
    val surfaceIds = viewModel.surfaceIds
    // 读取触发器确保 UI 更新
    val updateTrigger = viewModel.a2uiUpdateTrigger

    // 找到最新的有 root 组件的 surface（按 createdAt 时间戳排序）
    val validSurfaceId = surfaceIds
        .mapNotNull { surfaceId ->
            val ctx = viewModel.a2uiService.rendererState.renderer.getSurfaceContext(surfaceId)
            val rootId = ctx?.rootComponentId ?: "root"
            val root = viewModel.a2uiService.rendererState.renderer.getComponent(surfaceId, rootId)
            if (root != null && ctx != null) {
                Triple(surfaceId, ctx.createdAt, rootId)
            } else null
        }
        .maxByOrNull { it.second }
        ?.first

    Box(modifier = modifier) {
        if (validSurfaceId != null) {
            // 使用 key 确保内容更新
            key(updateTrigger, validSurfaceId) {
                A2UISurfaceContent(
                    service = viewModel.a2uiService,
                    surfaceId = validSurfaceId,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                )
            }
        } else if (surfaceIds.isNotEmpty()) {
            // 有 surface 但没有 root 组件，显示等待状态
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Waiting for content...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Surface: ${surfaceIds.first()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // 空状态
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "A2UI Panel",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Waiting for content...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * A2UI 窗口内容 - Full Space 模式
 */
@Composable
fun A2UIPanelContent(
    viewModel: PortalXRViewModel,
    modifier: Modifier = Modifier
) {
    val surfaceIds = viewModel.surfaceIds
    // 读取触发器确保 UI 更新
    val updateTrigger = viewModel.a2uiUpdateTrigger

    // 找到最新的有 root 组件的 surface（按 createdAt 时间戳排序）
    val validSurfaceId = surfaceIds
        .mapNotNull { surfaceId ->
            val ctx = viewModel.a2uiService.rendererState.renderer.getSurfaceContext(surfaceId)
            val rootId = ctx?.rootComponentId ?: "root"
            val root = viewModel.a2uiService.rendererState.renderer.getComponent(surfaceId, rootId)
            if (root != null && ctx != null) {
                Triple(surfaceId, ctx.createdAt, rootId)
            } else null
        }
        .maxByOrNull { it.second }
        ?.first

    android.util.Log.d("PortalXR", "A2UIPanelContent: surfaceIds=$surfaceIds, validSurfaceId=$validSurfaceId")

    if (validSurfaceId != null) {
        // 使用 key 确保内容更新
        key(updateTrigger, validSurfaceId) {
            A2UISurfaceContent(
                service = viewModel.a2uiService,
                surfaceId = validSurfaceId,
                modifier = modifier.padding(16.dp)
            )
        }
    } else {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text("No A2UI content")
        }
    }
}

/**
 * 连接控制面板
 */
@Composable
fun ConnectionPanel(
    viewModel: PortalXRViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("PortalXR", style = MaterialTheme.typography.headlineMedium)
            Column(horizontalAlignment = Alignment.End) {
                ConnectionStatusChip(
                    isConnected = viewModel.isConnected,
                    statusText = viewModel.statusText
                )
                if (viewModel.nodeConnected || viewModel.operatorConnected) {
                    Text(
                        text = "Node: ${if (viewModel.nodeConnected) "✓" else "✗"} | Operator: ${if (viewModel.operatorConnected) "✓" else "✗"}",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Device Info
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Device Identity", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Device ID: ${viewModel.deviceId.take(16)}...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Connection Controls
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Gateway Connection", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = viewModel.gatewayHost,
                    onValueChange = { viewModel.gatewayHost = it },
                    label = { Text("Gateway Host") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = viewModel.gatewayPort,
                        onValueChange = { viewModel.gatewayPort = it },
                        label = { Text("Port") },
                        modifier = Modifier.width(120.dp),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = viewModel.gatewayToken,
                        onValueChange = { viewModel.gatewayToken = it },
                        label = { Text("Token (optional)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.connect() },
                        enabled = !viewModel.isConnected
                    ) { Text("Connect") }
                    Button(
                        onClick = { viewModel.disconnect() },
                        enabled = viewModel.isConnected
                    ) { Text("Disconnect") }
                }
            }
        }

        // Capabilities
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Capabilities", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = viewModel.cameraEnabled,
                            onCheckedChange = { viewModel.enableCamera(it) }
                        )
                        Text("Camera")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = viewModel.locationEnabled,
                            onCheckedChange = { viewModel.enableLocation(it) }
                        )
                        Text("Location")
                    }
                }
            }
        }
    }
}

/**
 * Debug 面板
 * - Home Space: 只显示连接状态和 A2UI 组件详情
 * - Full Space: 额外显示 A2UI 窗口控制
 */
@Composable
fun DebugPanel(
    viewModel: PortalXRViewModel,
    isFullSpaceMode: Boolean,
    showA2uiPanel: Boolean = true,
    onShowA2uiPanelChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val surfaceIds = viewModel.surfaceIds

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Text("Debug Panel", style = MaterialTheme.typography.headlineMedium)

        // A2UI 组件测试
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("A2UI Component Test", style = MaterialTheme.typography.titleMedium)

                var selectedTest by remember { mutableStateOf("text") }

                // 测试选择
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("text", "button", "column", "row", "card", "image", "textfield", "checkbox", "slider", "list").forEach { test ->
                        FilterChip(
                            selected = selectedTest == test,
                            onClick = { selectedTest = test },
                            label = { Text(test, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // 渲染测试按钮
                Button(
                    onClick = {
                        val testMessage = when (selectedTest) {
                            "text" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Text":{"text":{"literalString":"Hello A2UI!"},"usageHint":"h1"}}}]}}"""
                            "button" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Column":{"children":{"explicitList":["btn1","btn2"]}}}},{"id":"btn1","component":{"Button":{"label":{"literalString":"确认"},"action":{"name":"confirm"}}}}},{"id":"btn2","component":{"Button":{"label":{"literalString":"取消"},"variant":"secondary","action":{"name":"cancel"}}}}]}}"""
                            "column" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Column":{"children":{"explicitList":["t1","t2","t3"]}}}},{"id":"t1","component":{"Text":{"text":{"literalString":"第一行"},"usageHint":"body"}}},{"id":"t2","component":{"Text":{"literalString":"第二行"},"usageHint":"body"}}},{"id":"t3","component":{"Text":{"text":{"literalString":"第三行"},"usageHint":"body"}}}]}}"""
                            "row" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Row":{"children":{"explicitList":["t1","t2","t3"]}}}},{"id":"t1","component":{"Text":{"text":{"literalString":"左"},"usageHint":"body"}}},{"id":"t2","component":{"Text":{"text":{"literalString":"中"},"usageHint":"body"}}},{"id":"t3","component":{"Text":{"text":{"literalString":"右"},"usageHint":"body"}}}]}}"""
                            "card" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Card":{"child":"content"}}}},{"id":"content","component":{"Column":{"children":{"explicitList":["title","desc"]}}}},{"id":"title","component":{"Text":{"text":{"literalString":"卡片标题"},"usageHint":"h2"}}},{"id":"desc","component":{"Text":{"text":{"literalString":"这是卡片内容描述"},"usageHint":"body"}}}]}}"""
                            "image" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Column":{"children":{"explicitList":["img","desc"]}}}},{"id":"img","component":{"Image":{"url":{"literalString":"https://picsum.photos/200/150"}}}},{"id":"desc","component":{"Text":{"text":{"literalString":"示例图片"},"usageHint":"caption"}}}]}}"""
                            "textfield" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Column":{"children":{"explicitList":["tf1","tf2"]}}}},{"id":"tf1","component":{"TextField":{"label":{"literalString":"用户名"},"placeholder":{"literalString":"请输入用户名"}}}},{"id":"tf2","component":{"TextField":{"label":{"literalString":"密码"},"placeholder":{"literalString":"请输入密码"}}}}]}}"""
                            "checkbox" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Column":{"children":{"explicitList":["cb1","cb2","cb3"]}}}},{"id":"cb1","component":{"CheckBox":{"label":{"literalString":"选项 A"}}}},{"id":"cb2","component":{"CheckBox":{"label":{"literalString":"选项 B"}}}},{"id":"cb3","component":{"CheckBox":{"label":{"literalString":"选项 C"}}}}]}}"""
                            "slider" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Column":{"children":{"explicitList":["t","s"]}}}},{"id":"t","component":{"Text":{"text":{"literalString":"滑块控件"},"usageHint":"h3"}}},{"id":"s","component":{"Slider":{"min":0.0,"max":100.0,"step":1.0}}}]}}"""
                            "list" -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Column":{"children":{"explicitList":["item1","item2","item3","item4"]}}}},{"id":"item1","component":{"Text":{"text":{"literalString":"📌 列表项 1"},"usageHint":"body"}}},{"id":"item2","component":{"Text":{"text":{"literalString":"📌 列表项 2"},"usageHint":"body"}}},{"id":"item3","component":{"Text":{"text":{"literalString":"📌 列表项 3"},"usageHint":"body"}}},{"id":"item4","component":{"Text":{"text":{"literalString":"📌 列表项 4"},"usageHint":"body"}}}]}}"""
                            else -> """{"surfaceUpdate":{"surfaceId":"test","components":[{"id":"root","component":{"Text":{"text":{"literalString":"Unknown test"},"usageHint":"body"}}}]}}"""
                        }
                        viewModel.onA2UIMessage(testMessage)
                        viewModel.onA2UIMessage("""{"beginRendering":{"surfaceId":"test","root":"root"}}""")
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("渲染测试组件")
                }
            }
        }

        // A2UI 窗口控制 - 只在 Full Space 模式显示
        if (isFullSpaceMode) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("A2UI Window Control", style = MaterialTheme.typography.titleMedium)

                    Text("Surface IDs: ${if (surfaceIds.isEmpty()) "None" else surfaceIds.joinToString()}")
                    Text("Window Visible: $showA2uiPanel")

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onShowA2uiPanelChange(true) },
                            enabled = !showA2uiPanel && surfaceIds.isNotEmpty()
                        ) {
                            Text("Show Window")
                        }
                        Button(
                            onClick = { onShowA2uiPanelChange(false) },
                            enabled = showA2uiPanel
                        ) {
                            Text("Hide Window")
                        }
                    }
                }
            }
        }

        // 连接状态
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Connection Status", style = MaterialTheme.typography.titleMedium)
                Text("Node Connected: ${viewModel.nodeConnected}")
                Text("Operator Connected: ${viewModel.operatorConnected}")
                Text("Overall: ${if (viewModel.isConnected) "Connected" else "Disconnected"}")
            }
        }

        // A2UI 组件详情
        if (surfaceIds.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("A2UI Components", style = MaterialTheme.typography.titleMedium)
                    surfaceIds.forEach { surfaceId ->
                        val ctx = viewModel.a2uiService.rendererState.renderer.getSurfaceContext(surfaceId)
                        val root = viewModel.a2uiService.rendererState.renderer.getComponent(surfaceId, "root")
                        Text("Surface: $surfaceId")
                        Text("  Context: ${ctx != null}")
                        Text("  Root: ${root != null}")
                        if (root != null) {
                            Text("  Root Component: ${root.component}")
                        }
                    }
                }
            }
        }
    }
}

/**
 * A2UI Surface 内容渲染
 */
@Composable
fun A2UISurfaceContent(
    service: org.a2ui.compose.service.A2UIService,
    surfaceId: String,
    modifier: Modifier = Modifier
) {
    // 获取 beginRendering 指定的 root 组件 ID
    val ctx = service.rendererState.renderer.getSurfaceContext(surfaceId)
    val rootComponentId = ctx?.rootComponentId ?: "root"

    // 使用库提供的 A2UISurface，传入自定义 root 组件 ID
    Box(modifier = modifier) {
        A2UISurface(
            surfaceId = surfaceId,
            rootComponentId = rootComponentId,
            rendererState = service.rendererState
        )
    }
}

@Composable
fun ConnectionStatusChip(isConnected: Boolean, statusText: String) {
    Surface(
        color = if (isConnected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = statusText,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun FullSpaceModeIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_full_space_mode_switch),
            contentDescription = stringResource(R.string.switch_to_full_space_mode)
        )
    }
}

@Composable
fun HomeSpaceModeIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_home_space_mode_switch),
            contentDescription = stringResource(R.string.switch_to_home_space_mode)
        )
    }
}

@PreviewLightDark
@Composable
fun PortalXRHomeSpaceContentPreview() {
    ProjecPortalXRTheme {
        PortalXRHomeSpaceContent(onRequestFullSpaceMode = {})
    }
}
