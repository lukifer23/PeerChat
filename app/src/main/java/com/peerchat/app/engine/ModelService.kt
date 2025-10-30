package com.peerchat.app.engine

import android.net.Uri
import com.peerchat.app.data.OperationResult
import com.peerchat.data.db.ModelManifest
import kotlinx.coroutines.flow.Flow

class ModelService(
    private val repository: ModelRepository
) {
    suspend fun loadModel(config: StoredEngineConfig): OperationResult<ModelManifest> = repository.loadModel(config)

    suspend fun loadModelAsync(
        config: StoredEngineConfig,
        onProgress: (String) -> Unit = {}
    ): OperationResult<ModelManifest> {
        onProgress("Preparing model load...")
        val result = repository.loadModel(config)
        when (result) {
            is OperationResult.Success -> onProgress("Model loaded successfully")
            is OperationResult.Failure -> onProgress("Failed to load model: ${result.error}")
        }
        return result
    }

    suspend fun unloadModel(): String = repository.unloadModel()

    suspend fun importModel(uri: Uri): OperationResult<ModelManifest> = repository.importModel(uri)

    suspend fun deleteManifest(manifest: ModelManifest, removeFile: Boolean): String = repository.deleteManifest(manifest, removeFile)

    suspend fun activateManifest(manifest: ModelManifest): OperationResult<ModelManifest> = repository.activateManifest(manifest)

    suspend fun verifyManifest(manifest: ModelManifest): Boolean = repository.verifyManifest(manifest)

    fun getManifestsFlow(): Flow<List<ModelManifest>> = repository.getManifestsFlow()

    suspend fun getActiveManifest(): ModelManifest? = repository.getActiveManifest()

    fun getDetectedTemplateId(manifest: ModelManifest): String? = repository.getDetectedTemplateId(manifest)

    suspend fun smartUnload(maxAgeMinutes: Long = 30): Boolean {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val memoryPressure = usedMemory.toDouble() / maxMemory.toDouble()
        return if (memoryPressure > 0.8) {
            unloadModel(); true
        } else false
    }

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
