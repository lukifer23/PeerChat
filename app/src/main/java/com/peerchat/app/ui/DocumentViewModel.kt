package com.peerchat.app.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.OperationResult
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.DocumentService
import com.peerchat.data.db.Document
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val repository: PeerChatRepository,
    private val documentService: DocumentService
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<DocumentEvent>()
    val events = _events.asSharedFlow()

    override fun emitToast(message: String, isError: Boolean) {
        _events.tryEmit(DocumentEvent.Toast(message, isError))
    }

    init {
        observeDocuments()
    }

    private fun observeDocuments() {
        viewModelScope.launch {
            repository.observeDocuments().collectLatest { docs ->
                _uiState.update { it.copy(documents = docs) }
            }
        }
    }

    fun importDocument(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(importInProgress = true) }
            val result = withContext(Dispatchers.IO) { documentService.importDocument(uri) }
            _uiState.update { it.copy(importInProgress = false) }
            when (result) {
                is OperationResult.Success -> emitToast(result.message, false)
                is OperationResult.Failure -> emitToast(result.error, true)
            }
        }
    }

    fun reindex(document: Document) {
        viewModelScope.launch {
            _uiState.update { it.copy(reindexingIds = it.reindexingIds + document.id) }
            val result = withContext(Dispatchers.IO) { documentService.reindexDocument(document) }
            _uiState.update { it.copy(reindexingIds = it.reindexingIds - document.id) }
            when (result) {
                is OperationResult.Success -> emitToast(result.message, false)
                is OperationResult.Failure -> emitToast(result.error, true)
            }
        }
    }

    fun delete(document: Document) {
        viewModelScope.launch {
            _uiState.update { it.copy(deletingIds = it.deletingIds + document.id) }
            val result = withContext(Dispatchers.IO) { documentService.deleteDocument(document.id) }
            _uiState.update { it.copy(deletingIds = it.deletingIds - document.id) }
            when (result) {
                is OperationResult.Success -> emitToast(result.message, false)
                is OperationResult.Failure -> emitToast(result.error, true)
            }
        }
    }

    data class DocumentsUiState(
        val documents: List<Document> = emptyList(),
        val reindexingIds: Set<Long> = emptySet(),
        val deletingIds: Set<Long> = emptySet(),
        val importInProgress: Boolean = false,
    )

    sealed class DocumentEvent {
        data class Toast(val message: String, val isError: Boolean = false) : DocumentEvent()
    }
}
