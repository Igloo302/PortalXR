package com.igloo.projecportalxr.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Chat message input composer
 */
@Composable
fun ChatComposer(
    healthOk: Boolean,
    pendingRunCount: Int,
    thinkingLevel: String,
    onSend: (String, String) -> Unit,
    onAbort: () -> Unit,
    onThinkingLevelChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var input by remember { mutableStateOf("") }
    var showThinkingMenu by remember { mutableStateOf(false) }
    val isRunning = pendingRunCount > 0

    Column(modifier = modifier) {
        // Thinking level selector
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Thinking: $thinkingLevel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("off", "low", "medium", "high").forEach { level ->
                    FilterChip(
                        selected = thinkingLevel == level,
                        onClick = { onThinkingLevelChange(level) },
                        label = { Text(level.replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.height(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Type a message...") },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                enabled = healthOk && !isRunning,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (input.isNotBlank() && healthOk && !isRunning) {
                            onSend(input, thinkingLevel)
                            input = ""
                        }
                    }
                )
            )

            // Abort button (when running)
            if (isRunning) {
                FilledTonalButton(
                    onClick = onAbort,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text("Stop")
                }
            } else {
                // Send button
                FilledIconButton(
                    onClick = {
                        if (input.isNotBlank() && healthOk) {
                            onSend(input, thinkingLevel)
                            input = ""
                        }
                    },
                    enabled = healthOk && input.isNotBlank()
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }

        // Status indicator
        if (!healthOk) {
            Text(
                text = "Not connected",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
