package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import java.util.Locale

@Composable
fun StatusRow(status: EngineRuntime.EngineStatus, metrics: EngineMetrics) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SuggestionChip(onClick = {}, enabled = false, label = { Text("Status: ${statusLabel(status)}") })
        SuggestionChip(onClick = {}, enabled = false, label = { Text("TTFS ${metrics.ttfsMs.toInt()} ms") })
        SuggestionChip(onClick = {}, enabled = false, label = { Text("TPS ${String.format(Locale.US, "%.2f", metrics.tps)}") })
    }
}

@Composable
fun SectionCard(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
            content()
        }
    }
}

@Composable
fun EmptyListHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ColumnScope.HomeListRow(
    title: String,
    subtitle: String? = null,
    actions: List<Pair<String, () -> Unit>>,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            actions.forEach { (label, handler) ->
                TextButton(onClick = handler) { Text(label) }
            }
        }
    }
}

@Composable
fun HomeTopBar(
    docImportInProgress: Boolean,
    modelImportInProgress: Boolean,
    compact: Boolean = false,
    onNewChat: () -> Unit,
    onImportDoc: () -> Unit,
    onImportModel: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDocuments: () -> Unit,
) {
    androidx.compose.material3.TopAppBar(
        title = { Text("PeerChat") },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!compact) {
                    HomeActionChip(label = "New Chat", onClick = onNewChat)
                    HomeActionChip(
                        label = if (docImportInProgress) "Importing…" else "Import Doc",
                        enabled = !docImportInProgress,
                        onClick = onImportDoc
                    )
                    HomeActionChip(
                        label = if (modelImportInProgress) "Importing…" else "Import Model",
                        enabled = !modelImportInProgress,
                        onClick = onImportModel
                    )
                    HomeActionChip(label = "Documents", onClick = onOpenDocuments)
                    HomeActionChip(label = "Models", onClick = onOpenModels)
                }
                androidx.compose.material3.IconButton(onClick = onOpenSettings) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Default.Settings,
                        contentDescription = "Settings"
                    )
                }

                // Overflow menu to ensure actions stay accessible on compact widths
                val (menuOpen, setMenuOpen) = remember { mutableStateOf(false) }
                androidx.compose.material3.IconButton(onClick = { setMenuOpen(true) }) {
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Default.MoreVert,
                        contentDescription = "More"
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { setMenuOpen(false) }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("New Chat") },
                        onClick = { setMenuOpen(false); onNewChat() }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Import Doc") },
                        onClick = { setMenuOpen(false); onImportDoc() },
                        enabled = !docImportInProgress
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Import Model") },
                        onClick = { setMenuOpen(false); onImportModel() },
                        enabled = !modelImportInProgress
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Documents") },
                        onClick = { setMenuOpen(false); onOpenDocuments() }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Models") },
                        onClick = { setMenuOpen(false); onOpenModels() }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = { setMenuOpen(false); onOpenSettings() }
                    )
                }
            }
        }
    )
}

@Composable
private fun HomeActionChip(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AssistChip(onClick = onClick, enabled = enabled, label = { Text(label) })
}

private fun statusLabel(status: EngineRuntime.EngineStatus): String = when (status) {
    EngineRuntime.EngineStatus.Uninitialized -> "Uninitialized"
    EngineRuntime.EngineStatus.Idle -> "Idle"
    is EngineRuntime.EngineStatus.Loading -> "Loading"
    is EngineRuntime.EngineStatus.Loaded -> "Loaded"
    is EngineRuntime.EngineStatus.Error -> "Error"
}
