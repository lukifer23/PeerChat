package com.peerchat.app.engine

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.peerchat.app.data.OperationResult
import com.peerchat.app.engine.ModelStorage.importModel
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service for managing model lifecycle operations including loading, unloading,
 * importing, and manifest management.
 */
class ModelService(
    private val context: Context,
    private val manifestService: ModelManifestService,
    private val modelCache: ModelStateCache
) {
    private val appContext = context.applicationContext as Application


    /**
     * Load a model with the given configuration.
     */
    suspend fun loadModel(config: StoredEngineConfig): OperationResult<ModelManifest> = withContext(Dispatchers.IO) {
        loadModelInternal(config)
    }

    /**
     * Load a model asynchronously with progress callback.
     */
    suspend fun loadModelAsync(
        config: StoredEngineConfig,
        onProgress: (String) -> Unit = {}
    ): OperationResult<ModelManifest> = withContext(Dispatchers.IO) {
        onProgress("Preparing model load...")
        val result = loadModelInternal(config)
        when (result) {
            is OperationResult.Success -> onProgress("Model loaded successfully")
            is OperationResult.Failure -> onProgress("Failed to load model: ${result.error}")
        }
        result
    }

    private suspend fun loadModelInternal(config: StoredEngineConfig): OperationResult<ModelManifest> {
        try {
            // Unload any existing model
            unloadModelInternal()

            // Load the new model
            val loaded = EngineRuntime.load(config.toEngineConfig())

            if (loaded) {
                // Save config and update manifest
                ModelConfigStore.save(appContext, config)

                val manifest = manifestService.list().firstOrNull { it.filePath == config.modelPath }
                val updatedManifest = manifest?.let { manifestService.refreshManifest(it) }

                val finalManifest = updatedManifest ?: manifest
                if (finalManifest != null) {
                    OperationResult.Success(finalManifest, "Model loaded successfully")
                } else {
                    OperationResult.Failure("Model loaded but manifest not found")
                }
            } else {
                ModelConfigStore.clear(appContext)
                OperationResult.Failure("Failed to load model")
            }
        } catch (e: Exception) {
            ModelConfigStore.clear(appContext)
            OperationResult.Failure("Error loading model: ${e.message}")
        }
    }

    /**
     * Unload the current model.
     */
    suspend fun unloadModel(): String = withContext(Dispatchers.IO) {
        try {
            unloadModelInternal()
            ModelConfigStore.clear(appContext)
            "Model unloaded"
        } catch (e: Exception) {
            "Error unloading model: ${e.message}"
        }
    }

    /**
     * Import a model from a URI.
     */
    suspend fun importModel(uri: Uri): OperationResult<ModelManifest> = withContext(Dispatchers.IO) {
        try {
            val path = importModel(appContext, uri)
            if (path.isNullOrEmpty()) {
                return@withContext OperationResult.Failure("Import failed")
            }

            // Create/update manifest
            manifestService.ensureManifestFor(path)

            val manifests = manifestService.list()
            val manifest = manifests.firstOrNull { it.filePath == path }

            OperationResult.Success(manifest!!, "Model imported successfully")
        } catch (e: Exception) {
            OperationResult.Failure("Import error: ${e.message}")
        }
    }

    /**
     * Delete a model manifest and optionally its file.
     */
    suspend fun deleteManifest(manifest: ModelManifest, removeFile: Boolean): String {
        return try {
            val isActive = isManifestActive(manifest)
            manifestService.deleteManifest(manifest, removeFile)

            if (isActive) {
                unloadModelInternal()
                ModelConfigStore.clear(appContext)
            }

            if (removeFile) "Model deleted" else "Manifest removed"
        } catch (e: Exception) {
            "Error deleting manifest: ${e.message}"
        }
    }

    /**
     * Activate a model manifest by setting it as the active configuration.
     */
    suspend fun activateManifest(manifest: ModelManifest): OperationResult<ModelManifest> {
        val storedConfig = ModelConfigStore.load(appContext)
        val config = StoredEngineConfig(
            modelPath = manifest.filePath,
            threads = storedConfig?.threads ?: 6,
            contextLength = manifest.contextLength.takeIf { it > 0 } ?: (storedConfig?.contextLength ?: 4096),
            gpuLayers = storedConfig?.gpuLayers ?: 20,
            useVulkan = storedConfig?.useVulkan ?: true
        )
        return loadModel(config)
    }

    /**
     * Verify a model manifest by checking file integrity.
     */
    suspend fun verifyManifest(manifest: ModelManifest): Boolean {
        return manifestService.verify(manifest)
    }

    /**
     * Get all available manifests.
     */
    fun getManifestsFlow(): Flow<List<ModelManifest>> = manifestService.manifestsFlow()

    /**
     * Get the currently active manifest.
     */
    suspend fun getActiveManifest(): ModelManifest? {
        val config = ModelConfigStore.load(appContext) ?: return null
        return manifestService.list().firstOrNull { it.filePath == config.modelPath }
    }

    /**
     * Check if a manifest is currently active.
     */
    private fun isManifestActive(manifest: ModelManifest): Boolean {
        val config = ModelConfigStore.load(appContext)
        return config?.modelPath == manifest.filePath
    }

    /**
     * Get the detected template ID for a manifest.
     */
    fun getDetectedTemplateId(manifest: ModelManifest): String? {
        return manifestService.detectedTemplateId(manifest)
    }

    /**
     * Smart unload based on memory pressure and time since last use.
     */
    suspend fun smartUnload(maxAgeMinutes: Long = 30): Boolean = withContext(Dispatchers.IO) {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryPressure = usedMemory.toDouble() / maxMemory.toDouble()

        // Unload if memory pressure is high (>80%) or no recent activity
        if (memoryPressure > 0.8) {
            unloadModel()
            true
        } else {
            false
        }
    }

    /**
     * Get current memory usage statistics.
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

    /**
     * Internal method to unload model without clearing config.
     */
    private suspend fun unloadModelInternal() {
        EngineRuntime.unload()
        EngineRuntime.clearState(true)
        modelCache.clearAll()
    }
}
