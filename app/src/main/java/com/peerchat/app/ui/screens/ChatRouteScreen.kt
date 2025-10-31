package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.peerchat.app.ui.ChatViewModel
import com.peerchat.app.ui.HomeViewModel
import com.peerchat.app.ui.components.SettingsDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRouteScreen(navController: NavHostController, chatId: Long) {
    val chatViewModel: ChatViewModel = hiltViewModel()
    val homeViewModel: HomeViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    val uiState by chatViewModel.uiState.collectAsState()
    val homeUiState by homeViewModel.uiState.collectAsState()

    LaunchedEffect(chatId) {
        chatViewModel.selectChat(chatId)
    }

    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(uiState.chatTitle.ifEmpty { "Chat" })
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {
                            menuOpen = false
                            renameText = uiState.chatTitle
                            showRename = true
                        })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = {
                            menuOpen = false
                            showDelete = true
                        })
                    }
                }
            )
        }
    ) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (uiState.activeChatId == chatId) {
                ChatScreen(
                    modifier = Modifier.weight(1f),
                    enabled = true,
                    messages = uiState.messages,
                    streaming = uiState.streaming,
                    onSend = chatViewModel::sendPrompt
                )
            } else {
                Text("Loading chat...")
            }
        }
    }

    if (showRename) {
        AlertDialog(
            onDismissRequest = { showRename = false },
            confirmButton = {
                TextButton(onClick = {
                    chatViewModel.renameChat(chatId, renameText)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } },
            title = { Text("Rename Chat") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = androidx.compose.ui.text.input.TextFieldValue(renameText),
                    onValueChange = { renameText = it.text },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        )
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        chatViewModel.deleteChat(chatId)
                        showDelete = false
                        navController.popBackStack()
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
            title = { Text("Delete Chat") },
            text = { Text("This will delete the chat and its messages.") }
        )
    }

    if (showSettings) {
        SettingsDialog(
            homeState = homeUiState,
            chatState = uiState,
            onDismiss = { showSettings = false },
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
