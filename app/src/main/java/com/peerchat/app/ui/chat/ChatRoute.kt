package com.peerchat.app.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun ChatRoute(chatId: Long) {
    val vm: ChatViewModel = viewModel(factory = androidx.lifecycle.viewmodel.initializer {
        val app = androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner.current!!
        val androidApp = (app as androidx.lifecycle.ViewModelStoreOwner)
        @Suppress("UNCHECKED_CAST")
        ChatViewModel(
            application = (androidApp as android.app.Activity).application,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(mapOf("chatId" to chatId.toString()))
        )
    })
    val state by vm.uiState.collectAsState()

    // Convert ChatViewModel.ChatUiState to ChatScreen.ChatUiState
    val chatScreenState = ChatUiState(
        messages = state.messages,
        streaming = state.streaming,
        currentResponse = state.currentResponse,
        reasoning = state.reasoning,
        showReasoning = state.showReasoning,
        inReasoning = state.inReasoning,
        metrics = state.metrics
    )

    ChatScreen(state = chatScreenState, onSend = vm::sendPrompt, onToggleReasoning = vm::toggleReasoning)
}