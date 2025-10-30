package com.peerchat.app.ui.screens

import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.platform.LocalConfiguration
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
import com.peerchat.app.ui.HomeEvent
import com.peerchat.app.ui.HomeUiState
import com.peerchat.app.ui.HomeViewModel
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import com.peerchat.app.ui.components.StatusRow
import com.peerchat.app.ui.components.SectionCard
import com.peerchat.app.ui.components.HomeListRow
import com.peerchat.app.ui.components.EmptyListHint
import com.peerchat.app.ui.components.HomeTopBar
import com.peerchat.app.ui.components.SettingsDialog
import com.peerchat.app.ui.components.ModelCatalogRow
import com.peerchat.app.ui.components.rememberDownloadInfo
import com.peerchat.app.ui.components.openUrl
import dev.jeziellago.compose.markdowntext.MarkdownText
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

private const val ROUTE_HOME = "home"
private const val ROUTE_CHAT = "chat/{chatId}"
private const val ROUTE_DOCUMENTS = "documents"
private const val ROUTE_MODELS = "models"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_REASONING = "reasoning/{chatId}"

@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as android.app.Application
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(app) as T
            }
        }
    )
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    androidx.compose.runtime.LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.Toast -> android.widget.Toast.makeText(context, event.message, android.widget.Toast.LENGTH_SHORT).show()
                is HomeEvent.OpenChat -> navController.navigate("chat/${event.chatId}")
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
    var showDeleteChatId by remember { mutableStateOf<Long?>(null) }
    var showDeleteFolderId by remember { mutableStateOf<Long?>(null) }

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

    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp
    val isTopBarCompact = screenWidthDp < 720

    Scaffold(
        topBar = {
            HomeTopBar(
                docImportInProgress = uiState.indexing,
                modelImportInProgress = uiState.importingModel,
                compact = isTopBarCompact,
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
                onOpenSettings = { showSettings = true },
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
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                            val label = result
                            androidx.compose.material3.TextButton(onClick = {
                                if (label.startsWith("Msg:#")) {
                                    val idPart = label.substringAfter("Msg:#").substringBefore(":").toLongOrNull()
                                    val target = idPart ?: uiState.activeChatId
                                    target?.let { navController.navigate("chat/${it}") }
                                } else {
                                    navController.navigate(ROUTE_DOCUMENTS)
                                }
                            }) {
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (isCompact) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text("Open a chat to begin", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
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
                                            "Open" to { viewModel.selectFolder(folder.id) },
                                            "Delete" to { showDeleteFolderId = folder.id }
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
                                            "Open" to { navController.navigate("chat/${chat.id}") },
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
                                            },
                                            "Delete" to { showDeleteChatId = chat.id }
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Unified to single-column layout; chat opens in dedicated screen
                }
            }
        }
    }

    // Dialogs
    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            confirmButton = { TextButton(onClick = { showModels = false }) { Text("Close") } },
            title = { Text("Models") },
            text = {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DefaultModels.list.forEach { model ->
                        val workInfo = rememberDownloadInfo(model)
                        val manifest = uiState.manifests.firstOrNull { File(it.filePath).name.equals(model.suggestedFileName, ignoreCase = true) }
                        ModelCatalogRow(
                            model = model,
                            manifest = manifest,
                            workInfo = workInfo,
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
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 240.dp)) {
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

    showDeleteChatId?.let { id ->
        AlertDialog(
            onDismissRequest = { showDeleteChatId = null },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteChat(id)
                    showDeleteChatId = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteChatId = null }) { Text("Cancel") } },
            title = { Text("Delete Chat") },
            text = { Text("This will delete the chat and its messages.") }
        )
    }

    showDeleteFolderId?.let { id ->
        AlertDialog(
            onDismissRequest = { showDeleteFolderId = null },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteFolder(id)
                    showDeleteFolderId = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteFolderId = null }) { Text("Cancel") } },
            title = { Text("Delete Folder") },
            text = { Text("Chats will be kept and unassigned from the folder.") }
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
            onDeleteManifest = viewModel::deleteManifest,
            onTemplateSelect = viewModel::updateTemplate
        )
    }
}
