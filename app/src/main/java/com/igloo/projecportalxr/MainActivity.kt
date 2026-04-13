package com.igloo.projecportalxr

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.igloo.portalxr.gateway.*
import org.a2ui.compose.theme.A2UITheme
import org.a2ui.compose.theme.A2UIThemeConfig

class MainActivity : ComponentActivity() {

    @SuppressLint("RestrictedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ProjecPortalXRTheme {
                val spatialConfiguration = LocalSpatialConfiguration.current
                if (LocalSpatialCapabilities.current.isSpatialUiEnabled) {
                    // XR 模式：3D 悬浮面板
                    Subspace {
                        PortalXRSpatialContent(
                            onRequestHomeSpaceMode = spatialConfiguration::requestHomeSpaceMode
                        )
                    }
                } else {
                    // 2D 模式：普通手机界面
                    PortalXR2DContent(onRequestFullSpaceMode = spatialConfiguration::requestFullSpaceMode)
                }
            }
        }
    }
}

/**
 * XR 空间模式 - 3D 悬浮面板
 */
@SuppressLint("RestrictedApi")
@Composable
fun PortalXRSpatialContent(onRequestHomeSpaceMode: () -> Unit) {
    val viewModel: PortalXRViewModel = viewModel()
    val surfaceIds = viewModel.surfaceIds

    // 控制面板 - 连接设置
    SpatialPanel(
        SubspaceModifier
            .width(800.dp)
            .height(600.dp)
            .resizable()
            .movable()
    ) {
        Surface {
            ConnectionPanel(
                viewModel = viewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            )
        }
    }

    // 为每个 A2UI Surface 创建独立的 3D 悬浮面板
    surfaceIds.forEachIndexed { index, surfaceId ->
        val ctx = viewModel.a2uiService.rendererState.renderer.getSurfaceContext(surfaceId)
        val root = viewModel.a2uiService.rendererState.renderer.getComponent(surfaceId, "root")

        if (ctx != null && root != null) {
            // 每个面板可独立移动、调整大小
            SpatialPanel(
                SubspaceModifier
                    .width(700.dp)
                    .height(500.dp)
                    .resizable()
                    .movable()
            ) {
                Surface {
                    A2UISurfaceContent(
                        service = viewModel.a2uiService,
                        surfaceId = surfaceId,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * 2D 模式
 */
@SuppressLint("RestrictedApi")
@Composable
fun PortalXR2DContent(onRequestFullSpaceMode: () -> Unit) {
    val viewModel: PortalXRViewModel = viewModel()

    Surface {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            ConnectionPanel(
                viewModel = viewModel,
                modifier = Modifier.padding(24.dp)
            )
            if (!LocalInspectionMode.current && LocalSession.current != null) {
                FullSpaceModeIconButton(
                    onClick = onRequestFullSpaceMode,
                    modifier = Modifier.padding(32.dp)
                )
            }
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
            ConnectionStatusChip(
                isConnected = viewModel.isConnected,
                statusText = viewModel.statusText
            )
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

        // 2D 模式下显示 A2UI Surfaces
        if (!LocalSpatialCapabilities.current.isSpatialUiEnabled) {
            val surfaceIds = viewModel.surfaceIds
            if (surfaceIds.isNotEmpty()) {
                Text("A2UI Surfaces", style = MaterialTheme.typography.titleMedium)
                surfaceIds.forEach { surfaceId ->
                    A2UISurfaceCard(
                        service = viewModel.a2uiService,
                        surfaceId = surfaceId,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * A2UI Surface 内容渲染（用于 XR 面板）
 */
@Composable
fun A2UISurfaceContent(
    service: org.a2ui.compose.service.A2UIService,
    surfaceId: String,
    modifier: Modifier = Modifier
) {
    val ctx = service.rendererState.renderer.getSurfaceContext(surfaceId)
    val root = service.rendererState.renderer.getComponent(surfaceId, "root")

    if (ctx != null && root != null) {
        A2UITheme(
            config = A2UIThemeConfig(
                primaryColor = ctx.theme?.primaryColor ?: "#2196F3"
            )
        ) {
            // Use the renderer's registry to ensure consistent component rendering
            Box(modifier = modifier) {
                service.rendererState.renderer.registry.render(root, ctx)
            }
        }
    } else {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

/**
 * A2UI Surface 卡片（用于 2D 模式）
 */
@Composable
fun A2UISurfaceCard(
    service: org.a2ui.compose.service.A2UIService,
    surfaceId: String,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Surface: $surfaceId", style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            A2UISurfaceContent(
                service = service,
                surfaceId = surfaceId,
                modifier = Modifier.fillMaxWidth()
            )
        }
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
fun PortalXR2dContentPreview() {
    ProjecPortalXRTheme {
        PortalXR2DContent(onRequestFullSpaceMode = {})
    }
}
