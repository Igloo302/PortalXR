package com.igloo.portalxr.gateway

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Dispatches invoke commands to appropriate handlers
 */
class InvokeDispatcher(
    private val context: Context,
    private val isForeground: () -> Boolean,
    private val cameraEnabled: () -> Boolean,
    private val locationEnabled: () -> Boolean,
    private val onXrPanelCommand: suspend (command: String, paramsJson: String?) -> GatewaySession.InvokeResult,
    private val onA2uiPush: suspend (jsonl: String) -> GatewaySession.InvokeResult,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun handleInvoke(command: String, paramsJson: String?): GatewaySession.InvokeResult {
        val spec = InvokeCommandRegistry.find(command)
            ?: return GatewaySession.InvokeResult.error(
                code = "INVALID_REQUEST",
                message = "INVALID_REQUEST: unknown command"
            )

        if (spec.requiresForeground && !isForeground()) {
            return GatewaySession.InvokeResult.error(
                code = "NODE_BACKGROUND_UNAVAILABLE",
                message = "NODE_BACKGROUND_UNAVAILABLE: command requires foreground"
            )
        }

        availabilityError(spec.availability)?.let { return it }

        return when (command) {
            // Device commands
            "device.status" -> handleDeviceStatus()
            "device.info" -> handleDeviceInfo()

            // XR commands
            "xr.showPanel", "xr.hidePanel", "xr.updatePanel" -> onXrPanelCommand(command, paramsJson)

            // Canvas commands
            "canvas.present", "canvas.navigate" -> handleCanvasNavigate(paramsJson)
            "canvas.hide" -> GatewaySession.InvokeResult.ok(null)
            "canvas.eval" -> handleCanvasEval(paramsJson)

            // A2UI commands
            "canvas.a2ui.push", "canvas.a2ui.pushJSONL" -> handleA2uiPush(paramsJson)
            "canvas.a2ui.reset" -> handleA2uiReset()

            // Camera commands (placeholder)
            "camera.list" -> handleCameraList()
            "camera.snap" -> handleCameraSnap(paramsJson)

            // Location commands (placeholder)
            "location.get" -> handleLocationGet()

            else -> GatewaySession.InvokeResult.error(
                code = "INVALID_REQUEST",
                message = "INVALID_REQUEST: unimplemented command"
            )
        }
    }

    private fun handleDeviceStatus(): GatewaySession.InvokeResult {
        val result = buildJsonObject {
            put("status", JsonPrimitive("online"))
            put("foreground", JsonPrimitive(isForeground()))
            put("timestamp", JsonPrimitive(System.currentTimeMillis()))
        }
        return GatewaySession.InvokeResult.ok(result.toString())
    }

    private fun handleDeviceInfo(): GatewaySession.InvokeResult {
        val result = buildJsonObject {
            put("brand", JsonPrimitive(Build.BRAND))
            put("model", JsonPrimitive(Build.MODEL))
            put("device", JsonPrimitive(Build.DEVICE))
            put("sdkVersion", JsonPrimitive(Build.VERSION.SDK_INT))
            put("release", JsonPrimitive(Build.VERSION.RELEASE))
        }
        return GatewaySession.InvokeResult.ok(result.toString())
    }

    private fun handleCanvasNavigate(paramsJson: String?): GatewaySession.InvokeResult {
        // Placeholder - would integrate with A2UI renderer
        val url = try {
            paramsJson?.let {
                val obj = json.parseToJsonElement(it) as? JsonObject
                (obj?.get("url") as? JsonPrimitive)?.content
            }
        } catch (_: Exception) { null } ?: ""
        return GatewaySession.InvokeResult.ok("""{"navigated":"$url"}""")
    }

    private fun handleCanvasEval(paramsJson: String?): GatewaySession.InvokeResult {
        // Placeholder - would integrate with A2UI renderer
        val js = try {
            paramsJson?.let {
                val obj = json.parseToJsonElement(it) as? JsonObject
                (obj?.get("javaScript") as? JsonPrimitive)?.content
            }
        } catch (_: Exception) { null } ?: return GatewaySession.InvokeResult.error(
            code = "INVALID_REQUEST",
            message = "INVALID_REQUEST: javaScript required"
        )
        return GatewaySession.InvokeResult.ok("""{"result":null}""")
    }

    private suspend fun handleA2uiPush(paramsJson: String?): GatewaySession.InvokeResult {
        val jsonl = try {
            paramsJson?.let {
                val obj = json.parseToJsonElement(it) as? JsonObject
                (obj?.get("jsonl") as? JsonPrimitive)?.content
            }
        } catch (_: Exception) { null } ?: return GatewaySession.InvokeResult.error(
            code = "INVALID_REQUEST",
            message = "INVALID_REQUEST: jsonl required"
        )
        return onA2uiPush(jsonl)
    }

    private fun handleA2uiReset(): GatewaySession.InvokeResult {
        // Reset is handled by sending a deleteSurface message
        return GatewaySession.InvokeResult.ok("""{"reset":true}""")
    }

    private fun handleCameraList(): GatewaySession.InvokeResult {
        // Placeholder - would integrate with camera manager
        return GatewaySession.InvokeResult.ok("""{"cameras":[]}""")
    }

    private fun handleCameraSnap(paramsJson: String?): GatewaySession.InvokeResult {
        // Placeholder - would integrate with camera manager
        return GatewaySession.InvokeResult.error(
            code = "CAMERA_UNAVAILABLE",
            message = "CAMERA_UNAVAILABLE: camera not configured"
        )
    }

    private fun handleLocationGet(): GatewaySession.InvokeResult {
        // Placeholder - would integrate with location manager
        return GatewaySession.InvokeResult.error(
            code = "LOCATION_UNAVAILABLE",
            message = "LOCATION_UNAVAILABLE: location not configured"
        )
    }

    private fun availabilityError(availability: InvokeCommandAvailability): GatewaySession.InvokeResult? {
        return when (availability) {
            InvokeCommandAvailability.Always -> null
            InvokeCommandAvailability.CameraEnabled ->
                if (cameraEnabled()) null else GatewaySession.InvokeResult.error(
                    code = "CAMERA_DISABLED",
                    message = "CAMERA_DISABLED: enable Camera in Settings"
                )
            InvokeCommandAvailability.LocationEnabled ->
                if (locationEnabled()) null else GatewaySession.InvokeResult.error(
                    code = "LOCATION_DISABLED",
                    message = "LOCATION_DISABLED: enable Location in Settings"
                )
            InvokeCommandAvailability.XRModeEnabled -> null // Always enabled for XR app
        }
    }
}
