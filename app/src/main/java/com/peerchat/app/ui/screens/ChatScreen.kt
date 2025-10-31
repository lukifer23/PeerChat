package com.peerchat.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import com.peerchat.app.ui.StreamingUiState
import com.peerchat.app.ui.components.MessageBubble
import com.peerchat.app.ui.components.PerformanceMetrics
import com.peerchat.app.ui.components.StreamingMessageBubble
import com.peerchat.data.db.Message

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    messages: List<Message>,
    streaming: StreamingUiState,
    onSend: (String) -> Unit,
) {
    var input by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    val clipboard = LocalClipboardManager.current
    var showReasoning by remember { mutableStateOf(false) }
    val isStreaming = streaming.isStreaming
    val metricsState = streaming.metrics

    val listState = rememberLazyListState()

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState
        ) {
            items(
                items = messages,
                key = { it.id }
            ) { msg ->
                MessageBubble(
                    message = msg,
                    showReasoningButton = true,
                    onReasoningClick = { showReasoning = true },
                    modifier = Modifier.animateItemPlacement()
                )

                // Show reasoning dialog if requested
                if (showReasoning && msg.role != "user") {
                    val reasoning = remember(msg.metaJson) {
                        runCatching { org.json.JSONObject(msg.metaJson).optString("reasoning") }
                            .getOrNull().orEmpty()
                    }
                    if (reasoning.isNotBlank()) {
                        AlertDialog(
                            onDismissRequest = { showReasoning = false },
                            confirmButton = { TextButton(onClick = { showReasoning = false }) { Text("Close") } },
                            title = { Text("Reasoning") },
                            text = { Text(reasoning) }
                        )
                    }
                }
            }
            item {
                AnimatedVisibility(
                    visible = streaming.isStreaming || streaming.visibleText.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut()
                ) {
                    StreamingMessageBubble(
                        currentText = streaming.visibleText,
                        showReasoning = showReasoning,
                        reasoningText = streaming.reasoningText,
                        onShowReasoningChange = { showReasoning = it }
                    )
                }
            }
        }
        metricsState?.let { metric ->
            PerformanceMetrics(
                ttfsMs = metric.ttfsMs.toFloat(),
                tps = metric.tps.toFloat()
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message…") }
            )
            androidx.compose.material3.Button(
                enabled = enabled && !isStreaming && input.text.isNotBlank(),
                onClick = {
                    if (!enabled) return@Button
                    val prompt = input.text
                    input = androidx.compose.ui.text.input.TextFieldValue("")
                    showReasoning = false
                    onSend(prompt)
                }
            ) { Text("Send") }
        }
    }
}
