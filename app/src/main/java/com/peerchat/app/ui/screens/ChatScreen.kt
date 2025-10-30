package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.peerchat.app.ui.components.MessageBubble
import com.peerchat.app.ui.components.PerformanceMetrics
import com.peerchat.app.ui.components.StreamingMessageBubble
import com.peerchat.data.db.Message
import com.peerchat.engine.EngineMetrics

@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    messages: List<Message>,
    onSend: (String, (String) -> Unit, (String, EngineMetrics) -> Unit) -> Unit,
) {
    var input by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue("")) }
    var streaming by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("Assistant: ") }
    var metricsState by remember { mutableStateOf<EngineMetrics?>(null) }
    val clipboard = LocalClipboardManager.current
    var reasoning by remember { mutableStateOf("") }
    var showReasoning by remember { mutableStateOf(false) }
    var inReasoning by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        LazyColumn(Modifier.weight(1f)) {
            items(messages) { msg ->
                MessageBubble(
                    message = msg,
                    showReasoningButton = true,
                    onReasoningClick = { showReasoning = true }
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
                StreamingMessageBubble(
                    currentText = if (current != "Assistant: ") current else "",
                    showReasoning = showReasoning,
                    reasoningText = reasoning,
                    onShowReasoningChange = { showReasoning = it }
                )
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
                enabled = enabled && !streaming && input.text.isNotBlank(),
                onClick = {
                    if (!enabled) return@Button
                    val prompt = input.text
                    input = androidx.compose.ui.text.input.TextFieldValue("")
                    current = "Assistant: "
                    metricsState = null
                    reasoning = ""
                    inReasoning = false
                    streaming = true
                    onSend(prompt, { token ->
                        val t = token
                        if (!inReasoning && (t.contains("<think>") || t.contains("<reasoning>") || t.contains("<|startofthink|>"))) {
                            inReasoning = true
                        }
                        if (inReasoning) {
                            reasoning += t
                        } else {
                            current += t
                        }
                        if (inReasoning && (t.contains("</think>") || t.contains("</reasoning>") || t.contains("<|endofthink|>"))) {
                            inReasoning = false
                        }
                    }, { _, metrics ->
                        streaming = false
                        metricsState = metrics
                        current = "Assistant: "
                        reasoning = ""
                        inReasoning = false
                    })
                }
            ) { Text("Send") }
        }
    }
}