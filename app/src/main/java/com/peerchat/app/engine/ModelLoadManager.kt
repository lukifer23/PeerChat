package com.peerchat.app.engine

import android.content.Context
import com.peerchat.app.data.OperationResult
import com.peerchat.app.util.Logger
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds

/**
 * Orchestrates model loading with comprehensive error recovery, progress tracking,
 * and fallback strategies. Manages the complete loading lifecycle.
 */
class ModelLoadManager(
    private val context: Context,
    private val modelRepository: ModelRepository,
    private val modelPreloader: ModelPreloader,
    private val healthChecker: ModelHealthChecker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loadMutex = Mutex()
    private val isLoading = AtomicBoolean(false)

    // Progress tracking
    private val _loadProgress = MutableStateFlow<ModelLoadProgress?>(null)
    val loadProgress: StateFlow<ModelLoadProgress?> = _loadProgress.asStateFlow()

    // Current load operation
    private var currentLoadJob: Job? = null
    private var currentCancellationCallback: (() -> Unit)? = null

    data class ModelLoadProgress(
        val modelPath: String,
        val stage: LoadStage,
        val progress: Float, // 0.0 to 1.0
        val message: String,
        val canCancel: Boolean = false,
        val estimatedTimeRemainingMs: Long? = null
    )

    enum class LoadStage {
        VALIDATING,
        PRELOADING,
        LOADING,
        HEALTH_CHECKING,
        FINALIZING,
        COMPLETED,
        FAILED
    }

    /**
     * Load a model with comprehensive error recovery and progress tracking
     */
    suspend fun loadModel(
        manifest: ModelManifest,
        config: StoredEngineConfig,
        onProgress: ((ModelLoadProgress) -> Unit)? = null,
        onHealthCheck: ((ModelHealthChecker.HealthResult) -> Unit)? = null
    ): OperationResult<ModelManifest> = loadMutex.withLock {
        if (isLoading.get()) {
            return OperationResult.Failure("Model loading already in progress")
        }

        isLoading.set(true)
        currentLoadJob = scope.launch {
            performLoad(manifest, config, onProgress, onHealthCheck)
        }

        try {
            // Wait for completion with timeout
            val result = withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                currentLoadJob?.join()
                // Get final result from internal state
                when (val finalProgress = loadProgress.value) {
                    null -> OperationResult.Failure("Load operation completed without result")
                    else -> when (finalProgress.stage) {
                        LoadStage.COMPLETED -> OperationResult.Success(manifest, "Model loaded successfully")
                        LoadStage.FAILED -> OperationResult.Failure(finalProgress.message)
                        else -> OperationResult.Failure("Load operation ended in unexpected state: ${finalProgress.stage}")
                    }
                }
            } ?: run {
                cancelLoad()
                OperationResult.Failure("Model loading timed out after ${LOAD_TIMEOUT_MS / 1000}s")
            }

            result
        } finally {
            isLoading.set(false)
            currentLoadJob = null
            currentCancellationCallback = null
            _loadProgress.value = null
        }
    }

    /**
     * Cancel the current load operation
     */
    fun cancelLoad() {
        currentLoadJob?.cancel()
        currentCancellationCallback?.invoke()
        isLoading.set(false)
        updateProgress(stage = LoadStage.FAILED, message = "Load cancelled by user")
        Logger.i("ModelLoadManager: load cancelled")
    }

    /**
     * Check if a load operation is currently in progress
     */
    fun isLoadInProgress(): Boolean = isLoading.get()

    /**
     * Get current load progress
     */
    fun getCurrentProgress(): ModelLoadProgress? = loadProgress.value

    // Private implementation

    private suspend fun performLoad(
        manifest: ModelManifest,
        config: StoredEngineConfig,
        onProgress: ((ModelLoadProgress) -> Unit)?,
        onHealthCheck: ((ModelHealthChecker.HealthResult) -> Unit)?
    ) {
        val modelPath = manifest.filePath

        try {
            Logger.i("ModelLoadManager: starting load", mapOf(
                "model" to manifest.name,
                "path" to modelPath,
                "config" to config.toString()
            ))

            // Stage 1: Validation
            updateProgress(modelPath, LoadStage.VALIDATING, 0.1f, "Validating model file...")
            onProgress?.invoke(loadProgress.value!!)

            val validation = modelRepository.validateModel(modelPath)
            if (validation is OperationResult.Failure) {
                updateProgress(modelPath, LoadStage.FAILED, 0f, "Validation failed: ${validation.error}")
                return
            }

            // Stage 2: Check preloader
            updateProgress(modelPath, LoadStage.PRELOADING, 0.2f, "Checking preloaded models...")
            onProgress?.invoke(loadProgress.value!!)

            val preloaded = modelPreloader.getPreloadedModel(modelPath)
            if (preloaded != null) {
                // Use preloaded model - just activate it
                updateProgress(modelPath, LoadStage.LOADING, 0.6f, "Activating preloaded model...")
                onProgress?.invoke(loadProgress.value!!)

                val activateResult = modelRepository.activateManifest(manifest)
                if (activateResult is OperationResult.Success) {
                    completeLoad(manifest, "Model activated from preload")
                    return
                }
                // Fall through to full load if activation failed
            }

            // Stage 3: Full load with recovery
            updateProgress(modelPath, LoadStage.LOADING, 0.3f, "Loading model...", canCancel = true)
            onProgress?.invoke(loadProgress.value!!)

            val loadStartTime = System.currentTimeMillis()
            val loadResult = modelRepository.loadModel(config)
            val loadTime = System.currentTimeMillis() - loadStartTime

            when (loadResult) {
                is OperationResult.Success -> {
                    updateProgress(modelPath, LoadStage.HEALTH_CHECKING, 0.8f, "Running health checks...")
                    onProgress?.invoke(loadProgress.value!!)

                    // Stage 4: Health check
                    val healthResult = healthChecker.checkCurrentModel()
                    onHealthCheck?.invoke(healthResult)

                    when (healthResult) {
                        is ModelHealthChecker.HealthResult.Healthy -> {
                            Logger.i("ModelLoadManager: health check passed", mapOf(
                                "model" to manifest.name,
                                "checks" to healthResult.checksPassed.joinToString(", "),
                                "loadTimeMs" to loadTime
                            ))
                            completeLoad(manifest, "Model loaded and verified healthy (${loadTime}ms)")
                        }
                        is ModelHealthChecker.HealthResult.Unhealthy -> {
                            Logger.w("ModelLoadManager: health check failed", mapOf(
                                "model" to manifest.name,
                                "failures" to healthResult.failures.joinToString(", ")
                            ))
                            updateProgress(modelPath, LoadStage.FAILED, 0f,
                                "Health check failed: ${healthResult.failures.joinToString("; ")}")
                        }
                        is ModelHealthChecker.HealthResult.Error -> {
                            Logger.e("ModelLoadManager: health check error", mapOf(
                                "model" to manifest.name,
                                "error" to healthResult.error
                            ))
                            updateProgress(modelPath, LoadStage.FAILED, 0f,
                                "Health check error: ${healthResult.error}")
                        }
                    }
                }
                is OperationResult.Failure -> {
                    Logger.w("ModelLoadManager: load failed", mapOf(
                        "model" to manifest.name,
                        "error" to loadResult.error,
                        "loadTimeMs" to loadTime
                    ))
                    updateProgress(modelPath, LoadStage.FAILED, 0f, "Load failed: ${loadResult.error}")
                }
            }

        } catch (e: CancellationException) {
            updateProgress(modelPath, LoadStage.FAILED, 0f, "Load cancelled")
            throw e
        } catch (e: Exception) {
            Logger.e("ModelLoadManager: unexpected error during load", mapOf(
                "model" to manifest.name,
                "error" to e.message
            ), e)
            updateProgress(modelPath, LoadStage.FAILED, 0f, "Unexpected error: ${e.message}")
        }
    }

    private fun updateProgress(
        modelPath: String = "",
        stage: LoadStage,
        progress: Float = 0f,
        message: String = "",
        canCancel: Boolean = false,
        estimatedTimeRemainingMs: Long? = null
    ) {
        _loadProgress.value = ModelLoadProgress(
            modelPath = modelPath,
            stage = stage,
            progress = progress.coerceIn(0f, 1f),
            message = message,
            canCancel = canCancel,
            estimatedTimeRemainingMs = estimatedTimeRemainingMs
        )
    }

    private fun completeLoad(manifest: ModelManifest, message: String) {
        updateProgress(manifest.filePath, LoadStage.COMPLETED, 1f, message)

        // Mark as recently used for preloader
        modelPreloader.markRecentlyUsed(manifest)

        Logger.i("ModelLoadManager: load completed", mapOf(
            "model" to manifest.name,
            "message" to message
        ))
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 300_000L // 5 minutes
    }
}
