package com.peerchat.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.peerchat.app.ui.HomeUiState
import com.peerchat.app.ui.HomeViewModel
import com.peerchat.app.ui.SearchResultItem
import com.peerchat.app.ui.SearchResultItem.ResultType
import com.peerchat.app.ui.theme.LocalSpacing

/**
 * Home layout focuses on navigation (folders & chats) with adaptive column/row presentation.
 */
@Composable
fun AdaptiveHomeLayout(
    navController: NavHostController,
    uiState: HomeUiState,
    homeViewModel: HomeViewModel,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isCompact = maxWidth < 720.dp
        val spacing = LocalSpacing.current

        if (isCompact) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.large, vertical = spacing.medium)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.large)
            ) {
                HomeStatusSection(
                    navController = navController,
                    uiState = uiState,
                    homeViewModel = homeViewModel
                )
                FoldersSection(
                    uiState = uiState,
                    homeViewModel = homeViewModel
                )
                ChatsSection(
                    uiState = uiState,
                    homeViewModel = homeViewModel,
                    onOpenChat = { chatId, folderId ->
                        homeViewModel.focusChat(chatId, folderId)
                        navController.navigate("chat/$chatId") {
                            launchSingleTop = true
                        }
                    }
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.large, vertical = spacing.medium),
                verticalArrangement = Arrangement.spacedBy(spacing.large)
            ) {
                HomeStatusSection(
                    navController = navController,
                    uiState = uiState,
                    homeViewModel = homeViewModel
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing.large)
                ) {
                    FoldersSection(
                        uiState = uiState,
                        homeViewModel = homeViewModel,
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(max = 360.dp)
                    )
                    ChatsSection(
                        uiState = uiState,
                        homeViewModel = homeViewModel,
                        modifier = Modifier.weight(2f),
                        onOpenChat = { chatId, folderId ->
                            homeViewModel.focusChat(chatId, folderId)
                            navController.navigate("chat/$chatId") {
                                launchSingleTop = true
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeStatusSection(
    navController: NavHostController,
    uiState: HomeUiState,
    homeViewModel: HomeViewModel
) {
    val spacing = LocalSpacing.current
    Column(verticalArrangement = Arrangement.spacedBy(spacing.large)) {
        StatusRow(
            status = uiState.model.engineStatus,
            metrics = uiState.model.engineMetrics
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = uiState.search.searchQuery,
                onValueChange = homeViewModel::updateSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.medium, vertical = spacing.small),
                singleLine = true,
                placeholder = { Text("Search messages and docs…") }
            )
        }

        if (uiState.search.searchResults.isNotEmpty()) {
            SectionCard(title = "Search Results") {
                uiState.search.searchResults.forEach { result ->
                    HomeSearchResultRow(
                        result = result,
                        onClick = {
                            when (result.type) {
                                ResultType.MESSAGE -> {
                                    val chatId = result.chatId
                                    if (chatId != null) {
                                        homeViewModel.focusChat(chatId)
                                        navController.navigate("chat/$chatId") {
                                            launchSingleTop = true
                                        }
                                    }
                                }

                                ResultType.DOCUMENT_CHUNK -> {
                                    navController.navigate("documents")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSearchResultRow(
    result: SearchResultItem,
    onClick: () -> Unit
) {
    val spacing = LocalSpacing.current
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing.tiny)
        ) {
            Text(
                result.title.ifBlank { result.type.name.lowercase().replaceFirstChar { it.uppercase() } },
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                result.preview.take(160) + if (result.preview.length > 160) "…" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FoldersSection(
    uiState: HomeUiState,
    homeViewModel: HomeViewModel,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionCard(
            title = "Folders",
            actionLabel = "New",
            onAction = { homeViewModel.showNewFolderDialog() },
            content = {
                HomeListRow(
                    title = "All Chats",
                    subtitle = if (uiState.navigation.selectedFolderId == null) "Selected" else null,
                    actions = listOf(
                        "Open" to { homeViewModel.selectFolder(null) }
                    )
                )
                if (uiState.navigation.folders.isEmpty()) {
                    EmptyListHint("No folders yet. Create one to organize chats.")
                }
                uiState.navigation.folders.forEach { folder ->
                    HomeListRow(
                        title = folder.name,
                        subtitle = if (uiState.navigation.selectedFolderId == folder.id) "Selected" else null,
                        actions = listOf(
                            "Open" to { homeViewModel.selectFolder(folder.id) },
                            "Rename" to { homeViewModel.showRenameFolderDialog(folder.id, folder.name) },
                            "Delete" to { homeViewModel.showDeleteFolderDialog(folder.id, folder.name) }
                        )
                    )
                }
            }
        )
    }
}

@Composable
private fun ChatsSection(
    uiState: HomeUiState,
    homeViewModel: HomeViewModel,
    onOpenChat: (Long, Long?) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        SectionCard(
            title = "Chats",
            actionLabel = "New",
            onAction = { homeViewModel.showNewChatDialog() },
            content = {
                val chats = uiState.navigation.chats
                if (chats.isEmpty()) {
                    val message = if (uiState.navigation.selectedFolderId == null) {
                        "No chats yet. Start a new conversation to begin."
                    } else {
                        "No chats in this folder yet."
                    }
                    EmptyListHint(message)
                } else {
                    chats.forEach { chat ->
                        HomeListRow(
                            title = chat.title.ifBlank { "Untitled chat" },
                            subtitle = if (uiState.navigation.activeChatId == chat.id) "Active" else null,
                            actions = listOf(
                                "Open" to { onOpenChat(chat.id, chat.folderId) },
                                "Rename" to { homeViewModel.showRenameChatDialog(chat.id, chat.title) },
                                "Move" to { homeViewModel.showMoveChatDialog(chat.id) },
                                "Fork" to { homeViewModel.showForkChatDialog(chat.id) },
                                "Delete" to { homeViewModel.showDeleteChatDialog(chat.id, chat.title) }
                            )
                        )
                    }
                }
            }
        )
    }
}
