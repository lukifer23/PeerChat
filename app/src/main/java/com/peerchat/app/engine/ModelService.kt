package com.peerchat.app.engine

import android.net.Uri
import com.peerchat.app.data.OperationResult
import com.peerchat.data.db.ModelManifest
import kotlinx.coroutines.flow.Flow

/**
 * High-level service for model management operations.
 * Provides an abstraction over ModelRepository with additional utility methods.
 */
class ModelService(
    private val repository: ModelRepository
) {
    /**
     * Loads a model with the given configuration.
     *
     * @param config The engine configuration including model path and parameters.
     * @return Operation result with manifest on success or error message on failure.
     */
    suspend fun loadModel(config: StoredEngineConfig): OperationResult<ModelManifest> = repository.loadModel(config)

    /**
     * Loads a model asynchronously with progress callbacks.
     *
     * @param config The engine configuration.
     * @param onProgress Callback invoked with progress messages.
     * @return Operation result with manifest on success or error message on failure.
     */
    suspend fun loadModelAsync(
        config: StoredEngineConfig,
        onProgress: (String) -> Unit = {}
    ): OperationResult<ModelManifest> {
        onProgress("Preparing model load...")
        val result = repository.loadModel(config)
        when (result) {
            is OperationResult.Success -> onProgress(
                result.message.ifBlank { "Model loaded successfully" }
            )
            is OperationResult.Failure -> onProgress("Failed to load model: ${result.error}")
        }
        return result
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
}
