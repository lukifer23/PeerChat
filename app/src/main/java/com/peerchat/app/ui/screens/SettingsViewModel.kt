package com.peerchat.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.engine.ModelConfigStore
import com.peerchat.app.engine.ModelManifestService
import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

data class SettingsUiState(
    val manifests: List<ModelManifest> = emptyList(),
    val storedConfig: StoredEngineConfig? = null,
    val modelPath: String = "",
    val threadText: String = "6",
    val contextText: String = "4096",
    val gpuText: String = "20",
    val useVulkan: Boolean = true,
    val sysPrompt: String = "",
    val temperature: Float = 0.8f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val maxTokens: Int = 512,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PeerChatRepository.from(application)
    private val manifestService = ModelManifestService(application)

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadManifests()
        loadModelConfig()
    }

    private fun loadManifests() {
        viewModelScope.launch {
            manifestService.manifestsFlow().collectLatest { manifests ->
                _uiState.update { it.copy(manifests = manifests) }
            }
        }
    }

    private fun loadModelConfig() {
        val config = ModelConfigStore.load(getApplication())
        if (config != null) {
            _uiState.update {
                it.copy(
                    storedConfig = config,
                    modelPath = config.modelPath,
                    threadText = config.threads.toString(),
                    contextText = config.contextLength.toString(),
                    gpuText = config.gpuLayers.toString(),
                    useVulkan = config.useVulkan
                )
            }
        }
    }

    fun updateModelPath(path: String) {
        _uiState.update { it.copy(modelPath = path) }
    }

    fun updateThreadText(value: String) {
        _uiState.update { it.copy(threadText = value) }
    }

    fun updateContextText(value: String) {
        _uiState.update { it.copy(contextText = value) }
    }

    fun updateGpuText(value: String) {
        _uiState.update { it.copy(gpuText = value) }
    }

    fun updateUseVulkan(value: Boolean) {
        _uiState.update { it.copy(useVulkan = value) }
    }

    fun updateSysPrompt(value: String) {
        _uiState.update { it.copy(sysPrompt = value) }
    }

    fun updateTemperature(value: Float) {
        _uiState.update { it.copy(temperature = value) }
    }

    fun updateTopP(value: Float) {
        _uiState.update { it.copy(topP = value) }
    }

    fun updateTopK(value: Int) {
        _uiState.update { it.copy(topK = value) }
    }

    fun updateMaxTokens(value: Int) {
        _uiState.update { it.copy(maxTokens = value) }
    }

    fun loadModelFromInputs() {
        val state = _uiState.value
        val threads = state.threadText.toIntOrNull()
        val contextLength = state.contextText.toIntOrNull()
        val gpuLayers = state.gpuText.toIntOrNull()
        if (state.modelPath.isBlank() || threads == null || contextLength == null || gpuLayers == null) {
            return
        }
        val config = StoredEngineConfig(
            modelPath = state.modelPath,
            threads = threads,
            contextLength = contextLength,
            gpuLayers = gpuLayers,
            useVulkan = state.useVulkan
        )
        viewModelScope.launch {
            loadModelInternal(config)
        }
    }

    fun unloadModel() {
        viewModelScope.launch {
            EngineRuntime.unload()
            EngineRuntime.clearState(true)
            ModelConfigStore.clear(getApplication())
            _uiState.update {
                it.copy(
                    storedConfig = null,
                    modelPath = "",
                    threadText = "6",
                    contextText = "4096",
                    gpuText = "20"
                )
            }
        }
    }

    private suspend fun loadModelInternal(config: StoredEngineConfig) {
        EngineRuntime.unload()
        EngineRuntime.clearState(true)
        val loaded = EngineRuntime.load(config.toEngineConfig())
        if (loaded) {
            ModelConfigStore.save(getApplication(), config)
            manifestService.ensureManifestFor(config.modelPath, EngineRuntime.currentModelMeta())
            _uiState.update {
                it.copy(
                    storedConfig = config,
                    modelPath = config.modelPath,
                    threadText = config.threads.toString(),
                    contextText = config.contextLength.toString(),
                    gpuText = config.gpuLayers.toString(),
                    useVulkan = config.useVulkan
                )
            }
        } else {
            ModelConfigStore.clear(getApplication())
            _uiState.update { it.copy(storedConfig = null) }
        }
    }

    fun activateManifest(manifest: ModelManifest) {
        _uiState.update {
            it.copy(
                modelPath = manifest.filePath,
                contextText = manifest.contextLength.takeIf { len -> len > 0 }?.toString() ?: it.contextText
            )
        }
    }

    fun deleteManifest(manifest: ModelManifest) {
        viewModelScope.launch {
            manifestService.deleteManifest(manifest, removeFile = true)
            if (_uiState.value.storedConfig?.modelPath == manifest.filePath) {
                ModelConfigStore.clear(getApplication())
                _uiState.update { it.copy(storedConfig = null, modelPath = "") }
            }
        }
    }
}
