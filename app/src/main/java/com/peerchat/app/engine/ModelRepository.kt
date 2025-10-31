package com.peerchat.app.engine

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.PowerManager
import com.peerchat.app.data.OperationResult
import com.peerchat.app.util.Logger
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    // --------------------- Model validation ---------------------

    /**
     * Validates a model file before attempting to load it.
     * Performs basic sanity checks on file integrity and format.
     */
    suspend fun validateModel(path: String): OperationResult<ModelManifest> = withContext(Dispatchers.IO) {
        return@withContext try {
            val file = File(path)
            if (!file.exists()) {
                return@withContext OperationResult.Failure("Model file does not exist: $path")
            }

            if (!file.canRead()) {
                return@withContext OperationResult.Failure("Model file is not readable: $path")
            }

            // Check minimum file size (GGUF files are typically > 1MB)
            val minSize = 1024 * 1024 // 1MB
            if (file.length() < minSize) {
                return@withContext OperationResult.Failure("Model file too small (${file.length()} bytes), expected at least $minSize bytes")
            }

            // Check maximum file size (prevent loading extremely large files)
            val maxSize = 50L * 1024 * 1024 * 1024 // 50GB
            if (file.length() > maxSize) {
                return@withContext OperationResult.Failure("Model file too large (${file.length()} bytes), maximum allowed is $maxSize bytes")
            }

            // Try to get manifest to validate metadata
            val manifest = manifestService.list().firstOrNull { it.filePath == path }
                ?: return@withContext OperationResult.Failure("Model manifest not found. Try importing the model first.")

            OperationResult.Success(manifest)
        } catch (e: Exception) {
            OperationResult.Failure("Model validation failed: ${e.message}")
        }
    }

    /**
     * Preloads a model in the background for faster switching.
     * This validates the model and prepares it for loading without actually loading it.
     */
    suspend fun preloadModel(manifest: ModelManifest): OperationResult<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Just validate - actual preloading would require engine changes
            val validationResult = validateModel(manifest.filePath)
            when (validationResult) {
                is OperationResult.Success -> OperationResult.Success(Unit)
                is OperationResult.Failure -> OperationResult.Failure(validationResult.error)
            }
        } catch (e: Exception) {
            OperationResult.Failure("Model preload failed: ${e.message}")
        }
    }

    // --------------------- Performance monitoring ---------------------

    /**
     * Check if device has sufficient memory for model operations
     */
    private fun hasSufficientMemory(context: Context, requiredMemoryMB: Int = 512): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val availableMemoryMB = memoryInfo.availMem / (1024 * 1024)
        return availableMemoryMB >= requiredMemoryMB
    }

    /**
     * Check battery status for power-intensive operations
     */
    private fun isBatteryOk(context: Context): Boolean {
        val batteryManager = context.getSystemService(BATTERY_SERVICE) as BatteryManager
        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        // Require at least 20% battery for model operations
        return batteryLevel >= 20
    }

    /**
     * Check if device is plugged in (for intensive operations)
     */
    private fun isPluggedIn(context: Context): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: 0
        return plugged == BatteryManager.BATTERY_PLUGGED_AC ||
               plugged == BatteryManager.BATTERY_PLUGGED_USB ||
               plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
    }

    /**
     * Get performance recommendations based on device state
     */
    fun getPerformanceRecommendations(context: Context): List<String> {
        val recommendations = mutableListOf<String>()

        if (!hasSufficientMemory(context)) {
            recommendations.add("Low memory detected - consider closing other apps")
        }

        if (!isBatteryOk(context)) {
            recommendations.add("Low battery - connect charger for best performance")
        }

        if (!isPluggedIn(context)) {
            recommendations.add("Device not plugged in - performance may be limited")
        }

        return recommendations
    }

    // --------------------- Model lifecycle ---------------------
    suspend fun loadModel(config: StoredEngineConfig): OperationResult<ModelManifest> = withContext(Dispatchers.IO) {
        return@withContext try {
            // Validate model before attempting to load
            val validationResult = validateModel(config.modelPath)
            if (validationResult is OperationResult.Failure) {
                return@withContext OperationResult.Failure(validationResult.error)
            }

            unloadInternal()

            // Track load time
            val loadStartTime = System.currentTimeMillis()
            
            // Retry with exponential backoff
            var attempt = 0
            val maxRetries = 3
            var lastError: String? = null
            
            while (attempt < maxRetries) {
                val loaded = runCatching { EngineRuntime.load(config.toEngineConfig()) }.getOrElse {
                    lastError = it.message
                    false
                }
                
                if (loaded) {
                    val loadTime = System.currentTimeMillis() - loadStartTime
                    Logger.i(
                        "Model loaded successfully",
                        mapOf(
                            "path" to config.modelPath,
                            "loadTimeMs" to loadTime,
                            "threads" to config.threads,
                            "context" to config.contextLength,
                            "gpuLayers" to config.gpuLayers,
                            "useVulkan" to config.useVulkan
                        )
                    )
                    
                    ModelConfigStore.save(appContext, config)
                    // Get metadata from engine (should be ready from parallel detection)
                    val modelMeta = EngineRuntime.currentModelMeta()
                    // Ensure manifest in background to not block
                    withContext(Dispatchers.IO) {
                        manifestService.ensureManifestFor(
                            path = config.modelPath,
                            modelMetaJson = modelMeta,
                            isDefault = false
                        )
                    }
                    // Try to get existing manifest immediately, refresh will happen in background
                    val finalManifest = manifestService.list().firstOrNull { it.filePath == config.modelPath }
                        ?: run {
                            // Wait briefly if manifest doesn't exist yet (new import)
                            kotlinx.coroutines.delay(100)
                            manifestService.list().firstOrNull { it.filePath == config.modelPath }
                        }
                    if (finalManifest != null) {
                        return@withContext OperationResult.Success(finalManifest, "Model loaded in ${loadTime}ms")
                    } else {
                        ModelConfigStore.clear(appContext)
                        return@withContext OperationResult.Failure("Model loaded but manifest not found")
                    }
                }
                
                attempt++
                if (attempt < maxRetries) {
                    val delayMs = (1000L * (1 shl attempt)).coerceAtMost(10000L) // Max 10s
                    kotlinx.coroutines.delay(delayMs)
                }
            }
            
            ModelConfigStore.clear(appContext)
            OperationResult.Failure("Failed to load model after $maxRetries attempts${lastError?.let { ": $it" } ?: ""}")
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
        
        // Validate file size (minimum expected size for a valid snapshot)
        val fileSize = file.length()
        if (fileSize < 16L) {
            // Too small to be a valid snapshot, likely corrupted
            removeEntry(chatId, deleteFile = true)
            recordMiss()
            return@withContext false
        }
        
        val payload = runCatching { file.readBytes() }.getOrNull()
        if (payload == null || payload.isEmpty()) {
            removeEntry(chatId, deleteFile = true)
            recordMiss()
            return@withContext false
        }
        
        // Validate payload size matches file size
        if (payload.size != fileSize.toInt()) {
            removeEntry(chatId, deleteFile = true)
            recordMiss()
            return@withContext false
        }
        
        val snapshot = runCatching { decompress(payload) }.getOrNull()
        if (snapshot == null || snapshot.isEmpty()) {
            // Decompression failed or empty snapshot
            removeEntry(chatId, deleteFile = true)
            recordMiss()
            return@withContext false
        }
        
        // Validate snapshot size (should be reasonable)
        if (snapshot.size > maxCacheBytes) {
            // Snapshot too large, likely corrupted
            removeEntry(chatId, deleteFile = true)
            recordMiss()
            return@withContext false
        }
        
        val restored = runCatching { EngineRuntime.restoreState(snapshot) }.getOrDefault(false)
        if (restored) {
            recordHit(chatId, file)
        } else {
            // Restore failed, delete corrupted cache
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
        val stats = CacheStats(
            hits = cacheHits.get(),
            misses = cacheMisses.get(),
            evictions = cacheEvictions.get(),
            bytes = cacheBytes.coerceAtLeast(0)
        )
        _cacheStats.value = stats
        
        // Log cache stats periodically (only on significant changes)
        val total = stats.hits + stats.misses
        if (total > 0 && (stats.hits % 10 == 0L || stats.misses % 10 == 0L || stats.evictions > 0)) {
            Logger.i(
                "KV Cache stats",
                mapOf(
                    "hits" to stats.hits,
                    "misses" to stats.misses,
                    "hitRate" to if (total > 0) (stats.hits.toFloat() / total.toFloat() * 100f).toInt() else 0,
                    "evictions" to stats.evictions,
                    "bytes" to stats.bytes
                )
            )
        }
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
    ): Flow<EngineStreamEvent> {
        return flow {
            val restored = runCatching { restoreKv(chatId) }.getOrDefault(false)
            if (!restored) {
                runCatching { EngineRuntime.clearState(false) }
            }

            val upstream = StreamingEngine.stream(
                prompt = prompt,
                systemPrompt = systemPrompt,
                template = template,
                temperature = temperature,
                topP = topP,
                topK = topK,
                maxTokens = maxTokens,
                stop = stop
            ).onEach { event ->
                if (event is EngineStreamEvent.Terminal) {
                    val success = !event.metrics.isError
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (success) captureKv(chatId) else clearKv(chatId)
                        }
                    }
                }
            }.flowOn(Dispatchers.IO)

            emitAll(upstream)
        }
    }
}
