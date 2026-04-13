package com.igloo.projecportalxr.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igloo.projecportalxr.chat.ChatController

/**
 * Main chat panel combining message list and composer
 */
@Composable
fun ChatPanel(
    controller: ChatController,
    modifier: Modifier = Modifier
) {
    val messages by controller.messages.collectAsState()
    val streamingText by controller.streamingAssistantText.collectAsState()
    val pendingRunCount by controller.pendingRunCount.collectAsState()
    val pendingToolCalls by controller.pendingToolCalls.collectAsState()
    val healthOk by controller.healthOk.collectAsState()
    val thinkingLevel by controller.thinkingLevel.collectAsState()
    val errorText by controller.errorText.collectAsState()

    Column(modifier = modifier) {
        // Error banner
        if (!errorText.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorText ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        // Message list (takes most space)
        ChatMessageList(
            messages = messages,
            streamingText = streamingText,
            pendingRunCount = pendingRunCount,
            pendingToolCalls = pendingToolCalls,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )

        HorizontalDivider()

        // Composer at bottom
        ChatComposer(
            healthOk = healthOk,
            pendingRunCount = pendingRunCount,
            thinkingLevel = thinkingLevel,
            onSend = { text, thinking ->
                controller.sendMessage(text, thinking)
            },
            onAbort = { controller.abort() },
            onThinkingLevelChange = { level ->
                controller.setThinkingLevel(level)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        )
    }
}
