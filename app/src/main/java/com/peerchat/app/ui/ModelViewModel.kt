package com.peerchat.app.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.OperationResult
import com.peerchat.app.engine.ModelService
import com.peerchat.app.ui.components.GlobalToastManager
import com.peerchat.data.db.ModelManifest
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ModelUiState(
    val manifests: List<ModelManifest> = emptyList(),
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val activeManifest: ModelManifest? = null,
    val detectedTemplateId: String? = null
)

@HiltViewModel
class ModelViewModel @Inject constructor(
    private val modelService: ModelService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelUiState())
    val uiState: StateFlow<ModelUiState> = _uiState.asStateFlow()

    init {
        observeManifests()
    }

    private fun observeManifests() {
        viewModelScope.launch {
            modelService.getManifestsFlow().collectLatest { manifests ->
                val active = runCatching { modelService.getActiveManifest() }.getOrNull()
                _uiState.update { it.copy(manifests = manifests, activeManifest = active) }
            }
        }
    }

    fun importModel(uri: Uri) {
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            when (val result = modelService.importModel(uri)) {
                is OperationResult.Success -> {
                    GlobalToastManager.showToast("Model imported successfully")
                }
                is OperationResult.Failure -> {
                    GlobalToastManager.showToast("Import failed: ${result.error}", isError = true)
                }
            }
            _uiState.update { it.copy(isImporting = false) }
        }
    }

    fun activateManifest(manifest: ModelManifest) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            when (val result = modelService.activateManifest(manifest)) {
                is OperationResult.Success -> {
                    _uiState.update { it.copy(activeManifest = result.data) }
                    GlobalToastManager.showToast("Model loaded successfully")
                }
                is OperationResult.Failure -> {
                    GlobalToastManager.showToast("Failed to load model: ${result.error}", isError = true)
                }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            val message = modelService.unloadModel()
            _uiState.update { it.copy(activeManifest = null) }
            GlobalToastManager.showToast(message)
        }
    }

    fun deleteManifest(manifest: ModelManifest, removeFile: Boolean) {
        viewModelScope.launch {
            val message = modelService.deleteManifest(manifest, removeFile)
            GlobalToastManager.showToast(message)
        }
    }

    fun verifyManifest(manifest: ModelManifest) {
        viewModelScope.launch {
            val ok = runCatching { modelService.verifyManifest(manifest) }.getOrDefault(false)
            GlobalToastManager.showToast(if (ok) "Checksum verified" else "File missing", isError = !ok)
        }
    }
}
