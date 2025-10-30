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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.peerchat.app.ui.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatRouteScreen(navController: NavHostController, chatId: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: HomeViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return HomeViewModel(application) as T
            }
        }
    )
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(chatId) {
        viewModel.selectChat(chatId)
    }

    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var showDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = uiState.chats.firstOrNull { it.id == chatId }?.title ?: "Chat"
                    Text(title)
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = {
                            menuOpen = false
                            val current = uiState.chats.firstOrNull { it.id == chatId }?.title.orEmpty()
                            renameText = current
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
                    onSend = { prompt, onToken, onComplete ->
                        viewModel.sendPrompt(prompt, onToken, onComplete)
                    }
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
                    viewModel.renameChat(chatId, renameText)
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
                    viewModel.deleteChat(chatId)
                    showDelete = false
                    navController.popBackStack()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
            title = { Text("Delete Chat") },
            text = { Text("This will delete the chat and its messages.") }
        )
    }
}
