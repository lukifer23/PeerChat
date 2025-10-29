package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.peerchat.app.engine.DefaultModel
import com.peerchat.app.engine.DefaultModels
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.app.ui.theme.PeerChatTheme
import com.peerchat.data.db.ModelManifest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.work.WorkInfo

@Composable
fun ModelsScreen(navController: NavHostController) {
    PeerChatTheme {
        val context = LocalContext.current
        val viewModel: ModelsViewModel = viewModel {
            ModelsViewModel(context.applicationContext as android.app.Application)
        }
        val uiState by viewModel.uiState.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Models") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        AssistChip(
                            onClick = { /* TODO: Import model */ },
                            label = { Text("Import") }
                        )
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Available Models", style = MaterialTheme.typography.titleMedium)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DefaultModels.list) { model ->
                        val workState = rememberDownloadState(model)
                        val manifest = uiState.manifests.firstOrNull { File(it.filePath).name.equals(model.suggestedFileName, ignoreCase = true) }
                        ModelCatalogRow(
                            model = model,
                            manifest = manifest,
                            workState = workState,
                            onDownload = { ModelDownloadManager.enqueue(context, model) },
                            onActivate = manifest?.let { m -> { viewModel.activateManifest(m) } },
                            onOpenCard = { openUrl(context, model.cardUrl) }
                        )
                        HorizontalDivider()
                    }
                }

                Text("Installed Models", style = MaterialTheme.typography.titleMedium)
                if (uiState.manifests.isEmpty()) {
                    Text(
                        "No models installed yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.manifests) { manifest ->
                            val isActive = manifest.filePath == uiState.activeModelPath
                            val fileExists = File(manifest.filePath).exists()
                            InstalledModelCard(
                                manifest = manifest,
                                isActive = isActive,
                                fileExists = fileExists,
                                onActivate = { viewModel.activateManifest(manifest) },
                                onDelete = { viewModel.deleteManifest(manifest) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelCatalogRow(
    model: DefaultModel,
    manifest: ModelManifest?,
    workState: WorkInfo.State?,
    onDownload: () -> Unit,
    onActivate: (() -> Unit)?,
    onOpenCard: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(model.name, style = MaterialTheme.typography.bodyMedium)
            Text(model.description, style = MaterialTheme.typography.bodySmall)
            val status = when {
                workState == WorkInfo.State.RUNNING -> "Downloading…"
                workState == WorkInfo.State.ENQUEUED -> "Waiting to download"
                workState == WorkInfo.State.SUCCEEDED -> "Downloaded"
                workState == WorkInfo.State.FAILED -> "Download failed"
                else -> null
            }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            manifest?.let {
                Text("Installed at ${File(it.filePath).name}", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onOpenCard) { Text("Model Card") }
                val isDownloading = workState == WorkInfo.State.RUNNING || workState == WorkInfo.State.ENQUEUED
                TextButton(onClick = onDownload, enabled = !isDownloading) { Text(if (isDownloading) "Downloading…" else "Download") }
                if (manifest != null && onActivate != null) {
                    TextButton(onClick = onActivate) { Text("Activate") }
                }
            }
        }
    }
}

@Composable
private fun InstalledModelCard(
    manifest: ModelManifest,
    isActive: Boolean,
    fileExists: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                manifest.name + if (isActive) " (active)" else "",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "${manifest.family} • ${formatBytes(manifest.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Imported ${dateFormat.format(Date(manifest.importedAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!fileExists) {
                Text(
                    "File missing",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onActivate) { Text("Activate") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun rememberDownloadState(model: DefaultModel): WorkInfo.State? {
    val context = LocalContext.current
    val flow = androidx.compose.runtime.remember(model.id) { ModelDownloadManager.observe(context, model) }
    val infos by flow.collectAsState(initial = emptyList())
    return infos.firstOrNull()?.state
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return "${String.format(Locale.US, "%.2f", bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
