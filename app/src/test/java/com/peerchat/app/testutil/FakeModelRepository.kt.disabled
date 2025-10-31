package com.peerchat.app.testutil

import com.peerchat.app.data.OperationResult
import com.peerchat.app.engine.StoredEngineConfig
import com.peerchat.data.db.ModelManifest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake ModelRepository for testing.
 * Provides in-memory implementations of model and cache operations.
 * 
 * Note: This is a standalone test utility. For tests that need the real ModelRepository
 * class, use mockk or dependency injection with a test module.
 */
class FakeModelRepository {
    private val _manifests = MutableStateFlow<List<ModelManifest>>(emptyList())
    val manifestsFlow: Flow<List<ModelManifest>> = _manifests.asStateFlow()

    data class CacheStats(
        val hits: Long = 0,
        val misses: Long = 0,
        val evictions: Long = 0,
        val bytes: Long = 0
    )

    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats.asStateFlow()

    private val cache = mutableMapOf<Long, ByteArray>()
    private var activeManifest: ModelManifest? = null

    var shouldFailLoad = false
    var shouldFailRestore = false

    suspend fun loadModel(config: StoredEngineConfig): OperationResult<ModelManifest> {
        if (shouldFailLoad) {
            return OperationResult.Failure("Simulated load failure")
        }
        val manifest = ModelManifest(
            id = 1,
            name = config.modelPath.substringAfterLast("/"),
            filePath = config.modelPath,
            family = "test",
            sizeBytes = 0,
            checksumSha256 = "test",
            contextLength = config.contextLength,
            importedAt = System.currentTimeMillis(),
            sourceUrl = null,
            metadataJson = "{}",
            isDefault = false
        )
        activeManifest = manifest
        _manifests.value = listOf(manifest)
        return OperationResult.Success(manifest, "Loaded successfully")
    }

    suspend fun unloadModel(): String {
        activeManifest = null
        return "Unloaded"
    }

    suspend fun captureKv(chatId: Long) {
        cache[chatId] = "state_$chatId".toByteArray()
        _cacheStats.value = _cacheStats.value.copy(
            hits = _cacheStats.value.hits,
            misses = _cacheStats.value.misses,
            bytes = cache.values.sumOf { it.size.toLong() }
        )
    }

    suspend fun restoreKv(chatId: Long): Boolean {
        if (shouldFailRestore || !cache.containsKey(chatId)) {
            _cacheStats.value = _cacheStats.value.copy(misses = _cacheStats.value.misses + 1)
            return false
        }
        _cacheStats.value = _cacheStats.value.copy(hits = _cacheStats.value.hits + 1)
        return true
    }

    suspend fun clearKv(chatId: Long) {
        cache.remove(chatId)
        _cacheStats.value = _cacheStats.value.copy(
            bytes = cache.values.sumOf { it.size.toLong() }
        )
    }

    suspend fun getActiveManifest(): ModelManifest? = activeManifest

    fun getDetectedTemplateId(manifest: ModelManifest): String? = "default"

    suspend fun deleteManifest(manifest: ModelManifest, removeFile: Boolean): String {
        _manifests.value = _manifests.value.filter { it.id != manifest.id }
        if (activeManifest?.id == manifest.id) {
            activeManifest = null
        }
        return "Deleted"
    }

    suspend fun verifyManifest(manifest: ModelManifest): Boolean = true

    suspend fun importModel(uri: android.net.Uri): OperationResult<ModelManifest> {
        return OperationResult.Failure("Not implemented in fake")
    }

    suspend fun activateManifest(manifest: ModelManifest): OperationResult<ModelManifest> {
        activeManifest = manifest
        return OperationResult.Success(manifest, "Activated")
    }

    // Additional test helpers
    fun setManifests(manifests: List<ModelManifest>) {
        _manifests.value = manifests
    }

    fun clearCache() {
        cache.clear()
        _cacheStats.value = ModelRepository.CacheStats()
    }
}

