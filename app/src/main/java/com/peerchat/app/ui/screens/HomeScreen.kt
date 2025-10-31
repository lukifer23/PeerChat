package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import com.peerchat.app.ui.ChatEvent
import com.peerchat.app.ui.ChatViewModel
import com.peerchat.app.ui.HomeEvent
import com.peerchat.app.ui.DialogState
import com.peerchat.app.ui.HomeViewModel
import com.peerchat.app.ui.components.AdaptiveHomeLayout
import com.peerchat.app.ui.components.GlobalToastManager
import com.peerchat.app.ui.components.HomeTopBar
import com.peerchat.app.ui.components.*
import com.peerchat.app.ui.theme.LocalSpacing
import kotlinx.coroutines.launch

private const val ROUTE_DOCUMENTS = "documents"
private const val ROUTE_MODELS = "models"

@Composable
fun HomeScreen(
    navController: NavHostController,
    homeViewModel: HomeViewModel = hiltViewModel(),
    chatViewModel: ChatViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val uiState by homeViewModel.uiState.collectAsState()
    val chatUiState by chatViewModel.uiState.collectAsState()

    // Optimize dialog state computation
    val dialogState by remember(uiState.dialogState) {
        derivedStateOf { uiState.dialogState }
    }

    // Combine event flows into single LaunchedEffect for efficiency
    LaunchedEffect(homeViewModel, chatViewModel) {
        kotlinx.coroutines.coroutineScope {
            launch {
                homeViewModel.events.collect { event ->
                    when (event) {
                        is HomeEvent.Toast -> {
                            GlobalToastManager.showToast(event.message, event.isError)
                        }
                        is HomeEvent.SelectChat -> {
                            chatViewModel.selectChat(event.chatId)
                        }
                    }
                }
            }
            launch {
                chatViewModel.events.collect { event ->
                    when (event) {
                        is ChatEvent.Toast -> {
                            GlobalToastManager.showToast(event.message, event.isError)
                        }
                        is ChatEvent.ChatCreated -> {
                            homeViewModel.focusChat(event.chatId, event.folderId)
                            navController.navigate("chat/${event.chatId}") {
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }
        }
    }


    val spacing = LocalSpacing.current

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            HomeTopBar(
                onNewChat = {
                    homeViewModel.showNewChatDialog()
                },
                onOpenModels = { navController.navigate(ROUTE_MODELS) },
                onOpenSettings = { homeViewModel.showSettingsDialog() },
                onOpenDocuments = { navController.navigate(ROUTE_DOCUMENTS) }
            )
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = { homeViewModel.showNewChatDialog() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.semantics { contentDescription = "Create new chat" }
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
            }
        }
    ) { innerPadding ->
        AdaptiveHomeLayout(
            navController = navController,
            uiState = uiState,
            homeViewModel = homeViewModel,
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = spacing.large, vertical = spacing.medium)
        )
    }

    // Unified Dialog Rendering
    when (dialogState) {
        is DialogState.None -> Unit // No dialog
        is DialogState.RenameChat -> {
            val state = dialogState as DialogState.RenameChat
            RenameChatDialog(
                currentTitle = state.currentTitle,
                onConfirm = { title ->
                    chatViewModel.renameChat(state.chatId, title)
                    homeViewModel.dismissDialog()
                },
                onDismiss = homeViewModel::dismissDialog
            )
        }
        is DialogState.MoveChat -> {
            val state = dialogState as DialogState.MoveChat
            MoveChatDialog(
                folders = uiState.navigation.folders,
                onMoveToFolder = { folderId ->
                    chatViewModel.moveChat(state.chatId, folderId)
                    homeViewModel.dismissDialog()
                },
                onDismiss = homeViewModel::dismissDialog
            )
        }
        is DialogState.ForkChat -> {
            val state = dialogState as DialogState.ForkChat
            ForkChatDialog(
                onConfirm = {
                    // Fork is async, handle in coroutine
                    scope.launch {
                        chatViewModel.forkChat(state.chatId)
                        homeViewModel.dismissDialog()
                    }
                },
                onDismiss = homeViewModel::dismissDialog
            )
        }
        is DialogState.NewFolder -> {
            NewFolderDialog(
                onConfirm = { name ->
                    homeViewModel.createFolder(name)
                    homeViewModel.dismissDialog()
                },
                onDismiss = homeViewModel::dismissDialog
            )
        }
        is DialogState.RenameFolder -> {
            val state = dialogState as DialogState.RenameFolder
            RenameFolderDialog(
                currentName = state.currentName,
                onConfirm = { name ->
                    homeViewModel.renameFolder(state.folderId, name)
                    homeViewModel.dismissDialog()
                },
                onDismiss = homeViewModel::dismissDialog
            )
        }
        is DialogState.DeleteFolder -> {
            val state = dialogState as DialogState.DeleteFolder
            DeleteFolderDialog(
                folderName = state.folderName,
                onConfirm = {
                    homeViewModel.deleteFolder(state.folderId)
                    homeViewModel.dismissDialog()
                },
                onDismiss = homeViewModel::dismissDialog
            )
        }
        is DialogState.NewChat -> {
            NewChatDialog(
                onConfirm = { title ->
                    val selectedFolderId = uiState.navigation.selectedFolderId
                    val defaultPrompt = chatUiState.sysPrompt
                    val modelId = uiState.model.storedConfig?.modelPath ?: "default"
                    chatViewModel.createChat(title, selectedFolderId, defaultPrompt, modelId)
                    homeViewModel.dismissDialog()
                },
                onDismiss = homeViewModel::dismissDialog
            )
        }
        is DialogState.DeleteChat -> {
            val state = dialogState as DialogState.DeleteChat
            DeleteChatDialog(
                chatTitle = state.chatTitle,
                onConfirm = {
                    chatViewModel.deleteChat(state.chatId)
                    homeViewModel.dismissDialog()
                },
                onDismiss = homeViewModel::dismissDialog
            )
        }
        is DialogState.Settings -> {
            SettingsDialog(
                homeState = uiState,
                chatState = chatUiState,
                onDismiss = homeViewModel::dismissDialog,
                onSysPromptChange = chatViewModel::updateSysPrompt,
                onTemperatureChange = chatViewModel::updateTemperature,
                onTopPChange = chatViewModel::updateTopP,
                onTopKChange = chatViewModel::updateTopK,
                onMaxTokensChange = chatViewModel::updateMaxTokens,
                onModelPathChange = homeViewModel::updateModelPath,
                onThreadChange = homeViewModel::updateThreadText,
                onContextChange = homeViewModel::updateContextText,
                onGpuChange = homeViewModel::updateGpuText,
                onUseVulkanChange = homeViewModel::updateUseVulkan,
                onLoadModel = homeViewModel::loadModel,
                onUnloadModel = homeViewModel::unloadModel,
                onSelectManifest = homeViewModel::activateManifest,
                onDeleteManifest = homeViewModel::deleteManifest,
                onTemplateSelect = chatViewModel::updateTemplate
            )
        }
    }
}
