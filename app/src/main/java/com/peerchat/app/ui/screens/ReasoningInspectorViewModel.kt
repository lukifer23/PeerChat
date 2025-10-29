package com.peerchat.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.data.db.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReasoningInspectorUiState(
    val messages: List<Message> = emptyList()
)

class ReasoningInspectorViewModel(
    application: Application,
    private val chatId: Long
) : AndroidViewModel(application) {
    private val repository = PeerChatRepository.from(application)

    private val _uiState = MutableStateFlow(ReasoningInspectorUiState())
    val uiState: StateFlow<ReasoningInspectorUiState> = _uiState.asStateFlow()

    init {
        loadMessages()
    }

    private fun loadMessages() {
        viewModelScope.launch {
            repository.database().messageDao().observeByChat(chatId).collectLatest { messages ->
                _uiState.update { it.copy(messages = messages) }
            }
        }
    }
}
