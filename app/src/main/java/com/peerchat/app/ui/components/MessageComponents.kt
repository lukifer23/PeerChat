package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.peerchat.data.db.Message
import dev.jeziellago.compose.markdowntext.MarkdownText
import java.util.regex.Pattern

@Composable
fun MessageBubble(
    message: Message,
    modifier: Modifier = Modifier,
    showCopyButton: Boolean = true,
    showReasoningButton: Boolean = false,
    onReasoningClick: (() -> Unit)? = null,
    showMetrics: Boolean = false
) {
    val clipboard = LocalClipboardManager.current
    val roleLabel = if (message.role == "user") "You:" else "Assistant:"
    
    // Parse metrics from metadata
    val metrics = remember(message.metaJson, showMetrics) {
        if (!showMetrics || message.role == "user") null else {
            runCatching {
                val meta = org.json.JSONObject(message.metaJson)
                val metricsObj = meta.optJSONObject("metrics")
                metricsObj?.let {
                    Triple(
                        it.optLong("ttfsMs", 0),
                        it.optDouble("tps", 0.0).toFloat(),
                        it.optDouble("contextUsedPct", 0.0).toFloat()
                    )
                }
            }.getOrNull()
        }
    }

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(
                    roleLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (metrics != null) {
                    Text(
                        "TTFS: ${metrics.first}ms • TPS: ${String.format("%.1f", metrics.second)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            MarkdownText(message.contentMarkdown)

            if (message.role != "user" && showReasoningButton) {
                val reasoningData by remember(message.metaJson) {
                    derivedStateOf {
                        runCatching {
                            val meta = org.json.JSONObject(message.metaJson)
                            val reasoning = meta.optString("reasoning", "")
                            val reasoningDuration = meta.optLong("reasoningDurationMs", 0)
                            val reasoningChars = meta.optInt("reasoningChars", 0)
                            Triple(reasoning, reasoningDuration, reasoningChars)
                        }.getOrNull() ?: Triple("", 0L, 0)
                    }
                }
                if (reasoningData.first.isNotBlank() && onReasoningClick != null) {
                    Row(
                        Modifier.fillMaxWidth(), 
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        if (reasoningData.second > 0 || reasoningData.third > 0) {
                            Text(
                                "${reasoningData.third} chars${if (reasoningData.second > 0) " • ${reasoningData.second}ms" else ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }
                        TextButton(onClick = onReasoningClick) { Text("Reasoning") }
                    }
                }
            }
        }

        if (showCopyButton) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(message.contentMarkdown))
            }) { Text("Copy") }
        }
    }
}

@Composable
fun StreamingMessageBubble(
    currentText: String,
    modifier: Modifier = Modifier,
    showCopyButton: Boolean = true,
    showReasoning: Boolean = false,
    reasoningText: String = "",
    onShowReasoningChange: ((Boolean) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current

    Column(modifier.fillMaxWidth()) {
        if (reasoningText.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Reasoning", style = MaterialTheme.typography.titleSmall)
                    if (onShowReasoningChange != null) {
                        TextButton(onClick = { onShowReasoningChange(!showReasoning) }) {
                            Text(if (showReasoning) "Hide" else "Show")
                        }
                    }
                }
                if (showReasoning) {
                    Text(reasoningText)
                }
            }
        }

        if (currentText.isNotBlank()) {
            Column(Modifier.fillMaxWidth()) {
                MarkdownText(currentText)
                val codeBlocks by remember(currentText) {
                    derivedStateOf { extractCodeBlocks(currentText) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (showCopyButton) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(currentText))
                        }) { Text("Copy") }
                    }

                    if (codeBlocks.isNotEmpty()) {
                        codeBlocks.forEachIndexed { idx, block ->
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(block))
                            }) {
                                Text("Copy code #${idx + 1}")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PerformanceMetrics(
    ttfsMs: Float,
    tps: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("TTFS: ${ttfsMs.toInt()} ms")
        Text("TPS: ${"%.2f".format(tps)}")
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



