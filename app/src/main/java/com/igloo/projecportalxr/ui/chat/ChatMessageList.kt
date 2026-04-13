package com.igloo.projecportalxr.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.igloo.projecportalxr.chat.ChatMessage
import com.igloo.projecportalxr.chat.ChatPendingToolCall
import kotlinx.coroutines.launch

/**
 * Scrollable chat message list
 */
@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    streamingText: String?,
    pendingRunCount: Int,
    pendingToolCalls: List<ChatPendingToolCall>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Auto-scroll on new messages
    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty() || !streamingText.isNullOrBlank()) {
            scope.launch {
                listState.animateScrollToItem(0)
            }
        }
    }

    LazyColumn(
        state = listState,
        reverseLayout = true,  // Newest at bottom
        contentPadding = PaddingValues(vertical = 8.dp),
        modifier = modifier
    ) {
        // Streaming text bubble (at the "bottom" which is index 0 with reverseLayout)
        if (!streamingText.isNullOrBlank()) {
            item {
                ChatStreamingBubble(text = streamingText)
            }
        }

        // Pending tool calls
        items(pendingToolCalls.size) { index ->
            ChatPendingToolBubble(toolName = pendingToolCalls[index].name)
        }

        // Typing indicator (when running but no streaming text yet)
        if (pendingRunCount > 0 && streamingText.isNullOrBlank() && pendingToolCalls.isEmpty()) {
            item {
                ChatTypingIndicator()
            }
        }

        // Message bubbles (reversed for reverseLayout)
        items(messages.size) { index ->
            ChatMessageBubble(message = messages[messages.size - 1 - index])
        }
    }
}
