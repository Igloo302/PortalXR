package com.igloo.projecportalxr.chat

import kotlinx.serialization.json.JsonObject

/**
 * Chat message data model
 */
data class ChatMessage(
    val id: String,
    val role: String,              // "user", "assistant", "system"
    val content: List<ChatMessageContent>,
    val timestampMs: Long?,
)

/**
 * Chat message content (text, image, etc.)
 */
data class ChatMessageContent(
    val type: String = "text",
    val text: String? = null,
    val mimeType: String? = null,
    val fileName: String? = null,
    val base64: String? = null,
)

/**
 * Pending tool call state
 */
data class ChatPendingToolCall(
    val toolCallId: String,
    val name: String,
    val args: JsonObject? = null,
    val startedAtMs: Long,
    val isError: Boolean? = null,
)

/**
 * Chat session entry
 */
data class ChatSessionEntry(
    val key: String,
    val updatedAtMs: Long?,
    val displayName: String? = null,
)

/**
 * Chat history response
 */
data class ChatHistory(
    val sessionKey: String,
    val sessionId: String?,
    val thinkingLevel: String?,
    val messages: List<ChatMessage>,
)

/**
 * Outgoing attachment
 */
data class OutgoingAttachment(
    val type: String,
    val mimeType: String,
    val fileName: String,
    val base64: String,
)
