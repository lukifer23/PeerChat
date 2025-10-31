package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.peerchat.app.R
import com.peerchat.app.ui.theme.LocalElevations
import com.peerchat.app.ui.theme.LocalSpacing
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatusRow(status: EngineRuntime.EngineStatus, metrics: EngineMetrics) {
    val spacing = LocalSpacing.current
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(spacing.small),
        verticalArrangement = Arrangement.spacedBy(spacing.tiny)
    ) {
        listOf(
            "Status: ${statusLabel(status)}",
            "TTFS ${metrics.ttfsMs.toInt()} ms",
            "TPS ${String.format(Locale.US, "%.2f", metrics.tps)}"
        ).forEach { label ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(label, style = MaterialTheme.typography.labelLarge) }
            )
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val spacing = LocalSpacing.current
    val elevations = LocalElevations.current
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(elevations.level2),
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevations.level1)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = spacing.medium, vertical = spacing.small + spacing.tiny),
            verticalArrangement = Arrangement.spacedBy(spacing.small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.HomeListRow(
    title: String,
    subtitle: String? = null,
    actions: List<Pair<String, () -> Unit>>,
) {
    val spacing = LocalSpacing.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.tiny),
        verticalArrangement = Arrangement.spacedBy(spacing.tiny)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (actions.isNotEmpty()) {
            FlowRow(
                modifier = Modifier.wrapContentWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.tiny),
                verticalArrangement = Arrangement.spacedBy(spacing.tiny)
            ) {
                actions.forEach { (label, handler) ->
                    AssistChip(onClick = handler, label = { Text(label) })
                }
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
    val spacing = LocalSpacing.current
    val elevations = LocalElevations.current
    TopAppBar(
        navigationIcon = {
            Icon(
                painter = painterResource(R.drawable.ic_launcher_monochrome),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(start = spacing.small)
                    .size(28.dp)
            )
        },
        title = {},
        actions = {
            AssistChip(
                onClick = onNewChat,
                label = { Text("New chat") },
                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
            )
            Spacer(modifier = Modifier.size(spacing.small))
            IconButton(onClick = onOpenDocuments) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Documents")
            }
            IconButton(onClick = onOpenModels) {
                Icon(Icons.Default.Build, contentDescription = "Models")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(elevations.level2),
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface
        )
    )
}

private fun statusLabel(status: EngineRuntime.EngineStatus): String = when (status) {
    EngineRuntime.EngineStatus.Uninitialized -> "Uninitialized"
    EngineRuntime.EngineStatus.Idle -> "Idle"
    is EngineRuntime.EngineStatus.Loading -> "Loading"
    is EngineRuntime.EngineStatus.Loaded -> "Loaded"
    is EngineRuntime.EngineStatus.Error -> "Error"
}
