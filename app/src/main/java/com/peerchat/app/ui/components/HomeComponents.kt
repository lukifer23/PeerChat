package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    onNewChat: () -> Unit,
    onOpenDocuments: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    TopAppBar(
        title = { Text("PeerChat") },
        actions = {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
            IconButton(onClick = onOpenDocuments) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Documents")
            }
            IconButton(onClick = onOpenModels) {
                Icon(Icons.Default.Build, contentDescription = "Models")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    )
}

private fun statusLabel(status: EngineRuntime.EngineStatus): String = when (status) {
    EngineRuntime.EngineStatus.Uninitialized -> "Uninitialized"
    EngineRuntime.EngineStatus.Idle -> "Idle"
    is EngineRuntime.EngineStatus.Loading -> "Loading"
    is EngineRuntime.EngineStatus.Loaded -> "Loaded"
    is EngineRuntime.EngineStatus.Error -> "Error"
}
