# PortalXR

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-blue.svg)](https://kotlinlang.org)
[![API](https://img.shields.io/badge/API-34%2B-brightgreen.svg)](https://android-arsenal.com/api?level=34)

**[中文](README_CN.md)** | **English**

**PortalXR** is an Android XR application that connects to [OpenClaw](https://github.com/openclaw/openclaw) Gateway, enabling AI-powered conversational interfaces with dynamic UI rendering in extended reality environments.

## Features

- **Dual Session Architecture** - Simultaneous node and operator sessions for proper role separation
- **A2UI Protocol Support** - Full implementation of [A2UI v0.8/v0.10](https://github.com/google/A2UI) for dynamic UI rendering
- **Real-time Chat** - Streaming conversation flow with OpenClaw AI assistant
- **XR Spatial UI** - Jetpack XR integration for immersive panel-based interfaces
- **Dynamic UI Rendering** - Server-driven UI components rendered via Jetpack Compose

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      PortalXR App                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │   nodeSession   │         │ operatorSession │            │
│  │   role: "node"  │         │ role: "operator"│            │
│  ├─────────────────┤         ├─────────────────┤            │
│  │ • A2UI push     │         │ • Chat events   │            │
│  │ • User actions  │         │ • Chat.send API │            │
│  └────────┬────────┘         └────────┬────────┘            │
│           │                           │                      │
│           ▼                           ▼                      │
│  ┌─────────────────┐         ┌─────────────────┐            │
│  │ A2UI Renderer   │         │ ChatController  │            │
│  │ (Jetpack        │         │ • Messages      │            │
│  │  Compose)       │         │ • Streaming     │            │
│  └─────────────────┘         └─────────────────┘            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌─────────────────┐
                    │ OpenClaw Gateway │
                    │   (Backend)      │
                    └─────────────────┘
```

### Module Structure

| Module | Description |
|--------|-------------|
| `app` | Main Android XR application |
| `android_compose` | A2UI protocol renderer using Jetpack Compose |
| `gateway` | WebSocket connection and session management |

## Requirements

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 34+ (Android 14)
- Kotlin 1.9.22
- JDK 17
- XR-enabled device or emulator (for spatial features)

## Getting Started

### 1. Clone the Repository

```bash
git clone https://github.com/your-org/PortalXR.git
cd PortalXR
```

### 2. Open in Android Studio

Open the project in Android Studio and let Gradle sync complete.

### 3. Configure Gateway Connection

In the app, configure the gateway connection:
- **Host**: Your OpenClaw Gateway hostname
- **Port**: Gateway port (default: 18789)
- **Token**: Authentication token (if required)

### 4. Build and Run

```bash
./gradlew :app:assembleDebug
```

Install on your XR device or emulator.

## Usage

### Connecting to OpenClaw

1. Launch PortalXR
2. Enter your OpenClaw Gateway connection details
3. Tap "Connect" to establish dual sessions
4. Start chatting with the AI assistant

### A2UI Dynamic UI

When OpenClaw sends A2UI content, it renders automatically in XR panels:

- **Main Panel**: Chat conversation
- **Auxiliary Panel**: Dynamic A2UI content (forms, lists, cards, etc.)

### User Interactions

- Send text messages to the AI
- Interact with dynamically rendered UI components
- Actions are sent back to OpenClaw for processing

## A2UI Components

The A2UI renderer supports 20+ standard components:

| Component | Description |
|-----------|-------------|
| Text | Typography with variants (h1, h2, body, caption) |
| Button | Primary, secondary, text variants with actions |
| TextField | Input with validation (email, url, phone, regex) |
| Card | Container with elevation |
| Row / Column | Layout containers |
| List | Scrollable lists |
| Modal | Dialog with animations |
| CheckBox / Switch | Toggle controls |
| Slider | Range input |
| Dropdown | Selection menu |
| Image / Icon | Media display |
| ProgressBar | Loading indicator |
| DateTimeInput | Date/time picker |

## Project Structure

```
PortalXR/
├── app/                           # Main application module
│   └── src/main/java/com/igloo/projecportalxr/
│       ├── MainActivity.kt        # XR activity with spatial panels
│       ├── PortalXRViewModel.kt   # ViewModel with state management
│       └── chat/
│           └── ChatController.kt  # Chat API and event handling
│
├── android_compose/               # A2UI renderer module
│   └── src/main/java/org/a2ui/compose/
│       ├── rendering/
│       │   ├── A2UIRenderer.kt    # Core message processor
│       │   └── ComponentRegistry.kt # Component renderers
│       ├── data/
│       │   └── A2UIMessage.kt     # Protocol data models
│       ├── service/
│       │   └── A2UIService.kt     # High-level service API
│       └── theme/
│           └── A2UITheme.kt       # Theme configuration
│
├── gateway/                       # Gateway connection module
│   └── src/main/java/com/igloo/portalxr/gateway/
│       ├── PortalXRRuntime.kt     # Dual session manager
│       ├── GatewaySession.kt      # WebSocket session
│       ├── DeviceIdentityStore.kt # Device authentication
│       └── InvokeDispatcher.kt    # Command routing
│
└── build.gradle.kts               # Project configuration
```

## Configuration

### Gateway Connection Options

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

### A2UI Theme Customization

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

## API Reference

### PortalXRRuntime

```kotlin
class PortalXRRuntime(
    context: Context,
    a2uiHandler: A2UIMessageHandler?,
    chatHandler: ChatEventHandler?
) {
    // Connection states
    val isConnected: StateFlow<Boolean>
    val nodeConnected: StateFlow<Boolean>
    val operatorConnected: StateFlow<Boolean>

    // Chat session for ChatController
    val chatSession: GatewaySession

    // Operations
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

## Acknowledgments

This project was inspired by and incorporates patterns from:

- **[OpenClaw](https://github.com/openclaw/openclaw)** - AI assistant platform with Gateway protocol
  - Gateway session architecture and WebSocket handling patterns
  - ChatController implementation patterns
  - Device identity and authentication approach

- **[A2UI Protocol](https://github.com/google/A2UI)** - Agent to UI specification
  - Protocol specification for dynamic UI rendering
  - Component model and message format

- **[Jetpack Compose](https://developer.android.com/jetpack/compose)** - Modern Android UI toolkit
- **[Jetpack XR](https://developer.android.com/jetpack/xr)** - Android XR development framework

Special thanks to the open source community for making these technologies available.

## Contributing

We welcome contributions! Please see our contributing guidelines:

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

### Code Style

- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use 4-space indentation
- Add KDoc comments for public APIs

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

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

## Security

If you discover a security vulnerability, please report it by opening an issue with the "security" label. Do not publicly disclose vulnerabilities.

## Support

- **Issues**: [GitHub Issues](https://github.com/your-org/PortalXR/issues)
- **Discussions**: [GitHub Discussions](https://github.com/your-org/PortalXR/discussions)
