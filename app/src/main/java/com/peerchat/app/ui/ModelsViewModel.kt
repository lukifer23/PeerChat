package com.peerchat.app.ui

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.peerchat.app.engine.ModelRepository
import com.peerchat.app.engine.ModelService
import com.peerchat.app.ui.ModelsViewModel.ModelsEvent.Toast
import com.peerchat.data.db.ModelManifest
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
class ModelsViewModel @Inject constructor(
    private val modelService: ModelService,
    private val modelRepository: ModelRepository,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ModelsEvent>()
    val events = _events.asSharedFlow()

    override fun emitToast(message: String, isError: Boolean) {
        _events.tryEmit(Toast(message, isError))
    }

    init {
        observeManifests()
        refreshActiveManifest()
    }

    private fun observeManifests() {
        viewModelScope.launch {
            modelService.getManifestsFlow().collectLatest { manifests ->
                _uiState.update { it.copy(manifests = manifests) }
                refreshActiveManifest()
            }
        }
    }

    private fun refreshActiveManifest() {
        viewModelScope.launch {
            val active = withContext(Dispatchers.IO) {
                modelRepository.getActiveManifest()
            }
            _uiState.update { it.copy(activeManifestId = active?.id) }
        }
    }

    fun activate(manifest: ModelManifest) {
        viewModelScope.launch {
            _uiState.update { it.copy(activatingId = manifest.id) }
            when (val result = modelRepository.activateManifest(manifest)) {
                is com.peerchat.app.data.OperationResult.Success -> {
                    val loaded = result.data
                    emitToast("Loaded ${loaded.name}", false)
                    refreshActiveManifest()
                }
                is com.peerchat.app.data.OperationResult.Failure -> emitToast(result.error, true)
            }
            _uiState.update { it.copy(activatingId = null) }
        }
    }

    fun verify(manifest: ModelManifest) {
        viewModelScope.launch {
            _uiState.update { it.copy(verifyingIds = it.verifyingIds + manifest.id) }
            val verified = runCatching { modelRepository.verifyManifest(manifest) }
            verified.onSuccess { success ->
                if (success) {
                    emitToast("Checksum verified for ${manifest.name}", false)
                } else {
                    emitToast("Verification failed for ${manifest.name}", true)
                }
            }.onFailure {
                emitToast("Verification error for ${manifest.name}: ${it.message}", true)
            }
            if (verified.isSuccess && verified.getOrNull() == true) {
                refreshActiveManifest()
            }
            _uiState.update { it.copy(verifyingIds = it.verifyingIds - manifest.id) }
        }
    }

    fun delete(manifest: ModelManifest, removeFile: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(deletingIds = it.deletingIds + manifest.id) }
            val message = runCatching {
                modelRepository.deleteManifest(manifest, removeFile)
            }.onFailure {
                emitToast("Delete failed: ${it.message}", true)
            }.getOrNull()
            if (!message.isNullOrBlank()) emitToast("${manifest.name}: $message", false)
            _uiState.update { it.copy(deletingIds = it.deletingIds - manifest.id) }
            refreshActiveManifest()
        }
    }

    fun import(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            _uiState.update { it.copy(importInProgress = true) }
            val result = modelRepository.importModel(uri)
            _uiState.update { it.copy(importInProgress = false) }
            when (result) {
                is com.peerchat.app.data.OperationResult.Success -> {
                    emitToast("Imported ${result.data.name}", false)
                    refreshActiveManifest()
                }
                is com.peerchat.app.data.OperationResult.Failure -> emitToast(result.error, true)
            }
        }
    }

    data class ModelsUiState(
        val manifests: List<ModelManifest> = emptyList(),
        val activeManifestId: Long? = null,
        val activatingId: Long? = null,
        val verifyingIds: Set<Long> = emptySet(),
        val deletingIds: Set<Long> = emptySet(),
        val importInProgress: Boolean = false,
    )

    sealed class ModelsEvent {
        data class Toast(val message: String, val isError: Boolean = false) : ModelsEvent()
    }
}
