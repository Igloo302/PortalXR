# PortalXR

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API-34%2B-brightgreen.svg)](https://android-arsenal.com/api?level=34)

**[English](README.md)** | **中文**

**PortalXR** 是一款 Android XR 应用，可连接到 [OpenClaw](https://github.com/openclaw/openclaw) Gateway，实现 AI 驱动的对话界面与扩展现实环境中的动态 UI 渲染。

## 功能特性

- **双会话架构** - 同时维护 node 和 operator 会话，实现正确的角色分离
- **A2UI 协议支持** - 完整实现 [A2UI v0.8/v0.10](https://github.com/google/A2UI) 动态 UI 渲染协议
- **实时聊天** - 与 OpenClaw AI 助手的流式对话流程
- **XR 空间 UI** - Jetpack XR 集成，实现沉浸式面板界面
- **动态 UI 渲染** - 服务端驱动的 UI 组件，通过 Jetpack Compose 渲染

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                      PortalXR 应用                           │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │   nodeSession   │         │ operatorSession │            │
│  │   role: "node"  │         │ role: "operator"│            │
│  ├─────────────────┤         ├─────────────────┤            │
│  │ • A2UI 推送     │         │ • 聊天事件      │            │
│  │ • 用户操作      │         │ • Chat.send API │            │
│  └────────┬────────┘         └────────┬────────┘            │
│           │                           │                      │
│           ▼                           ▼                      │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │ A2UI 渲染器     │         │ ChatController  │            │
│  │ (Jetpack        │         │ • 消息列表      │            │
│  │  Compose)       │         │ • 流式响应      │            │
│  └─────────────────┘         └─────────────────┘            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ OpenClaw Gateway │
                    │     (后端)       │
                    └─────────────────┘
```

### 模块结构

| 模块 | 描述 |
|------|------|
| `app` | 主 Android XR 应用 |
| `android_compose` | 使用 Jetpack Compose 的 A2UI 协议渲染器 |
| `gateway` | WebSocket 连接和会话管理 |

## 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- Android SDK 34+ (Android 14)
- Kotlin 1.9.22
- JDK 17
- XR 设备或模拟器（用于空间功能）

## 快速开始

### 1. 克隆仓库

```bash
git clone https://github.com/your-org/PortalXR.git
cd PortalXR
```

### 2. 在 Android Studio 中打开

打开项目，等待 Gradle 同步完成。

### 3. 配置 Gateway 连接

在应用中配置网关连接：
- **主机**: OpenClaw Gateway 主机名
- **端口**: Gateway 端口（默认：18789）
- **令牌**: 认证令牌（如需要）

### 4. 构建运行

```bash
./gradlew :app:assembleDebug
```

安装到 XR 设备或模拟器。

## 使用说明

### 连接 OpenClaw

1. 启动 PortalXR
2. 输入 OpenClaw Gateway 连接信息
3. 点击"连接"建立双会话
4. 开始与 AI 助手对话

### A2UI 动态 UI

当 OpenClaw 发送 A2UI 内容时，会自动在 XR 面板中渲染：

- **主面板**: 聊天对话
- **辅助面板**: 动态 A2UI 内容（表单、列表、卡片等）

### 用户交互

- 发送文本消息给 AI
- 与动态渲染的 UI 组件交互
- 操作回传给 OpenClaw 处理

## A2UI 组件

A2UI 渲染器支持 20+ 标准组件：

| 组件 | 描述 |
|------|------|
| Text | 排版文本，支持多种变体（h1, h2, body, caption） |
| Button | 按钮，支持 primary、secondary、text 变体 |
| TextField | 输入框，支持验证（email、url、phone、regex） |
| Card | 卡片容器，带阴影 |
| Row / Column | 布局容器 |
| List | 可滚动列表 |
| Modal | 对话框，带动画 |
| CheckBox / Switch | 开关控件 |
| Slider | 滑块输入 |
| Dropdown | 下拉选择菜单 |
| Image / Icon | 图片/图标显示 |
| ProgressBar | 加载进度条 |
| DateTimeInput | 日期时间选择器 |

## 项目结构

```
PortalXR/
├── app/                           # 主应用模块
│   └── src/main/java/com/igloo/projecportalxr/
│       ├── MainActivity.kt        # XR Activity，包含空间面板
│       ├── PortalXRViewModel.kt   # ViewModel，状态管理
│       └── chat/
│           └── ChatController.kt  # 聊天 API 和事件处理
│
├── android_compose/               # A2UI 渲染器模块
│   └── src/main/java/org/a2ui/compose/
│       ├── rendering/
│       │   ├── A2UIRenderer.kt    # 核心消息处理器
│       │   └── ComponentRegistry.kt # 组件渲染器
│       ├── data/
│       │   └── A2UIMessage.kt     # 协议数据模型
│       ├── service/
│       │   └── A2UIService.kt     # 高级服务 API
│       └── theme/
│           └── A2UITheme.kt       # 主题配置
│
├── gateway/                       # Gateway 连接模块
│   └── src/main/java/com/igloo/portalxr/gateway/
│       ├── PortalXRRuntime.kt     # 双会话管理器
│       ├── GatewaySession.kt      # WebSocket 会话
│       ├── DeviceIdentityStore.kt # 设备认证
│       └── InvokeDispatcher.kt    # 命令路由
│
└── build.gradle.kts               # 项目配置
```

## 配置

### Gateway 连接选项

```kotlin
val endpoint = GatewayEndpoint.manual(
    host = "your-gateway.local",
    port = 18789
)

val auth = GatewayConnectAuth(
    token = "your-auth-token",
    bootstrapToken = null,
    password = null
)

runtime.connect(endpoint, auth)
```

### A2UI 主题定制

```kotlin
val themeConfig = A2UIThemeConfig(
    primaryColor = "#6200EE",
    secondaryColor = "#03DAC6",
    darkMode = false,
    borderRadius = 12
)

A2UITheme(config = themeConfig) {
    A2UISurface(surfaceId = "main")
}
```

## API 参考

### PortalXRRuntime

```kotlin
class PortalXRRuntime(
    context: Context,
    a2uiHandler: A2UIMessageHandler?,
    chatHandler: ChatEventHandler?
) {
    // 连接状态
    val isConnected: StateFlow<Boolean>
    val nodeConnected: StateFlow<Boolean>
    val operatorConnected: StateFlow<Boolean>

    // ChatController 使用的会话
    val chatSession: GatewaySession

    // 操作方法
    fun connect(endpoint: GatewayEndpoint, auth: GatewayConnectAuth)
    fun disconnect()
    suspend fun sendUserAction(surfaceId: String, actionName: String, context: Map<String, Any>)
}
```

### ChatController

```kotlin
class ChatController(
    scope: CoroutineScope,
    session: GatewaySession
) {
    val messages: StateFlow<List<ChatMessage>>
    val streamingAssistantText: StateFlow<String?>

    fun sendMessage(message: String, thinkingLevel: String)
    fun handleGatewayEvent(event: String, payloadJson: String?)
}
```

### A2UIRenderer

```kotlin
class A2UIRenderer {
    fun processMessage(message: String): Result<Unit>
    fun getSurfaceContext(surfaceId: String): SurfaceContext?
    fun getComponent(surfaceId: String, componentId: String): Component?
    fun setActionHandler(handler: ActionHandler?)
    fun dispose()
}
```

## 致谢

本项目受到以下项目的启发并采用了相关模式：

- **[OpenClaw](https://github.com/openclaw/openclaw)** - AI 助手平台，提供 Gateway 协议
  - Gateway 会话架构和 WebSocket 处理模式
  - ChatController 实现模式
  - 设备身份和认证方法

- **[A2UI Protocol](https://github.com/google/A2UI)** - Agent to UI 规范
  - 动态 UI 渲染协议规范
  - 组件模型和消息格式

- **[Jetpack Compose](https://developer.android.com/jetpack/compose)** - 现代 Android UI 工具包
- **[Jetpack XR](https://developer.android.com/jetpack/xr)** - Android XR 开发框架

感谢开源社区的贡献。

## 贡献指南

欢迎贡献代码！请遵循以下步骤：

1. Fork 仓库
2. 创建功能分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建 Pull Request

### 代码风格

- 遵循 [Kotlin 编码规范](https://kotlinlang.org/docs/coding-conventions.html)
- 使用 4 空格缩进
- 为公共 API 添加 KDoc 注释

## 许可证

本项目采用 Apache License 2.0 许可证 - 详见 [LICENSE](LICENSE) 文件。

```
Copyright 2025 PortalXR Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## 安全

如发现安全漏洞，请通过创建带有 "security" 标签的 Issue 报告。请勿公开披露漏洞。

## 支持

- **问题反馈**: [GitHub Issues](https://github.com/your-org/PortalXR/issues)
- **讨论**: [GitHub Discussions](https://github.com/your-org/PortalXR/discussions)
