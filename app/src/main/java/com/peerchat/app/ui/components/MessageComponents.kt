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
    onReasoningClick: (() -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current
    val roleLabel = if (message.role == "user") "You:" else "Assistant:"

    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                roleLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            MarkdownText(message.contentMarkdown)

            if (message.role != "user" && showReasoningButton) {
                val reasoning = remember(message.metaJson) {
                    runCatching { org.json.JSONObject(message.metaJson).optString("reasoning") }
                        .getOrNull().orEmpty()
                }
                if (reasoning.isNotBlank() && onReasoningClick != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (showCopyButton) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(currentText))
                        }) { Text("Copy") }
                    }

                    val codeBlocks = remember(currentText) { extractCodeBlocks(currentText) }
                    if (codeBlocks.isNotEmpty()) {
                        codeBlocks.forEachIndexed { idx, _ ->
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(codeBlocks[idx]))
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
