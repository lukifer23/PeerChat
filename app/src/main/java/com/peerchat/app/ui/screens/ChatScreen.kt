package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.peerchat.data.db.Message
import com.peerchat.engine.EngineMetrics
import dev.jeziellago.compose.markdowntext.MarkdownText
import java.util.regex.Pattern

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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val roleLabel = if (msg.role == "user") "You:" else "Assistant:"
                    Column(modifier = Modifier.weight(1f)) {
                        Text(roleLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MarkdownText(msg.contentMarkdown)
                        if (msg.role != "user") {
                            val reasoning = remember(msg.metaJson) {
                                runCatching { org.json.JSONObject(msg.metaJson).optString("reasoning") }
                                    .getOrNull().orEmpty()
                            }
                            if (reasoning.isNotBlank()) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                    TextButton(onClick = { showReasoning = true }) { Text("Reasoning") }
                                }
                                if (showReasoning) {
                                    AlertDialog(
                                        onDismissRequest = { showReasoning = false },
                                        confirmButton = { TextButton(onClick = { showReasoning = false }) { Text("Close") } },
                                        title = { Text("Reasoning") },
                                        text = { Text(reasoning) }
                                    )
                                }
                            }
                        }
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(msg.contentMarkdown)) }) { Text("Copy") }
                }
            }
            item {
                if (reasoning.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Reasoning", style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { showReasoning = !showReasoning }) { Text(if (showReasoning) "Hide" else "Show") }
                        }
                        if (showReasoning) {
                            Text(reasoning)
                        }
                    }
                }
                if (current != "Assistant: ") {
                    Column(Modifier.fillMaxWidth()) {
                        MarkdownText(current)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(current)) }) { Text("Copy") }
                        }
                        val codeBlocks = remember(current) { extractCodeBlocks(current) }
                        if (codeBlocks.isNotEmpty()) {
                            Column(Modifier.fillMaxWidth()) {
                                codeBlocks.forEachIndexed { idx, block ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { clipboard.setText(AnnotatedString(block)) }) {
                                            Text("Copy code #${idx + 1}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        metricsState?.let { metric ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("TTFS: ${metric.ttfsMs.toInt()} ms")
                Text("TPS: ${"%.2f".format(metric.tps)}")
            }
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

private fun extractCodeBlocks(markdown: String): List<String> {
    val pattern = Pattern.compile("```[a-zA-Z0-9_-]*\\n([\\s\\S]*?)```", Pattern.MULTILINE)
    val matcher = pattern.matcher(markdown)
    val out = mutableListOf<String>()
    while (matcher.find()) {
        out.add(matcher.group(1) ?: "")
    }
    return out
}
