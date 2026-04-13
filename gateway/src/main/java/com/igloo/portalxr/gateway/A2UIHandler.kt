package com.igloo.portalxr.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Handles A2UI message processing from gateway
 */
class A2UIHandler(
    private val onA2UIMessage: (messages: List<String>) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decode A2UI messages from gateway command
     */
    fun decodeA2uiMessages(command: String, paramsJson: String?): List<String> {
        if (paramsJson.isNullOrBlank()) return emptyList()

        val params = json.parseToJsonElement(paramsJson).asObjectOrNull() ?: return emptyList()

        return when (command) {
            "canvas.a2ui.push" -> {
                val messagesArray = params["messages"]?.asArrayOrNull()
                messagesArray?.mapNotNull { it.asStringOrNull() } ?: emptyList()
            }
            "canvas.a2ui.pushJSONL" -> {
                val jsonl = params["jsonl"]?.asStringOrNull() ?: return emptyList()
                jsonl.lines().filter { it.isNotBlank() }
            }
            else -> emptyList()
        }
    }

    /**
     * Generate JavaScript to apply A2UI messages
     */
    fun a2uiApplyMessagesJS(messages: List<String>): String {
        val messagesJson = messages.joinToString(",") { "\"${it.replace("\"", "\\\"")}\"" }
        return "window.__a2uiApplyMessages([$messagesJson]);"
    }

    /**
     * Generate JavaScript to reset A2UI state
     */
    val a2uiResetJS: String
        get() = "window.__a2uiReset();"

    companion object {
        /**
         * Extract action name from A2UI user action
         */
        fun extractActionName(userActionObj: JsonObject): String? {
            return userActionObj["name"]?.asStringOrNull()
                ?: userActionObj["action"]?.asStringOrNull()
        }

        /**
         * Format agent message for A2UI action
         */
        fun formatAgentMessage(
            actionName: String,
            sessionKey: String,
            surfaceId: String,
            sourceComponentId: String,
            host: String,
            instanceId: String,
            contextJson: String?,
        ): String {
            return buildString {
                append("Execute action '$actionName' on surface '$surfaceId' ")
                append("from component '$sourceComponentId'. ")
                if (!contextJson.isNullOrBlank()) {
                    append("Context: $contextJson. ")
                }
            }
        }
    }
}

// Extension functions
private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray
private fun JsonElement?.asStringOrNull(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> content
    else -> null
}
