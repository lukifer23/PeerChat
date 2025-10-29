@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.peerchat.app.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.WorkInfo
import com.peerchat.app.engine.DefaultModel
import com.peerchat.app.engine.DefaultModels
import com.peerchat.app.engine.ModelDownloadManager
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import dev.jeziellago.compose.markdowntext.MarkdownText
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

private const val ROUTE_HOME = "home"
private const val ROUTE_CHAT = "chat/{chatId}"

@Composable
fun PeerChatRoot() {
    com.peerchat.app.ui.theme.PeerChatTheme {
        val navController = rememberNavController()
        Surface(color = MaterialTheme.colorScheme.background) {
            NavHost(navController = navController, startDestination = ROUTE_HOME) {
                composable(ROUTE_HOME) {
                    HomeScreen(navController = navController)
                }
                composable(ROUTE_CHAT) { backStackEntry ->
                    val chatIdArg = backStackEntry.arguments?.getString("chatId")?.toLongOrNull()
                    if (chatIdArg != null) {
                        com.peerchat.app.ui.chat.ChatRoute(chatId = chatIdArg)
                    } else {
                        Text("Invalid chat")
                    }
                }
            }
        }
    }
}

@Composable
@Suppress("UNUSED_PARAMETER")
private fun HomeScreen(navController: NavHostController) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(application) as T
            }
        }
    )
    val uiState by viewModel.uiState.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    var showSettings by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    val showRenameDialog = remember { mutableStateOf(false) }
    val showMoveDialog = remember { mutableStateOf(false) }
    val showForkDialog = remember { mutableStateOf(false) }
    val renameTargetId = remember { mutableStateOf<Long?>(null) }
    val moveTargetId = remember { mutableStateOf<Long?>(null) }
    val forkTargetId = remember { mutableStateOf<Long?>(null) }
    var tempName by remember { mutableStateOf(TextFieldValue("")) }

    val documentImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importDocument(uri)
        }
    }
    val modelImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            viewModel.importModel(uri)
        }
    }

    val bodySpacing = 16.dp

    Scaffold(
        topBar = {
            HomeTopBar(
                docImportInProgress = uiState.indexing,
                modelImportInProgress = uiState.importingModel,
                onNewChat = {
                    tempName = TextFieldValue("")
                    showNewChat = true
                },
                onImportDoc = {
                    documentImportLauncher.launch(arrayOf("application/pdf", "text/*", "image/*"))
                },
                onImportModel = {
                    modelImportLauncher.launch(arrayOf("application/octet-stream", "model/gguf", "application/x-gguf", "*/*"))
                },
                onOpenModels = { navController.navigate(ROUTE_MODELS) },
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
                onOpenDocuments = { navController.navigate(ROUTE_DOCUMENTS) }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isCompact = maxWidth < 720.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(bodySpacing)
            ) {
                StatusRow(status = uiState.engineStatus, metrics = uiState.engineMetrics)
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::updateSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        singleLine = true,
                        placeholder = { Text("Search messages and docs…") }
                    )
                }
                if (uiState.searchResults.isNotEmpty()) {
                    SectionCard(title = "Search Results") {
                        uiState.searchResults.forEach { result ->
                            Text(result, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (isCompact) {
                    Column(verticalArrangement = Arrangement.spacedBy(bodySpacing)) {
                        SectionCard(
                            title = "Folders",
                            actionLabel = "New",
                            onAction = {
                                tempName = TextFieldValue("")
                                showNewFolder = true
                            }
                        ) {
                            if (uiState.folders.isEmpty()) {
                                EmptyListHint("No folders yet.")
                            } else {
                                uiState.folders.forEach { folder ->
                                    HomeListRow(
                                        title = folder.name,
                                        subtitle = if (uiState.selectedFolderId == folder.id) "Selected" else null,
                                        actions = listOf(
                                            "Open" to { viewModel.selectFolder(folder.id) }
                                        )
                                    )
                                }
                            }
                        }
                        SectionCard(
                            title = "Chats",
                            actionLabel = "New",
                            onAction = {
                                tempName = TextFieldValue("")
                                showNewChat = true
                            }
                        ) {
                            if (uiState.chats.isEmpty()) {
                                EmptyListHint("No chats yet.")
                            } else {
                                uiState.chats.forEach { chat ->
                                    HomeListRow(
                                        title = chat.title,
                                        actions = listOf(
                                            "Open" to { viewModel.selectChat(chat.id) },
                                            "Rename" to {
                                                tempName = TextFieldValue(chat.title)
                                                renameTargetId.value = chat.id
                                                showRenameDialog.value = true
                                            },
                                            "Move" to {
                                                moveTargetId.value = chat.id
                                                showMoveDialog.value = true
                                            },
                                            "Fork" to {
                                                forkTargetId.value = chat.id
                                                showForkDialog.value = true
                                            }
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(bodySpacing)
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 360.dp)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(bodySpacing)
                        ) {
                            SectionCard(
                                title = "Folders",
                                actionLabel = "New",
                                onAction = {
                                    tempName = TextFieldValue("")
                                    showNewFolder = true
                                }
                            ) {
                                if (uiState.folders.isEmpty()) {
                                    EmptyListHint("No folders yet.")
                                } else {
                                    uiState.folders.forEach { folder ->
                                        HomeListRow(
                                            title = folder.name,
                                            subtitle = if (uiState.selectedFolderId == folder.id) "Selected" else null,
                                            actions = listOf(
                                                "Open" to { viewModel.selectFolder(folder.id) }
                                            )
                                        )
                                    }
                                }
                            }
                            SectionCard(
                                title = "Chats",
                                actionLabel = "New",
                                onAction = {
                                    tempName = TextFieldValue("")
                                    showNewChat = true
                                }
                            ) {
                                if (uiState.chats.isEmpty()) {
                                    EmptyListHint("No chats yet.")
                                } else {
                                    uiState.chats.forEach { chat ->
                                        HomeListRow(
                                            title = chat.title,
                                            actions = listOf(
                                                "Open" to { viewModel.selectChat(chat.id) },
                                                "Rename" to {
                                                    tempName = TextFieldValue(chat.title)
                                                    renameTargetId.value = chat.id
                                                    showRenameDialog.value = true
                                                },
                                                "Move" to {
                                                    moveTargetId.value = chat.id
                                                    showMoveDialog.value = true
                                                },
                                                "Fork" to {
                                                    forkTargetId.value = chat.id
                                                    showForkDialog.value = true
                                                }
                                            )
                                        )
                                    }
                                }
                            }
                        }
                        ElevatedCard(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                        ) {
                            if (uiState.activeChatId != null) {
                                ChatScreen(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(16.dp),
                                    enabled = true,
                                    messages = uiState.messages,
                                    onSend = { prompt, onToken, onComplete ->
                                        viewModel.sendPrompt(prompt, onToken, onComplete)
                                    }
                                )
                            } else {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Select a chat or create a new one", style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            confirmButton = { TextButton(onClick = { showModels = false }) { Text("Close") } },
            title = { Text("Models") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DefaultModels.list.forEach { model ->
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
            }
        )
    }

    if (showRenameDialog.value && renameTargetId.value != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog.value = false },
            confirmButton = {
                TextButton(onClick = {
                    val id = renameTargetId.value ?: return@TextButton
                    viewModel.renameChat(id, tempName.text)
                    showRenameDialog.value = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog.value = false }) { Text("Cancel") } },
            title = { Text("Rename Chat") },
            text = { OutlinedTextField(value = tempName, onValueChange = { tempName = it }) }
        )
    }

    if (showMoveDialog.value && moveTargetId.value != null) {
        AlertDialog(
            onDismissRequest = { showMoveDialog.value = false },
            confirmButton = { TextButton(onClick = { showMoveDialog.value = false }) { Text("Close") } },
            title = { Text("Move Chat to Folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        val id = moveTargetId.value ?: return@TextButton
                        viewModel.moveChat(id, null)
                        showMoveDialog.value = false
                    }) { Text("No folder") }
                    LazyColumn(Modifier.heightIn(max = 240.dp)) {
                        items(uiState.folders) { folder ->
                            TextButton(onClick = {
                                val id = moveTargetId.value ?: return@TextButton
                                viewModel.moveChat(id, folder.id)
                                showMoveDialog.value = false
                            }) { Text(folder.name) }
                        }
                    }
                }
            }
        )
    }

    if (showForkDialog.value && forkTargetId.value != null) {
        AlertDialog(
            onDismissRequest = { showForkDialog.value = false },
            confirmButton = {
                TextButton(onClick = {
                    val id = forkTargetId.value ?: return@TextButton
                    viewModel.forkChat(id)
                    showForkDialog.value = false
                }) { Text("Fork") }
            },
            dismissButton = { TextButton(onClick = { showForkDialog.value = false }) { Text("Cancel") } },
            title = { Text("Fork Chat") },
            text = { Text("Create a duplicate conversation including existing messages?") }
        )
    }

    if (showNewFolder) {
        AlertDialog(
            onDismissRequest = { showNewFolder = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createFolder(tempName.text)
                    showNewFolder = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewFolder = false }) { Text("Cancel") } },
            title = { Text("New Folder") },
            text = { OutlinedTextField(value = tempName, onValueChange = { tempName = it }, placeholder = { Text("Folder name") }) }
        )
    }

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createChat(tempName.text)
                    showNewChat = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewChat = false }) { Text("Cancel") } },
            title = { Text("New Chat") },
            text = { OutlinedTextField(value = tempName, onValueChange = { tempName = it }, placeholder = { Text("Chat title") }) }
        )
    }

    if (showSettings) {
        SettingsDialog(
            state = uiState,
            onDismiss = { showSettings = false },
            onSysPromptChange = viewModel::updateSysPrompt,
            onTemperatureChange = viewModel::updateTemperature,
            onTopPChange = viewModel::updateTopP,
            onTopKChange = viewModel::updateTopK,
            onMaxTokensChange = viewModel::updateMaxTokens,
            onModelPathChange = viewModel::updateModelPath,
            onThreadChange = viewModel::updateThreadText,
            onContextChange = viewModel::updateContextText,
            onGpuChange = viewModel::updateGpuText,
            onUseVulkanChange = viewModel::updateUseVulkan,
            onLoadModel = viewModel::loadModelFromInputs,
            onUnloadModel = viewModel::unloadModel,
            onSelectManifest = viewModel::activateManifest,
            onDeleteManifest = viewModel::deleteManifest
        )
    }
}

@Composable
private fun HomeTopBar(
    docImportInProgress: Boolean,
    modelImportInProgress: Boolean,
    onNewChat: () -> Unit,
    onImportDoc: () -> Unit,
    onImportModel: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDocuments: () -> Unit,
) {
    TopAppBar(
        title = { Text("PeerChat") },
        actions = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
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

@Composable
private fun StatusRow(status: EngineRuntime.EngineStatus, metrics: EngineMetrics) {
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
private fun SectionCard(
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
private fun EmptyListHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ColumnScope.HomeListRow(
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
private fun rememberDownloadState(model: DefaultModel): WorkInfo.State? {
    val context = LocalContext.current
    val flow = remember(model.id) { ModelDownloadManager.observe(context, model) }
    val infos by flow.collectAsState(initial = emptyList())
    return infos.firstOrNull()?.state
}

@Composable
private fun ModelCatalogRow(
    model: DefaultModel,
    manifest: com.peerchat.data.db.ModelManifest?,
    workState: WorkInfo.State?,
    onDownload: () -> Unit,
    onActivate: (() -> Unit)?,
    onOpenCard: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

@Composable
private fun SettingsDialog(
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
    onSelectManifest: (com.peerchat.data.db.ModelManifest) -> Unit,
    onDeleteManifest: (com.peerchat.data.db.ModelManifest) -> Unit,
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

private fun statusLabel(status: EngineRuntime.EngineStatus): String = when (status) {
    EngineRuntime.EngineStatus.Uninitialized -> "Uninitialized"
    EngineRuntime.EngineStatus.Idle -> "Idle"
    is EngineRuntime.EngineStatus.Loading -> "Loading"
    is EngineRuntime.EngineStatus.Loaded -> "Loaded"
    is EngineRuntime.EngineStatus.Error -> "Error"
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
