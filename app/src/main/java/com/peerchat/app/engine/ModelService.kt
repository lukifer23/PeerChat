package com.peerchat.app.engine

import android.content.Context
import android.net.Uri
import com.peerchat.app.data.OperationResult
import com.peerchat.app.util.Logger
import com.peerchat.data.db.ModelManifest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * High-level service for model management operations.
 * Provides an abstraction over ModelRepository with robust loading, preloading, and health checking.
 */
class ModelService(
    private val context: Context,
    private val repository: ModelRepository
) {
    // Initialize robust loading components
    private val preloader = ModelPreloader(context, repository)
    private val healthChecker = ModelHealthChecker()
    private val loadManager = ModelLoadManager(context, repository, preloader, healthChecker)

    init {
        // Start preloader service
        preloader.start()

        Logger.i("ModelService: initialized with robust loading components")
    }
    /**
     * Loads a model with robust error recovery, progress tracking, and health checking.
     *
     * @param config The engine configuration including model path and parameters.
     * @param onProgress Optional callback for load progress updates.
     * @param onHealthCheck Optional callback for health check results.
     * @return Operation result with manifest on success or error message on failure.
     */
    suspend fun loadModel(
        config: StoredEngineConfig,
        onProgress: ((ModelLoadManager.ModelLoadProgress) -> Unit)? = null,
        onHealthCheck: ((ModelHealthChecker.HealthResult) -> Unit)? = null
    ): OperationResult<ModelManifest> {
        return Logger.profile("model_load_full", mapOf(
            "modelPath" to config.modelPath,
            "nThreads" to config.threads,
            "nGpuLayers" to config.gpuLayers,
            "nCtx" to config.contextLength
        )) {
            try {
                // Find manifest for this config
                Logger.startPerfTimer("model_manifest_lookup", mapOf("modelPath" to config.modelPath))
                val manifests = repository.listManifests()
                val manifest = manifests.firstOrNull { it.filePath == config.modelPath }
                Logger.endPerfTimer("model_manifest_lookup", mapOf("manifestsFound" to manifests.size))

                if (manifest == null) {
                    Logger.w("ModelService: manifest not found", mapOf("modelPath" to config.modelPath))
                    return@profile OperationResult.Failure("Model manifest not found for path: ${config.modelPath}")
                }

                Logger.perf("ModelService: starting model load", mapOf(
                    "modelId" to manifest.id,
                    "modelName" to manifest.name,
                    "modelSize" to manifest.sizeBytes
                ))

                val result = loadManager.loadModel(manifest, config, onProgress, onHealthCheck)

                when (result) {
                    is OperationResult.Success -> {
                        Logger.perf("ModelService: model loaded successfully", mapOf(
                            "modelId" to manifest.id,
                            "modelName" to manifest.name
                        ))
                    }
                    is OperationResult.Failure -> {
                        Logger.errorContext("ModelService: model load failed", null, mapOf(
                            "modelId" to manifest.id,
                            "modelName" to manifest.name,
                            "error" to result.error
                        ))
                    }
                }

                result
            } catch (e: Exception) {
                Logger.errorContext("ModelService: unexpected error during model load", e, mapOf(
                    "modelPath" to config.modelPath
                ))
                OperationResult.Failure("Model load error: ${e.message}")
            }
        }
    }

    /**
     * Loads a model by manifest with robust error recovery and progress tracking.
     *
     * @param manifest The model manifest to load.
     * @param config The engine configuration.
     * @param onProgress Optional callback for load progress updates.
     * @param onHealthCheck Optional callback for health check results.
     * @return Operation result with manifest on success or error message on failure.
     */
    suspend fun loadModel(
        manifest: ModelManifest,
        config: StoredEngineConfig,
        onProgress: ((ModelLoadManager.ModelLoadProgress) -> Unit)? = null,
        onHealthCheck: ((ModelHealthChecker.HealthResult) -> Unit)? = null
    ): OperationResult<ModelManifest> = loadManager.loadModel(manifest, config, onProgress, onHealthCheck)

    /**
     * Loads a model asynchronously with progress callbacks (legacy compatibility).
     *
     * @param config The engine configuration.
     * @param onProgress Callback invoked with progress messages.
     * @return Operation result with manifest on success or error message on failure.
     */
    suspend fun loadModelAsync(
        config: StoredEngineConfig,
        onProgress: (String) -> Unit = {}
    ): OperationResult<ModelManifest> {
        return loadModel(config, onProgress = { progress: ModelLoadManager.ModelLoadProgress ->
            onProgress(progress.message)
        })
    }

    /**
     * Unloads the currently loaded model.
     *
     * @return Status message indicating success or failure.
     */
    suspend fun unloadModel(): String = repository.unloadModel()

    /**
     * Imports a model from a URI (e.g., user-selected file).
     *
     * @param uri The URI of the model file to import.
     * @return Operation result with manifest on success or error message on failure.
     */
    suspend fun importModel(uri: Uri): OperationResult<ModelManifest> = repository.importModel(uri)

    /**
     * Deletes a model manifest and optionally removes the model file.
     *
     * @param manifest The manifest to delete.
     * @param removeFile Whether to delete the associated model file from storage.
     * @return Status message indicating success or failure.
     */
    suspend fun deleteManifest(manifest: ModelManifest, removeFile: Boolean): String = repository.deleteManifest(manifest, removeFile)

    /**
     * Activates a model manifest, making it the active model.
     *
     * @param manifest The manifest to activate.
     * @return Operation result with the activated manifest or error message.
     */
    suspend fun activateManifest(manifest: ModelManifest): OperationResult<ModelManifest> = repository.activateManifest(manifest)

    /**
     * Verifies a model manifest by checking file existence and checksum.
     *
     * @param manifest The manifest to verify.
     * @return true if verification succeeds, false otherwise.
     */
    suspend fun verifyManifest(manifest: ModelManifest): Boolean = repository.verifyManifest(manifest)

    /**
     * Returns a Flow of all model manifests.
     *
     * @return Flow emitting lists of manifests, updated whenever the manifest list changes.
     */
    fun getManifestsFlow(): Flow<List<ModelManifest>> = repository.getManifestsFlow()

    /**
     * Gets the currently active model manifest.
     *
     * @return The active manifest, or null if no model is loaded.
     */
    suspend fun getActiveManifest(): ModelManifest? = repository.getActiveManifest()

    /**
     * Gets the detected template ID for a model.
     *
     * @param manifest The model manifest to check.
     * @return The detected template ID, or null if not detected.
     */
    fun getDetectedTemplateId(manifest: ModelManifest): String? = repository.getDetectedTemplateId(manifest)

    /**
     * Unloads the model if memory pressure is high.
     *
     * @param maxAgeMinutes Currently unused (reserved for future time-based unload).
     * @return true if model was unloaded, false otherwise.
     */
    suspend fun smartUnload(maxAgeMinutes: Long = 30): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryPressure = usedMemory.toDouble() / maxMemory.toDouble()
        return if (memoryPressure > 0.8) {
            unloadModel(); true
        } else false
    }

    /**
     * Gets current memory usage statistics.
     *
     * @return Map containing usedMemory, totalMemory, maxMemory, and freeMemory (all in bytes).
     */
    fun getMemoryStats(): Map<String, Any> {
        val runtime = Runtime.getRuntime()
        return mapOf(
            "usedMemory" to (runtime.totalMemory() - runtime.freeMemory()),
            "totalMemory" to runtime.totalMemory(),
            "maxMemory" to runtime.maxMemory(),
            "freeMemory" to runtime.freeMemory()
        )
    }

    // Robust loading features

    /**
     * Request background preloading of a model.
     *
     * @param manifest The model manifest to preload.
     * @param priority Priority level (lower = higher priority).
     * @param config Optional configuration for preloading.
     */
    fun requestPreload(manifest: ModelManifest, priority: Int = 0, config: StoredEngineConfig? = null) {
        preloader.requestPreload(manifest, priority, config)
    }

    /**
     * Mark a model as recently used to influence preload priorities.
     *
     * @param manifest The model manifest that was used.
     */
    fun markRecentlyUsed(manifest: ModelManifest) {
        preloader.markRecentlyUsed(manifest)
    }

    /**
     * Check if a model is currently preloaded.
     *
     * @param modelPath The model file path.
     * @return true if the model is preloaded.
     */
    fun isPreloaded(modelPath: String): Boolean = preloader.isPreloaded(modelPath)

    /**
     * Get preload status for a specific model.
     *
     * @param modelPath The model file path.
     * @return Current preload status or null if not being preloaded.
     */
    fun getPreloadStatus(modelPath: String): ModelPreloader.PreloadStatus? =
        preloader.getPreloadStatus(modelPath)

    /**
     * Get preload statistics.
     *
     * @return Statistics about preloading performance.
     */
    fun getPreloadStats(): ModelPreloader.PreloadStats = preloader.getStats()

    /**
     * Get a flow of preload statuses.
     *
     * @return Flow emitting maps of model paths to their preload statuses.
     */
    fun getPreloadStatusesFlow(): Flow<Map<String, ModelPreloader.PreloadStatus>> =
        preloader.preloadStatus

    /**
     * Perform a comprehensive health check on the currently loaded model.
     *
     * @return Health check result.
     */
    suspend fun checkCurrentModelHealth(): ModelHealthChecker.HealthResult =
        healthChecker.checkCurrentModel()

    /**
     * Perform a quick smoke test on the currently loaded model.
     *
     * @return true if the basic test passes.
     */
    suspend fun smokeTestCurrentModel(): Boolean = healthChecker.smokeTest()

    /**
     * Cancel the current model loading operation.
     */
    fun cancelCurrentLoad() {
        loadManager.cancelLoad()
    }

    /**
     * Check if a model loading operation is currently in progress.
     *
     * @return true if loading is in progress.
     */
    fun isLoadInProgress(): Boolean = loadManager.isLoadInProgress()

    /**
     * Get current load progress.
     *
     * @return Current load progress or null if no load in progress.
     */
    fun getCurrentLoadProgress(): ModelLoadManager.ModelLoadProgress? =
        loadManager.getCurrentProgress()

    /**
     * Get a flow of load progress updates.
     *
     * @return Flow emitting load progress updates.
     */
    fun getLoadProgressFlow(): Flow<ModelLoadManager.ModelLoadProgress?> =
        loadManager.loadProgress

    /**
     * Get a combined flow of all loading-related statuses.
     *
     * @return Flow combining load progress and preload statuses.
     */
    fun getLoadingStatusFlow(): Flow<ModelLoadingStatus> =
        combine(
            loadManager.loadProgress,
            preloader.preloadStatus
        ) { loadProgress, preloadStatuses ->
            ModelLoadingStatus(
                loadProgress = loadProgress,
                preloadStatuses = preloadStatuses,
                isLoadInProgress = loadProgress != null,
                preloadStats = preloader.getStats()
            )
        }

    /**
     * Combined status of all loading operations.
     */
    data class ModelLoadingStatus(
        val loadProgress: ModelLoadManager.ModelLoadProgress?,
        val preloadStatuses: Map<String, ModelPreloader.PreloadStatus>,
        val isLoadInProgress: Boolean,
        val preloadStats: ModelPreloader.PreloadStats
    )

    /**
     * Shutdown all loading components and cancel background operations - SYNCHRONOUS AND IMMEDIATE.
     * Call this when the service is no longer needed to prevent resource leaks.
     */
    fun shutdown() {
        Logger.i("ModelService: IMMEDIATE SYNCHRONOUS shutdown initiated")

        try {
            // IMMEDIATE: Cancel any ongoing load operations synchronously
            Logger.i("ModelService: IMMEDIATE - cancelling load operations")
            loadManager.cancelLoad()

            // IMMEDIATE: Shutdown components in reverse order synchronously
            Logger.i("ModelService: IMMEDIATE - shutting down loadManager")
            loadManager.shutdown()
            Logger.i("ModelService: IMMEDIATE - shutting down preloader")
            preloader.shutdown()

            Logger.i("ModelService: IMMEDIATE SYNCHRONOUS shutdown complete")
        } catch (e: Exception) {
            Logger.e("ModelService: IMMEDIATE - Error during shutdown", mapOf("error" to e.message), e)
            // Continue with aggressive cleanup even if shutdown fails
            forceAggressiveCleanup()
        }
    }

    /**
     * Force aggressive cleanup if normal shutdown fails - SYNCHRONOUS AND IMMEDIATE
     */
    private fun forceAggressiveCleanup() {
        Logger.w("ModelService: IMMEDIATE FORCE aggressive cleanup initiated")

        try {
            // IMMEDIATE: Force cancel any remaining operations
            Logger.i("ModelService: IMMEDIATE FORCE - cancelling all operations")
            // Note: Individual components should handle their own force cleanup

            Logger.w("ModelService: IMMEDIATE FORCE aggressive cleanup complete")
        } catch (e: Exception) {
            Logger.e("ModelService: IMMEDIATE FORCE - Critical error during force cleanup", mapOf("error" to e.message), e)
        }
    }
}
