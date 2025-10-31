package com.peerchat.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.peerchat.data.db.Message
import com.peerchat.app.ui.theme.LocalElevations
import com.peerchat.app.ui.theme.LocalSpacing
import com.peerchat.app.util.Logger
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.delay
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
    val spacing = LocalSpacing.current
    val elevations = LocalElevations.current
    val isUser = message.role == "user"
    val roleLabel = if (isUser) "You" else "Assistant"
    val messageType = if (isUser) "User message" else "Assistant message"

    val metrics = remember(message.metaJson, showMetrics) {
        if (!showMetrics || isUser) null else {
            runCatching {
                val meta = org.json.JSONObject(message.metaJson)
                meta.optJSONObject("metrics")?.let {
                    Triple(
                        it.optLong("ttfsMs", 0L),
                        it.optDouble("tps", 0.0).toFloat(),
                        it.optDouble("contextUsedPct", 0.0).toFloat()
                    )
                }
            }.getOrNull()
        }
    }

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
    val hasReasoning = reasoningData.first.isNotBlank()

    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceColorAtElevation(elevations.level1)
    }
    val bubbleContentColor = if (isUser) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val semanticsModifier = Modifier.semantics {
        contentDescription = "$messageType: ${message.contentMarkdown.take(100)}${if (message.contentMarkdown.length > 100) "..." else ""}"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.tiny),
        shape = if (isUser) MaterialTheme.shapes.large else MaterialTheme.shapes.medium,
        tonalElevation = if (isUser) 0.dp else elevations.level1,
        color = bubbleColor,
        contentColor = bubbleContentColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.medium, vertical = spacing.small)
                .then(semanticsModifier),
            verticalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(roleLabel, style = MaterialTheme.typography.labelLarge)
                AnimatedVisibility(
                    visible = metrics != null,
                    enter = expandVertically(animationSpec = spring()) + fadeIn(),
                    exit = shrinkVertically(animationSpec = spring()) + fadeOut()
                ) {
                    metrics?.let { (ttfsMs, tps, contextPct) ->
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(
                                    text = "TTFS ${ttfsMs}ms • TPS ${String.format("%.1f", tps)}",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        )
                    }
                }
            }

            MarkdownText(message.contentMarkdown)

            if (!isUser && showReasoningButton && hasReasoning && onReasoningClick != null) {
                AnimatedVisibility(
                    visible = hasReasoning,
                    enter = expandVertically(animationSpec = spring()) + fadeIn(),
                    exit = shrinkVertically(animationSpec = spring()) + fadeOut()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(spacing.tiny)
                    ) {
                        Text(
                            text = "Reasoning snapshot",
                            style = MaterialTheme.typography.labelLarge,
                            color = bubbleContentColor.copy(alpha = 0.8f)
                        )
                        Text(
                            text = "${reasoningData.third} chars • ${reasoningData.second}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = bubbleContentColor.copy(alpha = 0.7f)
                        )
                        TextButton(
                            onClick = onReasoningClick,
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Text("View reasoning")
                        }
                    }
                }
            }

            val codeBlocks by remember(message.contentMarkdown) {
                derivedStateOf { extractCodeBlocksWithLanguage(message.contentMarkdown) }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.weight(1f))
                if (showCopyButton) {
                    CopyButton(
                        text = message.contentMarkdown,
                        label = "Copy",
                        clipboard = clipboard
                    )
                }
                codeBlocks.forEachIndexed { idx, (language, code) ->
                    CopyButton(
                        text = code,
                        label = if (language.isNotBlank()) "Copy $language" else "Copy code #${idx + 1}",
                        clipboard = clipboard
                    )
                }
            }
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

    val spacing = LocalSpacing.current
    val elevations = LocalElevations.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.tiny),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = elevations.level1,
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(elevations.level1)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.medium, vertical = spacing.small),
            verticalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            if (reasoningText.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(spacing.tiny)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Reasoning", style = MaterialTheme.typography.labelLarge)
                        if (onShowReasoningChange != null) {
                            TextButton(onClick = { onShowReasoningChange(!showReasoning) }) {
                                Text(if (showReasoning) "Hide" else "Show")
                            }
                        }
                    }
                    if (showReasoning) {
                        MarkdownText(reasoningText)
                    }
                }
            }

            if (currentText.isNotBlank()) {
                MarkdownText(currentText)
                val codeBlocks by remember(currentText) {
                    derivedStateOf { extractCodeBlocksWithLanguage(currentText) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    if (showCopyButton) {
                        CopyButton(
                            text = currentText,
                            label = "Copy",
                            clipboard = clipboard
                        )
                    }
                    codeBlocks.forEachIndexed { idx, (language, code) ->
                        CopyButton(
                            text = code,
                            label = if (language.isNotBlank()) "Copy $language" else "Copy code #${idx + 1}",
                            clipboard = clipboard
                        )
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
    val spacing = LocalSpacing.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.small),
        horizontalArrangement = Arrangement.spacedBy(spacing.small)
    ) {
        AssistChip(onClick = {}, enabled = false, label = { Text("TTFS ${ttfsMs.toInt()} ms") })
        AssistChip(onClick = {}, enabled = false, label = { Text("TPS ${"%.2f".format(tps)}") })
    }
}

data class CodeBlockWithLanguage(val language: String, val code: String)

fun extractCodeBlocksWithLanguage(markdown: String): List<CodeBlockWithLanguage> {
    val pattern = Pattern.compile("```([a-zA-Z0-9_+-]*)?\\n([\\s\\S]*?)```", Pattern.MULTILINE)
    val matcher = pattern.matcher(markdown)
    val out = mutableListOf<CodeBlockWithLanguage>()
    while (matcher.find()) {
        val language = matcher.group(1)?.trim() ?: ""
        val code = matcher.group(2) ?: ""
        out.add(CodeBlockWithLanguage(language, code))
    }
    return out
}

@Composable
fun CopyButton(
    text: String,
    label: String,
    clipboard: ClipboardManager
) {
    var copied by remember { mutableStateOf(false) }
    
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    
    TextButton(
        onClick = {
            runCatching {
                clipboard.setText(AnnotatedString(text))
                copied = true
                Logger.i("CopyButton: copied to clipboard", mapOf("length" to text.length, "label" to label))
            }.onFailure { e ->
                Logger.e("CopyButton: clipboard copy failed", mapOf("error" to e.message, "label" to label), e)
                copied = false
            }
        },
        modifier = Modifier.semantics { 
            contentDescription = if (copied) "Copied!" else "Copy $label to clipboard"
        }
    ) {
        if (copied) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Copied",
                modifier = Modifier.height(16.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = "Copy",
                modifier = Modifier.height(16.dp)
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(if (copied) "Copied!" else label)
    }
}



