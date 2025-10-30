package com.peerchat.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.peerchat.app.ui.HomeEvent
import com.peerchat.app.ui.DialogState
import com.peerchat.app.ui.HomeViewModel
import com.peerchat.app.ui.components.AdaptiveHomeLayout
import com.peerchat.app.ui.components.HomeTopBar
import com.peerchat.app.ui.components.*

private const val ROUTE_HOME = "home"
private const val ROUTE_CHAT = "chat/{chatId}"
private const val ROUTE_DOCUMENTS = "documents"
private const val ROUTE_MODELS = "models"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_REASONING = "reasoning/{chatId}"

@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var tempName by remember { mutableStateOf(TextFieldValue("")) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is HomeEvent.Toast -> {
                    android.widget.Toast.makeText(
                        context,
                        event.message,
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

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
        AdaptiveHomeLayout(
            navController = navController,
            uiState = uiState,
            viewModel = viewModel,
            tempName = tempName,
            onTempNameChange = { tempName = it },
            modifier = Modifier.padding(innerPadding)
        )
    }

    // Unified Dialog Rendering
    when (val dialogState = uiState.dialogState) {
        is DialogState.None -> Unit // No dialog
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
