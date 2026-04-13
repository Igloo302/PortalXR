package com.igloo.portalxr.gateway

/**
 * Capability specification for node advertisement
 */
data class NodeCapabilitySpec(
    val name: String,
    val availability: NodeCapabilityAvailability = NodeCapabilityAvailability.Always,
)

/**
 * Command specification for invoke dispatch
 */
data class InvokeCommandSpec(
    val name: String,
    val requiresForeground: Boolean = false,
    val availability: InvokeCommandAvailability = InvokeCommandAvailability.Always,
)

/**
 * Capability availability conditions
 */
enum class NodeCapabilityAvailability {
    Always,
    CameraEnabled,
    LocationEnabled,
    XRModeEnabled,
}

/**
 * Command availability conditions
 */
enum class InvokeCommandAvailability {
    Always,
    CameraEnabled,
    LocationEnabled,
    XRModeEnabled,
}

/**
 * Runtime flags for capability/command filtering
 */
data class NodeRuntimeFlags(
    val cameraEnabled: Boolean = false,
    val locationEnabled: Boolean = false,
    val xrModeEnabled: Boolean = true,
)

/**
 * Registry of capabilities and commands supported by the node
 */
object InvokeCommandRegistry {
    // Core XR capabilities
    private val CAP_CANVAS = "canvas"
    private val CAP_DEVICE = "device"
    private val CAP_SYSTEM = "system"
    private val CAP_XR = "xr"
    private val CAP_CAMERA = "camera"
    private val CAP_LOCATION = "location"

    // Canvas commands
    private val CMD_CANVAS_PRESENT = "canvas.present"
    private val CMD_CANVAS_HIDE = "canvas.hide"
    private val CMD_CANVAS_NAVIGATE = "canvas.navigate"
    private val CMD_CANVAS_EVAL = "canvas.eval"
    private val CMD_CANVAS_A2UI_PUSH = "canvas.a2ui.push"
    private val CMD_CANVAS_A2UI_PUSH_JSONL = "canvas.a2ui.pushJSONL"
    private val CMD_CANVAS_A2UI_RESET = "canvas.a2ui.reset"

    // XR commands
    private val CMD_XR_SHOW_PANEL = "xr.showPanel"
    private val CMD_XR_HIDE_PANEL = "xr.hidePanel"
    private val CMD_XR_UPDATE_PANEL = "xr.updatePanel"

    // Device commands
    private val CMD_DEVICE_STATUS = "device.status"
    private val CMD_DEVICE_INFO = "device.info"

    // Camera commands
    private val CMD_CAMERA_LIST = "camera.list"
    private val CMD_CAMERA_SNAP = "camera.snap"

    // Location commands
    private val CMD_LOCATION_GET = "location.get"

    val capabilityManifest: List<NodeCapabilitySpec> = listOf(
        NodeCapabilitySpec(name = CAP_CANVAS),
        NodeCapabilitySpec(name = CAP_DEVICE),
        NodeCapabilitySpec(name = CAP_SYSTEM),
        NodeCapabilitySpec(name = CAP_XR),
        NodeCapabilitySpec(
            name = CAP_CAMERA,
            availability = NodeCapabilityAvailability.CameraEnabled
        ),
        NodeCapabilitySpec(
            name = CAP_LOCATION,
            availability = NodeCapabilityAvailability.LocationEnabled
        ),
    )

    val all: List<InvokeCommandSpec> = listOf(
        // Canvas commands
        InvokeCommandSpec(name = CMD_CANVAS_PRESENT, requiresForeground = true),
        InvokeCommandSpec(name = CMD_CANVAS_HIDE, requiresForeground = true),
        InvokeCommandSpec(name = CMD_CANVAS_NAVIGATE, requiresForeground = true),
        InvokeCommandSpec(name = CMD_CANVAS_EVAL, requiresForeground = true),
        InvokeCommandSpec(name = CMD_CANVAS_A2UI_PUSH, requiresForeground = true),
        InvokeCommandSpec(name = CMD_CANVAS_A2UI_PUSH_JSONL, requiresForeground = true),
        InvokeCommandSpec(name = CMD_CANVAS_A2UI_RESET, requiresForeground = true),

        // XR commands
        InvokeCommandSpec(name = CMD_XR_SHOW_PANEL, requiresForeground = true),
        InvokeCommandSpec(name = CMD_XR_HIDE_PANEL, requiresForeground = true),
        InvokeCommandSpec(name = CMD_XR_UPDATE_PANEL, requiresForeground = true),

        // Device commands
        InvokeCommandSpec(name = CMD_DEVICE_STATUS),
        InvokeCommandSpec(name = CMD_DEVICE_INFO),

        // Camera commands
        InvokeCommandSpec(
            name = CMD_CAMERA_LIST,
            requiresForeground = true,
            availability = InvokeCommandAvailability.CameraEnabled
        ),
        InvokeCommandSpec(
            name = CMD_CAMERA_SNAP,
            requiresForeground = true,
            availability = InvokeCommandAvailability.CameraEnabled
        ),

        // Location commands
        InvokeCommandSpec(
            name = CMD_LOCATION_GET,
            availability = InvokeCommandAvailability.LocationEnabled
        ),
    )

    private val byNameInternal: Map<String, InvokeCommandSpec> = all.associateBy { it.name }

    fun find(command: String): InvokeCommandSpec? = byNameInternal[command]

    fun advertisedCapabilities(flags: NodeRuntimeFlags): List<String> {
        return capabilityManifest
            .filter { spec ->
                when (spec.availability) {
                    NodeCapabilityAvailability.Always -> true
                    NodeCapabilityAvailability.CameraEnabled -> flags.cameraEnabled
                    NodeCapabilityAvailability.LocationEnabled -> flags.locationEnabled
                    NodeCapabilityAvailability.XRModeEnabled -> flags.xrModeEnabled
                }
            }
            .map { it.name }
    }

    fun advertisedCommands(flags: NodeRuntimeFlags): List<String> {
        return all
            .filter { spec ->
                when (spec.availability) {
                    InvokeCommandAvailability.Always -> true
                    InvokeCommandAvailability.CameraEnabled -> flags.cameraEnabled
                    InvokeCommandAvailability.LocationEnabled -> flags.locationEnabled
                    InvokeCommandAvailability.XRModeEnabled -> flags.xrModeEnabled
                }
            }
            .map { it.name }
    }
}
