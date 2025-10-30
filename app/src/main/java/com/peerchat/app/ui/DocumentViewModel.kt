package com.peerchat.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.OperationResult
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.docs.OcrService
import com.peerchat.app.engine.DocumentService
import com.peerchat.app.ui.components.GlobalToastManager
import com.peerchat.data.db.Document
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentUiState(
    val documents: List<Document> = emptyList(),
    val isIndexing: Boolean = false,
    val isProcessingOcr: Boolean = false
)

@HiltViewModel
class DocumentViewModel @Inject constructor(
    private val repository: PeerChatRepository,
    private val documentService: DocumentService,
    private val ocrService: OcrService
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentUiState())
    val uiState: StateFlow<DocumentUiState> = _uiState.asStateFlow()

    init {
        observeDocuments()
    }

    private fun observeDocuments() {
        viewModelScope.launch {
            repository.observeDocuments().collectLatest { documents ->
                _uiState.update { it.copy(documents = documents) }
            }
        }
    }

    fun importDocument(uri: Uri) {
        _uiState.update { it.copy(isIndexing = true) }
        viewModelScope.launch {
            try {
                when (val result = documentService.importDocument(uri)) {
                    is OperationResult.Success -> {
                        GlobalToastManager.showToast("Document imported and indexed")
                    }
                    is OperationResult.Failure -> {
                        GlobalToastManager.showToast("Import failed: ${result.error}", isError = true)
                    }
                }
            } catch (e: Exception) {
                GlobalToastManager.showToast("Import error: ${e.message}", isError = true)
            } finally {
                _uiState.update { it.copy(isIndexing = false) }
            }
        }
    }

    fun reindexDocument(document: Document) {
        _uiState.update { it.copy(isIndexing = true) }
        viewModelScope.launch {
            try {
                when (val result = documentService.reindexDocument(document)) {
                    is OperationResult.Success -> {
                        GlobalToastManager.showToast("Document re-indexed")
                    }
                    is OperationResult.Failure -> {
                        GlobalToastManager.showToast("Re-index failed: ${result.error}", isError = true)
                    }
                }
            } catch (e: Exception) {
                GlobalToastManager.showToast("Re-index error: ${e.message}", isError = true)
            } finally {
                _uiState.update { it.copy(isIndexing = false) }
            }
        }
    }

    fun processImageForOcr(uri: Uri) {
        _uiState.update { it.copy(isProcessingOcr = true) }
        viewModelScope.launch {
            try {
                // Note: This is a simplified version. In a real implementation,
                // we'd need to create a proper DocumentService method for OCR
                GlobalToastManager.showToast("OCR processing not yet implemented", isError = true)
            } catch (e: Exception) {
                GlobalToastManager.showToast("OCR error: ${e.message}", isError = true)
            } finally {
                _uiState.update { it.copy(isProcessingOcr = false) }
            }
        }
    }

    fun deleteDocument(document: Document) {
        viewModelScope.launch {
            try {
                repository.deleteDocument(document.id)
                GlobalToastManager.showToast("Document deleted")
            } catch (e: Exception) {
                GlobalToastManager.showToast("Delete error: ${e.message}", isError = true)
            }
        }
    }
}
