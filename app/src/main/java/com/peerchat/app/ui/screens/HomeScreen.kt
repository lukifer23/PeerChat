package com.peerchat.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.peerchat.app.ui.DialogState
import com.peerchat.app.ui.HomeEvent
import com.peerchat.app.ui.HomeUiState
import com.peerchat.app.ui.HomeViewModel
import com.peerchat.app.ui.components.*
import com.peerchat.app.ui.components.EmptyListHint
import com.peerchat.app.ui.components.HomeListRow
import com.peerchat.app.ui.components.HomeTopBar
import com.peerchat.app.ui.components.SectionCard
import com.peerchat.app.ui.components.StatusRow

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
                return HomeViewModel(android.app.Application()) as T
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
            }
        }
    }

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

    Scaffold(
        topBar = {
            HomeTopBar(
                docImportInProgress = uiState.indexing,
                modelImportInProgress = uiState.importingModel,
                onNewChat = {
                    tempName = TextFieldValue("")
                    viewModel.showNewChatDialog()
                },
                onImportDoc = {
                    documentImportLauncher.launch(arrayOf("application/pdf", "text/*", "image/*"))
                },
                onImportModel = {
                    modelImportLauncher.launch(arrayOf("application/octet-stream", "model/gguf", "application/x-gguf", "*/*"))
                },
                onOpenModels = { navController.navigate(ROUTE_MODELS) },
                onOpenSettings = { viewModel.showSettingsDialog() },
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
                            Text(result, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (isCompact) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 260.dp)
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
                        SectionCard(
                            title = "Folders",
                            actionLabel = "New",
                            onAction = {
                                tempName = TextFieldValue("")
                                viewModel.showNewFolderDialog()
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
                                viewModel.showNewChatDialog()
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
                                                viewModel.showRenameChatDialog(chat.id, chat.title)
                                            },
                                            "Move" to {
                                                viewModel.showMoveChatDialog(chat.id)
                                            },
                                            "Fork" to {
                                                viewModel.showForkChatDialog(chat.id)
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
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 360.dp)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            SectionCard(
                                title = "Folders",
                                actionLabel = "New",
                            onAction = {
                                tempName = TextFieldValue("")
                                viewModel.showNewFolderDialog()
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
                                    viewModel.showNewChatDialog()
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
                                                    viewModel.showRenameChatDialog(chat.id, chat.title)
                                                },
                                                "Move" to {
                                                    viewModel.showMoveChatDialog(chat.id)
                                                },
                                                "Fork" to {
                                                    viewModel.showForkChatDialog(chat.id)
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

    // Unified Dialog Rendering
    when (val dialogState = uiState.dialogState) {
        is DialogState.None -> Unit // No dialog
        is DialogState.Models -> {
            ModelsDialog(
                manifests = uiState.manifests,
                onDismiss = viewModel::dismissDialog
            )
        }
        is DialogState.RenameChat -> {
            RenameChatDialog(
                currentTitle = dialogState.currentTitle,
                onConfirm = { title ->
                    viewModel.renameChat(dialogState.chatId, title)
                    viewModel.dismissDialog()
                },
                onDismiss = viewModel::dismissDialog
            )
        }
        is DialogState.MoveChat -> {
            MoveChatDialog(
                folders = uiState.folders,
                onMoveToFolder = { folderId ->
                    viewModel.moveChat(dialogState.chatId, folderId)
                    viewModel.dismissDialog()
                },
                onDismiss = viewModel::dismissDialog
            )
        }
        is DialogState.ForkChat -> {
            ForkChatDialog(
                onConfirm = {
                    viewModel.forkChat(dialogState.chatId)
                    viewModel.dismissDialog()
                },
                onDismiss = viewModel::dismissDialog
            )
        }
        is DialogState.NewFolder -> {
            NewFolderDialog(
                onConfirm = { name ->
                    viewModel.createFolder(name)
                    viewModel.dismissDialog()
                },
                onDismiss = viewModel::dismissDialog
            )
        }
        is DialogState.NewChat -> {
            NewChatDialog(
                onConfirm = { title ->
                    viewModel.createChat(title)
                    viewModel.dismissDialog()
                },
                onDismiss = viewModel::dismissDialog
            )
        }
        is DialogState.Settings -> {
            SettingsDialog(
                state = uiState,
                onDismiss = viewModel::dismissDialog,
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
}
