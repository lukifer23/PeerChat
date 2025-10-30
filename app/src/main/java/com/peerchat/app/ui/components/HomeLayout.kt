package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.peerchat.app.ui.HomeUiState
import com.peerchat.app.ui.HomeViewModel
import com.peerchat.app.ui.screens.ChatScreen

/**
 * Main home screen layout that adapts to screen size
 */
@Composable
fun AdaptiveHomeLayout(
    navController: NavHostController,
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    tempName: androidx.compose.ui.text.input.TextFieldValue,
    onTempNameChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isCompact = maxWidth < 720.dp

        if (isCompact) {
            CompactHomeLayout(
                navController = navController,
                uiState = uiState,
                viewModel = viewModel,
                tempName = tempName,
                onTempNameChange = onTempNameChange
            )
        } else {
            WideHomeLayout(
                navController = navController,
                uiState = uiState,
                viewModel = viewModel,
                tempName = tempName,
                onTempNameChange = onTempNameChange
            )
        }
    }
}

/**
 * Compact layout for small screens (stacked vertically)
 */
@Composable
private fun CompactHomeLayout(
    navController: NavHostController,
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    tempName: androidx.compose.ui.text.input.TextFieldValue,
    onTempNameChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Status and search (common to both layouts)
        HomeStatusSection(uiState = uiState, viewModel = viewModel)

        // Chat area takes up significant space
        ChatSection(
            uiState = uiState,
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp)
        )

        // Navigation sections below
        NavigationSections(
            uiState = uiState,
            viewModel = viewModel,
            tempName = tempName,
            onTempNameChange = onTempNameChange
        )
    }
}

/**
 * Wide layout for large screens (side-by-side)
 */
@Composable
private fun WideHomeLayout(
    navController: NavHostController,
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    tempName: androidx.compose.ui.text.input.TextFieldValue,
    onTempNameChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Status and search at top
        HomeStatusSection(uiState = uiState, viewModel = viewModel)

        // Main content area
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Navigation sidebar
            NavigationSections(
                uiState = uiState,
                viewModel = viewModel,
                tempName = tempName,
                onTempNameChange = onTempNameChange,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxHeight()
            )

            // Chat area takes up remaining space
            ChatSection(
                uiState = uiState,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

/**
 * Status row and search section
 */
@Composable
private fun HomeStatusSection(
    uiState: HomeUiState,
    viewModel: HomeViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
    }
}

/**
 * Chat display and input area
 */
@Composable
private fun ChatSection(
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier) {
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
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Select a chat or create a new one",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}

/**
 * Folders and chats navigation sections
 */
@Composable
private fun NavigationSections(
    uiState: HomeUiState,
    viewModel: HomeViewModel,
    tempName: androidx.compose.ui.text.input.TextFieldValue,
    onTempNameChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Folders section
        SectionCard(
            title = "Folders",
            actionLabel = "New",
            onAction = {
                onTempNameChange(androidx.compose.ui.text.input.TextFieldValue(""))
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

        // Chats section
        SectionCard(
            title = "Chats",
            actionLabel = "New",
            onAction = {
                onTempNameChange(androidx.compose.ui.text.input.TextFieldValue(""))
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
                                onTempNameChange(androidx.compose.ui.text.input.TextFieldValue(chat.title))
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
}
