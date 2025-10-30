package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.peerchat.data.db.ModelManifest
import com.peerchat.app.engine.DefaultModel
import com.peerchat.app.engine.DefaultModels
import com.peerchat.app.ui.HomeUiState
import com.peerchat.app.ui.TemplateOption
import com.peerchat.templates.TemplateCatalog
import java.io.File
import java.util.Locale

@Composable
fun SettingsDialog(
    state: HomeUiState,
    onDismiss: () -> Unit,
    onSysPromptChange: (String) -> Unit,
    onTemperatureChange: (Float) -> Unit,
    onTopPChange: (Float) -> Unit,
    onTopKChange: (Int) -> Unit,
    onMaxTokensChange: (Int) -> Unit,
    onModelPathChange: (String) -> Unit,
    onThreadChange: (String) -> Unit,
    onContextChange: (String) -> Unit,
    onGpuChange: (String) -> Unit,
    onUseVulkanChange: (Boolean) -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onSelectManifest: (ModelManifest) -> Unit,
    onDeleteManifest: (ModelManifest) -> Unit,
    onTemplateSelect: (String?) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Settings") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Chat Settings", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.sysPrompt,
                    onValueChange = onSysPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("System Prompt") }
                )
                OutlinedTextField(
                    value = state.temperature.toString(),
                    onValueChange = { it.toFloatOrNull()?.let(onTemperatureChange) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Temperature") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = state.topP.toString(),
                    onValueChange = { it.toFloatOrNull()?.let(onTopPChange) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("top_p") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                NumericField(
                    label = "top_k",
                    text = state.topK.toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let(onTopKChange) }
                )
                NumericField(
                    label = "Max tokens",
                    text = state.maxTokens.toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let(onMaxTokensChange) }
                )
                HorizontalDivider()
                Text("Chat Template", style = MaterialTheme.typography.titleMedium)
                val detected = state.detectedTemplateId
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(
                        onClick = { onTemplateSelect(null) },
                        label = { Text(if (detected != null) "Auto (${detected})" else "Auto") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.templates.forEach { opt ->
                        SuggestionChip(
                            onClick = { onTemplateSelect(opt.id) },
                            label = { Text(opt.label) }
                        )
                    }
                }
                HorizontalDivider()
                Text("Engine", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = state.modelPath,
                    onValueChange = onModelPathChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model Path") },
                    singleLine = true
                )
                NumericField("Threads", state.threadText, onThreadChange)
                NumericField("Context", state.contextText, onContextChange)
                NumericField("GPU Layers", state.gpuText, onGpuChange)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Use Vulkan")
                    Switch(checked = state.useVulkan, onCheckedChange = onUseVulkanChange)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLoadModel, enabled = state.modelPath.isNotBlank()) { Text("Load Model") }
                    Button(onClick = onUnloadModel, enabled = state.storedConfig != null) { Text("Unload") }
                }
                HorizontalDivider()
                Text("Available Models", style = MaterialTheme.typography.titleMedium)
                if (state.manifests.isEmpty()) {
                    EmptyListHint("No manifests recorded yet.")
                } else {
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(state.manifests) { manifest ->
                            val isActive = manifest.filePath == state.storedConfig?.modelPath
                            val fileExists = File(manifest.filePath).exists()
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(
                                    manifest.name + if (isActive) " (active)" else "",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "${manifest.family} • ${formatBytes(manifest.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (!fileExists) {
                                    Text(
                                        "File missing",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { onSelectManifest(manifest) }) { Text("Activate") }
                                    TextButton(onClick = { onDeleteManifest(manifest) }) { Text("Delete") }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
fun ModelsDialog(
    manifests: List<ModelManifest>,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Models") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DefaultModels.list.forEach { model ->
                    val workInfo = rememberDownloadInfo(model)
                    val manifest = manifests.firstOrNull { File(it.filePath).name.equals(model.suggestedFileName, ignoreCase = true) }
                    ModelCatalogRow(
                        model = model,
                        manifest = manifest,
                        workInfo = workInfo,
                        onDownload = { com.peerchat.app.engine.ModelDownloadManager.enqueue(context, model) },
                        onActivate = null, // TODO: Pass activate callback
                        onOpenCard = { openUrl(context, model.cardUrl) }
                    )
                    HorizontalDivider()
                }
            }
        }
    )
}

@Composable
fun RenameChatDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(TextFieldValue(currentTitle)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(title.text)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename Chat") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Chat Title") }) }
    )
}

@Composable
fun MoveChatDialog(
    folders: List<com.peerchat.data.db.Folder>,
    onMoveToFolder: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Move Chat to Folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    onMoveToFolder(null)
                    onDismiss()
                }) { Text("No folder") }
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(folders) { folder ->
                        TextButton(onClick = {
                            onMoveToFolder(folder.id)
                            onDismiss()
                        }) { Text(folder.name) }
                    }
                }
            }
        }
    )
}

@Composable
fun ForkChatDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text("Fork") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Fork Chat") },
        text = { Text("Create a duplicate conversation including existing messages?") }
    )
}

@Composable
fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(name.text)
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New Folder") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Folder name") }) }
    )
}

@Composable
fun NewChatDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(TextFieldValue("")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(title.text)
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("New Chat") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Chat title") }) }
    )
}

// Helper functions and composables

@Composable
private fun NumericField(
    label: String,
    text: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Number,
) {
    OutlinedTextField(
        value = text,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}

@Composable
fun rememberDownloadInfo(model: DefaultModel): WorkInfo? {
    val context = LocalContext.current
    val flow = androidx.compose.runtime.remember(model.id) { com.peerchat.app.engine.ModelDownloadManager.observe(context, model) }
    val infos by flow.collectAsState(initial = emptyList())
    return infos.firstOrNull()
}

@Composable
fun ModelCatalogRow(
    model: DefaultModel,
    manifest: ModelManifest?,
    workInfo: WorkInfo?,
    onDownload: () -> Unit,
    onActivate: (() -> Unit)?,
    onOpenCard: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(model.name, style = MaterialTheme.typography.bodyMedium)
        Text(model.description, style = MaterialTheme.typography.bodySmall)
        val status = when (workInfo?.state) {
            WorkInfo.State.RUNNING -> {
                val prog = workInfo.progress
                val d = prog.getLong("downloaded", -1L)
                val t = prog.getLong("total", -1L)
                if (d >= 0 && t > 0) {
                    val pct = (d * 100 / t).toInt().coerceIn(0, 100)
                    "Downloading… $pct%"
                } else "Downloading…"
            }
            WorkInfo.State.ENQUEUED -> "Waiting to download"
            WorkInfo.State.SUCCEEDED -> "Downloaded"
            WorkInfo.State.FAILED -> "Download failed"
            else -> null
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        manifest?.let {
            Text("Installed at ${File(it.filePath).name}", style = MaterialTheme.typography.bodySmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenCard) { Text("Model Card") }
            val isDownloading = workInfo?.state == WorkInfo.State.RUNNING || workInfo?.state == WorkInfo.State.ENQUEUED
            TextButton(onClick = onDownload, enabled = !isDownloading) { Text(if (isDownloading) "Downloading…" else "Download") }
            if (manifest != null && onActivate != null) {
                TextButton(onClick = onActivate) { Text("Activate") }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    return "${String.format(Locale.US, "%.2f", bytes / Math.pow(1024.0, digitGroups.toDouble()))} ${units[digitGroups]}"
}

fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
