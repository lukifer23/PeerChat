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
        } catch (e: Exception) {
            Logger.e("ModelLoadManager: exception waiting for load", mapOf(
                "model" to manifest.name,
                "error" to e.message
            ), e)
            cancelLoad()
            OperationResult.Failure("Load operation failed: ${e.message}")
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

            // Stage 1: Validation with corruption detection
            updateProgress(modelPath, LoadStage.VALIDATING, 0.1f, "Validating model file...")
            onProgress?.invoke(loadProgress.value!!)

            val validation = modelRepository.validateModel(modelPath)
            if (validation is OperationResult.Failure) {
                // Check if it's a corruption issue
                val isCorrupted = detectCorruption(validation.error)
                if (isCorrupted) {
                    Logger.w("ModelLoadManager: detected potential corruption", mapOf(
                        "model" to manifest.name,
                        "error" to validation.error
                    ))
                    updateProgress(modelPath, LoadStage.FAILED, 0f, 
                        "Model file appears corrupted: ${validation.error}. Please re-download the model.")
                    return
                }
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
                Logger.w("ModelLoadManager: preloaded model activation failed, falling back to full load")
            }

            // Stage 3: Full load with recovery and graceful degradation
            updateProgress(modelPath, LoadStage.LOADING, 0.3f, "Loading model...", canCancel = true)
            onProgress?.invoke(loadProgress.value!!)

            val loadStartTime = System.currentTimeMillis()
            var loadResult = modelRepository.loadModel(config)
            val loadTime = System.currentTimeMillis() - loadStartTime

            // If initial load failed, try graceful degradation with alternative configs
            if (loadResult is OperationResult.Failure) {
                Logger.w("ModelLoadManager: initial load failed, attempting graceful degradation", mapOf(
                    "model" to manifest.name,
                    "error" to loadResult.error,
                    "loadTimeMs" to loadTime
                ))
                
                loadResult = tryGracefulDegradation(manifest, config, loadResult.error)
            }

            when (loadResult) {
                is OperationResult.Success -> {
                    updateProgress(modelPath, LoadStage.HEALTH_CHECKING, 0.8f, "Running health checks...")
                    onProgress?.invoke(loadProgress.value!!)

                    // Stage 4: Health check with retry on failure
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
                            
                            // Try recovery: unload and reload with reduced config
                            val recoveryResult = tryRecoveryFromUnhealthyState(manifest, config, healthResult.failures)
                            if (recoveryResult is OperationResult.Success) {
                                completeLoad(manifest, "Model recovered from unhealthy state")
                            } else {
                                updateProgress(modelPath, LoadStage.FAILED, 0f,
                                    "Health check failed: ${healthResult.failures.joinToString("; ")}")
                            }
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
                    Logger.w("ModelLoadManager: load failed after all recovery attempts", mapOf(
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
    
    /**
     * Detect if an error indicates model corruption
     */
    private fun detectCorruption(error: String): Boolean {
        val corruptionKeywords = listOf(
            "corrupt", "invalid format", "magic number", "header", "truncated",
            "unexpected end", "cannot read", "malformed", "checksum"
        )
        return corruptionKeywords.any { error.lowercase().contains(it) }
    }
    
    /**
     * Try graceful degradation with alternative configurations
     */
    private suspend fun tryGracefulDegradation(
        manifest: ModelManifest,
        originalConfig: StoredEngineConfig,
        originalError: String
    ): OperationResult<ModelManifest> {
        Logger.i("ModelLoadManager: attempting graceful degradation", mapOf(
            "model" to manifest.name,
            "originalError" to originalError
        ))
        
        // Try progressively reduced configurations
        val degradationConfigs = listOf(
            // Reduce GPU layers by half
            originalConfig.copy(gpuLayers = (originalConfig.gpuLayers / 2).coerceAtLeast(0)),
            // Disable GPU entirely
            originalConfig.copy(gpuLayers = 0, useVulkan = false),
            // Reduce context length
            originalConfig.copy(
                gpuLayers = 0,
                useVulkan = false,
                contextLength = minOf(originalConfig.contextLength, 2048)
            ),
            // Minimal config
            originalConfig.copy(
                gpuLayers = 0,
                useVulkan = false,
                contextLength = minOf(originalConfig.contextLength, 1024),
                threads = maxOf(2, originalConfig.threads / 2)
            )
        )
        
        for ((index, degradedConfig) in degradationConfigs.withIndex()) {
            if (degradedConfig == originalConfig) continue // Skip if same as original
            
            Logger.i("ModelLoadManager: trying degraded config ${index + 1}/${degradationConfigs.size}", mapOf(
                "model" to manifest.name,
                "gpuLayers" to degradedConfig.gpuLayers,
                "useVulkan" to degradedConfig.useVulkan,
                "contextLength" to degradedConfig.contextLength
            ))
            
            updateProgress(
                manifest.filePath,
                LoadStage.LOADING,
                0.3f + (index * 0.1f),
                "Retrying with reduced configuration...",
                canCancel = true
            )
            
            val result = modelRepository.loadModel(degradedConfig)
            if (result is OperationResult.Success) {
                Logger.i("ModelLoadManager: graceful degradation succeeded", mapOf(
                    "model" to manifest.name,
                    "configIndex" to index
                ))
                return result
            }
            
            // Brief delay between attempts
            kotlinx.coroutines.delay(500)
        }
        
        return OperationResult.Failure("All degradation attempts failed. Original error: $originalError")
    }
    
    /**
     * Try to recover from an unhealthy model state
     */
    private suspend fun tryRecoveryFromUnhealthyState(
        manifest: ModelManifest,
        config: StoredEngineConfig,
        failures: List<String>
    ): OperationResult<ModelManifest> {
        Logger.i("ModelLoadManager: attempting recovery from unhealthy state", mapOf(
            "model" to manifest.name,
            "failures" to failures.joinToString(", ")
        ))
        
        // Clear engine state and try reloading
        try {
            EngineRuntime.unload()
            kotlinx.coroutines.delay(1000) // Brief pause for cleanup
            
            updateProgress(
                manifest.filePath,
                LoadStage.LOADING,
                0.5f,
                "Recovering from unhealthy state...",
                canCancel = true
            )
            
            // Try reloading with CPU-only config (most stable)
            val recoveryConfig = config.copy(
                gpuLayers = 0,
                useVulkan = false,
                contextLength = minOf(config.contextLength, 2048)
            )
            
            val recoveryResult = modelRepository.loadModel(recoveryConfig)
            if (recoveryResult is OperationResult.Success) {
                // Re-run health check
                val healthResult = healthChecker.checkCurrentModel()
                if (healthResult is ModelHealthChecker.HealthResult.Healthy) {
                    Logger.i("ModelLoadManager: recovery successful", mapOf(
                        "model" to manifest.name
                    ))
                    return OperationResult.Success(manifest, "Model recovered from unhealthy state")
                }
            }

            val errorMessage = when (recoveryResult) {
                is OperationResult.Failure -> recoveryResult.error
                is OperationResult.Success -> "Health check failed after recovery"
            }
            return OperationResult.Failure("Recovery failed: $errorMessage")
        } catch (e: Exception) {
            Logger.e("ModelLoadManager: recovery attempt failed", mapOf(
                "model" to manifest.name,
                "error" to e.message
            ), e)
            return OperationResult.Failure("Recovery failed: ${e.message}")
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

    /**
     * Clean up resources and cancel ongoing operations
     */
    fun shutdown() {
        cancelLoad()
        scope.cancel() // Cancel the entire scope to prevent leaks
        currentLoadJob = null
        currentCancellationCallback = null
        _loadProgress.value = null
        Logger.i("ModelLoadManager: shutdown complete")
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 180_000L // 3 minutes (reduced from 5 for better UX)
    }
}
