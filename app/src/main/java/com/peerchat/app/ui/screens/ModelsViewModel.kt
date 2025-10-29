package com.peerchat.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.ModelConfigStore
import com.peerchat.app.engine.ModelManifestService
import com.peerchat.data.db.ModelManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelsUiState(
    val manifests: List<ModelManifest> = emptyList(),
    val activeModelPath: String? = null
)

class ModelsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PeerChatRepository.from(application)
    private val manifestService = ModelManifestService(application)

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    init {
        loadManifests()
        loadActiveModel()
    }

    private fun loadManifests() {
        viewModelScope.launch {
            repository.getManifests().collectLatest { manifests ->
                _uiState.update { it.copy(manifests = manifests) }
            }
        }
    }

    private fun loadActiveModel() {
        val config = ModelConfigStore.load(getApplication())
        _uiState.update { it.copy(activeModelPath = config?.modelPath) }
    }

    fun activateManifest(manifest: ModelManifest) {
        val config = com.peerchat.app.engine.StoredEngineConfig(
            modelPath = manifest.filePath,
            threads = 6,
            contextLength = manifest.contextLength,
            gpuLayers = 20,
            useVulkan = true
        )
        ModelConfigStore.save(getApplication(), config)
        _uiState.update { it.copy(activeModelPath = manifest.filePath) }
    }

    fun deleteManifest(manifest: ModelManifest) {
        viewModelScope.launch {
            manifestService.deleteManifest(manifest, removeFile = true)
            if (_uiState.value.activeModelPath == manifest.filePath) {
                ModelConfigStore.clear(getApplication())
                _uiState.update { it.copy(activeModelPath = null) }
            }
            loadManifests() // Refresh the list
        }
    }
}
