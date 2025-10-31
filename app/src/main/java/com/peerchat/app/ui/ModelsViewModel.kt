package com.peerchat.app.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.peerchat.app.engine.BenchmarkService
import com.peerchat.app.engine.ModelRepository
import com.peerchat.app.engine.ModelService
import com.peerchat.app.ui.ModelsViewModel.ModelsEvent.Toast
import com.peerchat.app.ui.components.BenchmarkDialogState
import com.peerchat.data.db.ModelManifest
import com.peerchat.app.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    @ApplicationContext private val appContext: Context,
    private val modelService: ModelService,
    private val modelRepository: ModelRepository,
) : BaseViewModel() {

    private val _uiState = MutableStateFlow(ModelsUiState())
    val uiState: StateFlow<ModelsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ModelsEvent>()
    val events = _events.asSharedFlow()

    private val _benchmarkState = MutableStateFlow<BenchmarkDialogState>(BenchmarkDialogState.Closed)
    val benchmarkState: StateFlow<BenchmarkDialogState> = _benchmarkState.asStateFlow()

    private var benchmarkJob: Job? = null

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
            Logger.i(
                "models.activate:start",
                mapOf(
                    "manifestId" to manifest.id,
                    "name" to manifest.name,
                    "sizeBytes" to manifest.sizeBytes
                )
            )
            _uiState.update { it.copy(activatingId = manifest.id) }

            // First validate the model
            when (val validationResult = modelRepository.validateModel(manifest.filePath)) {
                is com.peerchat.app.data.OperationResult.Failure -> {
                    emitToast("Model validation failed: ${validationResult.error}", true)
                    Logger.e(
                        "models.activate:validation_failed",
                        mapOf(
                            "manifestId" to manifest.id,
                            "reason" to validationResult.error
                        )
                    )
                    _uiState.update { it.copy(activatingId = null) }
                    return@launch
                }
                is com.peerchat.app.data.OperationResult.Success -> {
                    // Validation passed, proceed with activation
                }
            }

            when (val result = modelRepository.activateManifest(manifest)) {
                is com.peerchat.app.data.OperationResult.Success -> {
                    val loaded = result.data
                    emitToast("Loaded ${loaded.name}", false)
                     Logger.i(
                         "models.activate:success",
                         mapOf(
                             "manifestId" to manifest.id,
                             "name" to loaded.name
                         )
                     )
                    refreshActiveManifest()
                }
                is com.peerchat.app.data.OperationResult.Failure -> {
                    emitToast(result.error, true)
                    Logger.e(
                        "models.activate:failure",
                        mapOf(
                            "manifestId" to manifest.id,
                            "reason" to result.error
                        )
                    )
                }
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

    // Benchmark functionality
    fun showBenchmarkDialog() {
        _benchmarkState.value = BenchmarkDialogState.SelectingModel
    }

    fun dismissBenchmarkDialog() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        _benchmarkState.value = BenchmarkDialogState.Closed
    }

    fun startBenchmark(manifest: ModelManifest) {
        // Cancel any existing benchmark
        benchmarkJob?.cancel()

        // Check if model is loaded
        val activeManifest = uiState.value.activeManifestId
        if (activeManifest != manifest.id) {
            _benchmarkState.value = BenchmarkDialogState.Error("Model must be loaded before benchmarking. Please load the model first.")
            return
        }

        benchmarkJob = viewModelScope.launch {
            _benchmarkState.value = BenchmarkDialogState.Running(
                manifest = manifest,
                progress = BenchmarkService.BenchmarkProgress(
                    stage = "Starting benchmark...",
                    progress = 0.0f,
                    message = "Initializing benchmark tests"
                ),
                results = emptyList()
            )

            val result = BenchmarkService.runBenchmark(
                context = appContext,
                manifest = manifest
            ) { progress ->
                runCatching {
                    _benchmarkState.value = BenchmarkDialogState.Running(
                        manifest = manifest,
                        progress = progress,
                        results = progress.completedResults
                    )
                }.onFailure { e ->
                    Logger.e("ModelsViewModel: benchmark progress update failed", mapOf("error" to e.message), e)
                }
            }

            when (result) {
                is com.peerchat.app.data.OperationResult.Success -> {
                    _benchmarkState.value = BenchmarkDialogState.Completed(
                        manifest = manifest,
                        results = result.data
                    )
                }
                is com.peerchat.app.data.OperationResult.Failure -> {
                    _benchmarkState.value = BenchmarkDialogState.Error(result.error)
                }
            }
        }
    }

    fun cancelBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = null
        // Ensure native generation is aborted
        com.peerchat.engine.EngineNative.abort()
        _benchmarkState.value = BenchmarkDialogState.Closed
        emitToast("Benchmark cancelled", false)
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
