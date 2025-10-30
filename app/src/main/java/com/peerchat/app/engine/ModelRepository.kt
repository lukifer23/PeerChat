package com.peerchat.app.engine

import android.app.Application
import android.content.Context
import android.net.Uri
import com.peerchat.app.data.OperationResult
import com.peerchat.app.util.Logger
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.withLock

class ModelRepository(
    context: Context,
    private val manifestService: ModelManifestService,
    private val maxCacheFiles: Int = DEFAULT_MAX_CACHE_FILES,
    private val maxCacheBytes: Long = DEFAULT_MAX_CACHE_BYTES,
) {
    private val appContext = context.applicationContext as Application

    // KV cache
    private val cacheDir: File by lazy { File(appContext.filesDir, "kv_cache").apply { if (!exists()) mkdirs() } }
    private fun stateFile(chatId: Long): File = File(cacheDir, "chat_${chatId}.kvc")
    private val cacheLock = ReentrantLock()
    private val cacheIndex = object : LinkedHashMap<Long, CacheEntry>(16, 0.75f, true) {}
    private var cacheBytes: Long = 0
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)
    private val cacheEvictions = AtomicLong(0)
    private val _cacheStats = MutableStateFlow(CacheStats())
    val cacheStats: StateFlow<CacheStats> = _cacheStats
    private data class CacheEntry(val file: File, var size: Long)

    // --------------------- Model lifecycle ---------------------
    suspend fun loadModel(config: StoredEngineConfig): OperationResult<ModelManifest> = withContext(Dispatchers.IO) {
        return@withContext try {
            unloadInternal()
            val loaded = EngineRuntime.load(config.toEngineConfig())
            if (loaded) {
                ModelConfigStore.save(appContext, config)
                manifestService.ensureManifestFor(
                    path = config.modelPath,
                    modelMetaJson = EngineRuntime.currentModelMeta(),
                    isDefault = false
                )
                val finalManifest = manifestService.list().firstOrNull { it.filePath == config.modelPath }
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

    suspend fun unloadModel(): String = withContext(Dispatchers.IO) {
        return@withContext try {
            unloadInternal()
            ModelConfigStore.clear(appContext)
            "Model unloaded"
        } catch (e: Exception) {
            "Error unloading model: ${e.message}"
        }
    }

    private suspend fun unloadInternal() {
        EngineRuntime.unload()
        EngineRuntime.clearState(true)
        clearAllKv()
    }

    // --------------------- Manifests and import ---------------------
    suspend fun importModel(uri: Uri): OperationResult<ModelManifest> = withContext(Dispatchers.IO) {
        try {
            val path = ModelStorage.importModel(appContext, uri)
            if (path.isNullOrEmpty()) return@withContext OperationResult.Failure("Import failed")
            manifestService.ensureManifestFor(path)
            val manifests = manifestService.list()
            val manifest = manifests.firstOrNull { it.filePath == path }
            OperationResult.Success(requireNotNull(manifest), "Model imported successfully")
        } catch (e: Exception) {
            OperationResult.Failure("Import error: ${e.message}")
        }
    }

    suspend fun deleteManifest(manifest: ModelManifest, removeFile: Boolean): String {
        return try {
            val storedConfig = ModelConfigStore.load(appContext)
            val isActive = storedConfig?.modelPath == manifest.filePath
            manifestService.deleteManifest(manifest, removeFile)
            if (isActive) {
                unloadInternal()
                ModelConfigStore.clear(appContext)
            }
            if (removeFile) "Model deleted" else "Manifest removed"
        } catch (e: Exception) {
            "Error deleting manifest: ${e.message}"
        }
    }

    suspend fun activateManifest(manifest: ModelManifest): OperationResult<ModelManifest> {
        val stored = ModelConfigStore.load(appContext)
        val config = StoredEngineConfig(
            modelPath = manifest.filePath,
            threads = stored?.threads ?: 6,
            contextLength = manifest.contextLength.takeIf { it > 0 } ?: (stored?.contextLength ?: 4096),
            gpuLayers = stored?.gpuLayers ?: 20,
            useVulkan = stored?.useVulkan ?: true,
        )
        return loadModel(config)
    }

    suspend fun verifyManifest(manifest: ModelManifest): Boolean = manifestService.verify(manifest)
    fun getManifestsFlow(): Flow<List<ModelManifest>> = manifestService.manifestsFlow()
    suspend fun getActiveManifest(): ModelManifest? {
        val config = ModelConfigStore.load(appContext) ?: return null
        return manifestService.list().firstOrNull { it.filePath == config.modelPath }
    }
    fun getDetectedTemplateId(manifest: ModelManifest): String? = manifestService.detectedTemplateId(manifest)

    // --------------------- KV cache (compressed snapshots) ---------------------
    suspend fun restoreKv(chatId: Long): Boolean = withContext(Dispatchers.IO) {
        val file = stateFile(chatId)
        if (!file.exists()) {
            recordMiss()
            return@withContext false
        }
        val payload = runCatching { file.readBytes() }.getOrNull()
        if (payload == null) {
            recordMiss()
            return@withContext false
        }
        val snapshot = decompress(payload)
        val restored = runCatching { EngineRuntime.restoreState(snapshot) }.getOrDefault(false)
        if (restored) {
            recordHit(chatId, file)
        } else {
            removeEntry(chatId, deleteFile = true)
            recordMiss()
        }
        restored
    }

    suspend fun captureKv(chatId: Long) = withContext(Dispatchers.IO) {
        val snapshot: ByteArray = EngineRuntime.captureState() ?: return@withContext
        val packed = compress(snapshot)
        if (packed.size.toLong() > maxCacheBytes) {
            recordMiss()
            return@withContext // snapshot too large, skip caching
        }
        val file = stateFile(chatId)
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(cacheDir, "${file.name}.tmp")
            tmp.outputStream().use { it.write(packed) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            recordHit(chatId, file)
        }.onFailure {
            removeEntry(chatId, deleteFile = true)
            recordMiss()
        }
    }

    suspend fun clearKv(chatId: Long) = withContext(Dispatchers.IO) {
        removeEntry(chatId, deleteFile = true)
    }

    suspend fun clearAllKv() = withContext(Dispatchers.IO) {
        cacheLock.withLock {
            cacheIndex.clear()
            cacheBytes = 0
            cacheHits.set(0)
            cacheMisses.set(0)
            cacheEvictions.set(0)
            updateStatsLocked()
        }
        cacheDir.listFiles()?.forEach { runCatching { it.delete() } }
    }

    private fun compress(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        val buffer = ByteArrayOutputStream(input.size)
        GZIPOutputStream(buffer).use { it.write(input) }
        val compressed = buffer.toByteArray()
        return if (compressed.size >= input.size) input else compressed
    }

    private fun decompress(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        return runCatching {
            GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
        }.getOrElse { input }
    }

    private fun recordHit(chatId: Long, file: File) {
        val size = file.length().coerceAtLeast(0L)
        cacheLock.withLock {
            val previous = cacheIndex.put(chatId, CacheEntry(file, size))
            if (previous != null) {
                cacheBytes -= previous.size
            }
            cacheBytes += size
            cacheHits.incrementAndGet()
            trimCacheLocked()
            if (cacheBytes < 0) cacheBytes = 0
            updateStatsLocked()
        }
    }

    private fun removeEntry(chatId: Long, deleteFile: Boolean) {
        val target = cacheLock.withLock {
            val entry = cacheIndex.remove(chatId)
            if (entry != null) {
                cacheBytes -= entry.size
                if (cacheBytes < 0) cacheBytes = 0
                entry.file
            } else null
        }
        if (deleteFile) {
            val file = target ?: stateFile(chatId)
            runCatching { if (file.exists()) file.delete() }
        }
        cacheLock.withLock { updateStatsLocked() }
    }

    private fun trimCacheLocked() {
        val iterator = cacheIndex.entries.iterator()
        while ((cacheIndex.size > maxCacheFiles || cacheBytes > maxCacheBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            cacheBytes -= eldest.value.size
            if (cacheBytes < 0) cacheBytes = 0
            runCatching { if (eldest.value.file.exists()) eldest.value.file.delete() }
            iterator.remove()
            cacheEvictions.incrementAndGet()
            Logger.i("KV eviction for chat ${eldest.key}")
        }
        updateStatsLocked()
    }

    companion object {
        private const val DEFAULT_MAX_CACHE_FILES = 50
        private val DEFAULT_MAX_CACHE_BYTES = 500L * 1024L * 1024L
    }

    private fun recordMiss() {
        cacheMisses.incrementAndGet()
        cacheLock.withLock { updateStatsLocked() }
    }

    private fun updateStatsLocked() {
        _cacheStats.value = CacheStats(
            hits = cacheHits.get(),
            misses = cacheMisses.get(),
            evictions = cacheEvictions.get(),
            bytes = cacheBytes.coerceAtLeast(0)
        )
    }

    data class CacheStats(
        val hits: Long = 0,
        val misses: Long = 0,
        val evictions: Long = 0,
        val bytes: Long = 0
    )

    // --------------------- Streaming with cache integration ---------------------
    fun streamWithCache(
        chatId: Long,
        prompt: String,
        systemPrompt: String?,
        template: String?,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        stop: Array<String>
    ): kotlinx.coroutines.flow.Flow<EngineStreamEvent> {
        return flow {
            // restore best-effort
            val restored = runCatching { restoreKv(chatId) }.getOrDefault(false)
            if (!restored) runCatching { EngineRuntime.clearState(false) }
            // delegate streaming
            StreamingEngine.stream(
                prompt = prompt,
                systemPrompt = systemPrompt,
                template = template,
                temperature = temperature,
                topP = topP,
                topK = topK,
                maxTokens = maxTokens,
                stop = stop
            ).onEach { event ->
                emit(event)
                if (event is EngineStreamEvent.Terminal) {
                    val success = !event.metrics.isError
                    runCatching {
                        if (success) captureKv(chatId) else clearKv(chatId)
                    }
                }
            }.collect { }
        }
    }
}
