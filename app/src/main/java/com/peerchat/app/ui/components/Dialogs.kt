package com.peerchat.app.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.peerchat.data.db.ModelManifest
import com.peerchat.app.engine.DefaultModel
import com.peerchat.app.engine.DefaultModels
import com.peerchat.app.ui.ChatUiState
import com.peerchat.app.ui.HomeUiState
import com.peerchat.app.ui.TemplateOption
import com.peerchat.engine.EngineRuntime
import com.peerchat.templates.TemplateCatalog
import com.peerchat.app.util.FormatUtils.formatBytes
import com.peerchat.app.ui.components.LinearProgressWithLabel
import com.peerchat.app.ui.components.InlineLoadingIndicator
import java.io.File
import java.util.Locale

@Composable
fun SettingsDialog(
    homeState: HomeUiState,
    chatState: ChatUiState,
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
    onUseGpuModeChange: (Boolean) -> Unit,
    onUseVulkanChange: (Boolean) -> Unit,
    onLoadModel: () -> Unit,
    onUnloadModel: () -> Unit,
    onSelectManifest: (ModelManifest) -> Unit,
    onDeleteManifest: (ModelManifest) -> Unit,
    onTemplateSelect: (String?) -> Unit = {},
    onRebuildAnnIndex: () -> Unit,
    onDocScoreCacheChange: (Int) -> Unit,
) {
    AppDialog(
        title = "Settings",
        onDismiss = onDismiss,
        content = {
            val modelState = homeState.model
            val cacheStats = modelState.cacheStats
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Chat Settings", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = chatState.sysPrompt,
                    onValueChange = onSysPromptChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("System Prompt") }
                )
                OutlinedTextField(
                    value = chatState.temperature.toString(),
                    onValueChange = { value -> value.toFloatOrNull()?.let(onTemperatureChange) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Temperature") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = chatState.topP.toString(),
                    onValueChange = { value -> value.toFloatOrNull()?.let(onTopPChange) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("top_p") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                NumericField(
                    label = "top_k",
                    text = chatState.topK.toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let(onTopKChange) }
                )
                NumericField(
                    label = "Max tokens",
                    text = chatState.maxTokens.toString(),
                    onValueChange = { value -> value.toIntOrNull()?.let(onMaxTokensChange) }
                )
                HorizontalDivider()
                Text("Chat Template", style = MaterialTheme.typography.titleMedium)
                val detected = chatState.detectedTemplateId ?: modelState.detectedTemplateId
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SuggestionChip(
                        onClick = { onTemplateSelect(null) },
                        label = { Text(if (detected != null) "Auto (${detected})" else "Auto") }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (opt in chatState.templates) {
                        SuggestionChip(
                            onClick = { onTemplateSelect(opt.id) },
                            label = { Text(opt.label) }
                        )
                    }
                }
                HorizontalDivider()
                Text("Engine", style = MaterialTheme.typography.titleMedium)
                StatusRow(status = modelState.engineStatus, metrics = modelState.engineMetrics)
                
                // Show loading progress if model is loading
                if (modelState.isLoadingModel || modelState.importingModel) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (modelState.loadProgress != null) {
                            LinearProgressWithLabel(
                                progress = modelState.loadProgress.progress.coerceIn(0f, 1f),
                                label = modelState.loadProgress.message,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Stage: ${modelState.loadProgress.stage.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            InlineLoadingIndicator(
                                message = if (modelState.importingModel) "Applying model configuration…" else "Loading model…",
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
                
                // Show preload status if available
                if (modelState.preloadStatuses.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Preload Status: ${modelState.preloadStats.preloadedModels}/${modelState.preloadStats.maxPreloadedModels} models",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                OutlinedTextField(
                    value = modelState.modelPath,
                    onValueChange = onModelPathChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Model Path") },
                    singleLine = true
                )
                NumericField("Threads", modelState.threadText, onThreadChange)
                NumericField("Context", modelState.contextText, onContextChange)

                // CPU/GPU Mode Selection
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Inference Mode", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onUseGpuModeChange(false) }
                        ) {
                            RadioButton(
                                selected = !modelState.useGpuMode,
                                onClick = { onUseGpuModeChange(false) },
                                enabled = !modelState.importingModel
                            )
                            Text("CPU Only", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { onUseGpuModeChange(true) }
                        ) {
                            RadioButton(
                                selected = modelState.useGpuMode,
                                onClick = { onUseGpuModeChange(true) },
                                enabled = !modelState.importingModel
                            )
                            Text("GPU Accelerated", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                NumericField("GPU Layers", modelState.gpuText, onGpuChange)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Use Vulkan")
                    Switch(
                        checked = modelState.useVulkan,
                        onCheckedChange = onUseVulkanChange,
                        enabled = !modelState.importingModel
                    )
                }
                val canLoad = modelState.modelPath.isNotBlank() && !modelState.importingModel
                val canUnload = modelState.engineStatus is EngineRuntime.EngineStatus.Loaded && !modelState.importingModel
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onLoadModel, enabled = canLoad) {
                        Text(if (modelState.importingModel) "Working…" else "Load Model")
                    }
                    Button(onClick = onUnloadModel, enabled = canUnload) { Text("Unload") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Metrics & Cache", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "KV Cache: ${cacheStats.hits} hits, ${cacheStats.misses} misses, ${cacheStats.evictions} evictions",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Cache disk usage: ${formatBytes(cacheStats.bytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (modelState.engineMetrics.ttfsMs > 0) {
                        Text(
                            "Last generation: TTFS ${modelState.engineMetrics.ttfsMs}ms • TPS ${String.format("%.1f", modelState.engineMetrics.tps)} • Context ${String.format("%.1f", modelState.engineMetrics.contextUsedPct)}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                HorizontalDivider()
                Text("RAG Index", style = MaterialTheme.typography.titleMedium)
                val ragState = homeState.rag
                Text(
                    "Documents: ${ragState.totalDocuments} • Chunks: ${ragState.totalChunks} • Embeddings: ${ragState.totalEmbeddings}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Average chunk tokens: ${String.format(Locale.US, "%.1f", ragState.averageChunkTokens)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Doc-score cache: ${ragState.docScoreCacheSize}/${ragState.docScoreCacheMax}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                var docScoreTarget by remember(ragState.docScoreCacheMax) {
                    mutableStateOf(ragState.docScoreCacheMax)
                }
                Slider(
                    value = docScoreTarget.toFloat(),
                    onValueChange = { value ->
                        val clamped = value.toInt().coerceIn(200, 8000)
                        docScoreTarget = clamped
                    },
                    valueRange = 200f..8000f,
                    steps = 58,
                    onValueChangeFinished = { onDocScoreCacheChange(docScoreTarget) }
                )
                Text(
                    "Cache max entries: $docScoreTarget",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onRebuildAnnIndex) {
                    Text("Rebuild ANN Index")
                }
                HorizontalDivider()
                Text("Installed Models", style = MaterialTheme.typography.titleMedium)
                val activePath = modelState.storedConfig?.modelPath ?: modelState.modelPath
                if (modelState.manifests.isEmpty()) {
                    EmptyListHint("No models installed yet.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        modelState.manifests.forEach { manifest ->
                            ModelManifestRow(
                                manifest = manifest,
                                isActive = manifest.filePath == activePath,
                                isBusy = modelState.importingModel,
                                onLoad = { onSelectManifest(manifest) },
                                onVerify = null,
                                onDelete = { onDeleteManifest(manifest) }
                            )
                        }
                    }
                }
                HorizontalDivider()
                Text("Catalog Downloads", style = MaterialTheme.typography.titleMedium)
                EmptyListHint("Use catalog entries below to download curated defaults.")
            }
        }
    )
}

@Composable
fun ModelsDialog(
    manifests: List<ModelManifest>,
    onDismiss: () -> Unit,
    onActivate: ((ModelManifest) -> Unit)? = null,
) {
    val context = LocalContext.current

    AppDialog(
        title = "Models",
        onDismiss = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DefaultModels.list.forEach { model ->
                    val workInfo = rememberDownloadInfo(model)
                    val manifest = manifests.firstOrNull { File(it.filePath).name.equals(model.suggestedFileName, ignoreCase = true) }
                    ModelCatalogRow(
                        model = model,
                        manifest = manifest,
                        workInfo = workInfo,
                        onDownload = { com.peerchat.app.engine.ModelDownloadManager.enqueue(context, model) },
                        onActivate = manifest?.let { { onActivate?.invoke(it) } },
                        onOpenCard = { openUrl(context, model.cardUrl) }
                    )
                    HorizontalDivider()
                }
            }
        }
    )
}

@Composable
fun RenameFolderDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue(currentName)) }

    AppDialog(
        title = "Rename Folder",
        onDismiss = onDismiss,
        confirmText = "Save",
        onConfirm = { onConfirm(name.text) },
        dismissText = "Cancel",
        content = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Folder name") }
            )
        }
    )
}

@Composable
fun DeleteFolderDialog(
    folderName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val trimmed = folderName.trim()
    val label = if (trimmed.isEmpty()) "this folder" else "folder \"$trimmed\""

    AppDialog(
        title = "Delete Folder",
        onDismiss = onDismiss,
        confirmText = "Delete",
        onConfirm = onConfirm,
        dismissText = "Cancel",
        content = {
            Text("Delete $label? Chats remain available under All Chats.")
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

    AppDialog(
        title = "Rename Chat",
        onDismiss = onDismiss,
        confirmText = "Save",
        onConfirm = { onConfirm(title.text) },
        dismissText = "Cancel",
        content = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Chat Title") }) }
    )
}

@Composable
fun MoveChatDialog(
    folders: List<com.peerchat.data.db.Folder>,
    onMoveToFolder: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AppDialog(
        title = "Move Chat to Folder",
        onDismiss = onDismiss,
        content = {
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
    AppDialog(
        title = "Fork Chat",
        onDismiss = onDismiss,
        confirmText = "Fork",
        onConfirm = onConfirm,
        dismissText = "Cancel",
        content = { Text("Create a duplicate conversation including existing messages?") }
    )
}

@Composable
fun NewFolderDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(TextFieldValue("")) }

    AppDialog(
        title = "New Folder",
        onDismiss = onDismiss,
        confirmText = "Create",
        onConfirm = { onConfirm(name.text) },
        dismissText = "Cancel",
        content = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Folder name") }) }
    )
}

@Composable
fun DeleteChatDialog(
    chatTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val trimmed = chatTitle.trim()
    val label = if (trimmed.isEmpty()) "this chat" else "chat \"$trimmed\""

    AppDialog(
        title = "Delete Chat",
        onDismiss = onDismiss,
        confirmText = "Delete",
        onConfirm = onConfirm,
        dismissText = "Cancel",
        content = {
            Text("Delete $label? This permanently removes its messages.")
        }
    )
}

@Composable
fun NewChatDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(TextFieldValue("")) }

    AppDialog(
        title = "New Chat",
        onDismiss = onDismiss,
        confirmText = "Create",
        onConfirm = { onConfirm(title.text) },
        dismissText = "Cancel",
        content = { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Chat title") }) }
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
    val state = workInfo?.state
    LaunchedEffect(state) {
        when (state) {
            WorkInfo.State.SUCCEEDED -> showSuccessToast("Downloaded ${model.name}")
            WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> showErrorToast("Download failed for ${model.name}")
            else -> Unit
        }
    }

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

@Composable
fun ModelManifestRow(
    manifest: ModelManifest,
    isActive: Boolean = false,
    isBusy: Boolean = false,
    onLoad: (() -> Unit)? = null,
    onVerify: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(manifest.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                File(manifest.filePath).name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${formatBytes(manifest.sizeBytes)} • ctx ${manifest.contextLength}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isActive) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("Active") }
                )
            }
            if (isBusy) {
                InlineLoadingIndicator(message = "Working…")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onLoad?.let {
                    TextButton(
                        onClick = it,
                        enabled = !isBusy && !isActive
                    ) { Text(if (isActive) "Loaded" else "Activate") }
                }
                onVerify?.let {
                    TextButton(
                        onClick = it,
                        enabled = !isBusy
                    ) { Text("Verify") }
                }
                onDelete?.let {
                    TextButton(
                        onClick = it,
                        enabled = !isBusy && !isActive
                    ) { Text("Delete") }
                }
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
