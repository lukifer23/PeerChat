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
import kotlinx.coroutines.flow.onCompletion
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

    // Performance optimizations
    private val gpuMemoryManager = GpuMemoryManager(appContext)
    private val kvCacheOptimizer = KvCacheOptimizer(maxCacheSizeBytes = maxCacheBytes, maxCacheEntries = maxCacheFiles)

    // Legacy KV cache (keeping for compatibility, but optimized version preferred)
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
    
    // Validation cache (path -> (lastModified, validation result))
    private val validationCache = mutableMapOf<String, Pair<Long, OperationResult<ModelManifest>>>()
    private val validationCacheLock = ReentrantLock()
    private val VALIDATION_CACHE_TTL_MS = 60_000L // 1 minute cache

    // --------------------- Model validation ---------------------

    /**
     * Validates a model file before attempting to load it.
     * Performs basic sanity checks on file integrity and format.
     * Results are cached for 1 minute to avoid redundant file I/O.
     */
    suspend fun validateModel(path: String): OperationResult<ModelManifest> = withContext(Dispatchers.IO) {
        val startNs = System.nanoTime()
        val file = File(path)
        val fileName = file.name.ifBlank { path.substringAfterLast('/', path) }
        
        // Check validation cache
        val now = System.currentTimeMillis()
        validationCacheLock.withLock {
            val cached = validationCache[path]
            if (cached != null && (now - cached.first) < VALIDATION_CACHE_TTL_MS) {
                val fileModified = file.lastModified()
                // Only use cache if file hasn't been modified
                if (fileModified == cached.first) {
                    Logger.d("validateModel: cache_hit", mapOf("file" to fileName))
                    return@withContext cached.second
                }
            }
        }

        fun failure(reason: String): OperationResult<ModelManifest> {
            Logger.e(
                "validateModel:failure",
                mapOf(
                    "file" to fileName,
                    "reason" to reason
                )
            )
            return OperationResult.Failure(reason)
        }

        val result = try {
            if (!file.exists()) {
                return@withContext failure("Model file does not exist: $path")
            }

            if (!file.canRead()) {
                return@withContext failure("Model file is not readable: $path")
            }

            val sizeBytes = file.length()
            val minSize = 1024 * 1024 // 1MB
            if (sizeBytes < minSize) {
                return@withContext failure("Model file too small (${sizeBytes} bytes), expected at least $minSize bytes")
            }

            val maxSize = 50L * 1024 * 1024 * 1024 // 50GB
            if (sizeBytes > maxSize) {
                return@withContext failure("Model file too large (${sizeBytes} bytes), maximum allowed is $maxSize bytes")
            }

            val metaSnapshot = EngineRuntime.currentModelMeta()?.takeIf { it.isNotBlank() }

            manifestService.ensureManifestFor(path, modelMetaJson = metaSnapshot)

            val manifest = manifestService.list().firstOrNull { it.filePath == path }
                ?: return@withContext failure("Model manifest not found. Try importing the model first.")

            val durationMs = (System.nanoTime() - startNs) / 1_000_000
            Logger.i(
                "validateModel:success",
                mapOf(
                    "file" to fileName,
                    "sizeBytes" to sizeBytes,
                    "durationMs" to durationMs,
                    "manifestId" to manifest.id,
                    "ctx" to manifest.contextLength
                )
            )

            OperationResult.Success(manifest)
        } catch (e: Exception) {
            failure("Model validation failed: ${e.message}")
        }
        
        // Cache validation result
        val fileModified = file.lastModified()
        validationCacheLock.withLock {
            validationCache[path] = fileModified to result
            // Clean up old cache entries periodically
            if (validationCache.size > 50) {
                validationCache.entries.removeIf { (now - it.value.first) > VALIDATION_CACHE_TTL_MS }
            }
        }
        
        result
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
            val validationResult = validateModel(config.modelPath)
            if (validationResult is OperationResult.Failure) {
                return@withContext OperationResult.Failure(validationResult.error)
            }

            val manifest = (validationResult as OperationResult.Success<ModelManifest>).data
            val attempts = buildLoadAttempts(config, manifest)
            val failureReasons = mutableListOf<String>()

            Logger.i(
                "loadModel:start",
                mapOf(
                    "file" to File(config.modelPath).name,
                    "threads" to config.threads,
                    "ctx" to config.contextLength,
                    "gpuLayers" to config.gpuLayers,
                    "attempts" to attempts.size
                )
            )

            for ((index, attempt) in attempts.withIndex()) {
                var retries = 0
                var lastError: String? = null

                Logger.i(
                    "loadModel:attempt",
                    mapOf(
                        "file" to File(attempt.config.modelPath).name,
                        "reason" to attempt.reason,
                        "threads" to attempt.config.threads,
                        "ctx" to attempt.config.contextLength,
                        "gpuLayers" to attempt.config.gpuLayers,
                        "useVulkan" to attempt.config.useVulkan,
                        "index" to index
                    )
                )

                while (retries < MAX_LOAD_RETRIES) {
                    unloadInternal()
                    val loadStartTime = System.currentTimeMillis()
                    val loadResult = runCatching { EngineRuntime.load(attempt.config.toEngineConfig()) }
                    val loaded = loadResult.getOrDefault(false)

                    if (loaded) {
                        val loadTime = System.currentTimeMillis() - loadStartTime
                        Logger.i(
                            "Model loaded successfully",
                            mapOf(
                                "path" to attempt.config.modelPath,
                                "loadTimeMs" to loadTime,
                                "threads" to attempt.config.threads,
                                "context" to attempt.config.contextLength,
                                "gpuLayers" to attempt.config.gpuLayers,
                                "useVulkan" to attempt.config.useVulkan,
                                "attempt" to attempt.reason
                            )
                        )

                        ModelConfigStore.save(appContext, attempt.config)
                        val modelMeta = EngineRuntime.currentModelMeta()
                        withContext(Dispatchers.IO) {
                            manifestService.ensureManifestFor(
                                path = attempt.config.modelPath,
                                modelMetaJson = modelMeta,
                                isDefault = false
                            )
                        }
                        val finalManifest = manifestService.list().firstOrNull { it.filePath == attempt.config.modelPath }
                            ?: run {
                                kotlinx.coroutines.delay(100)
                                manifestService.list().firstOrNull { it.filePath == attempt.config.modelPath }
                            }
                        if (finalManifest != null) {
                            val baseMessage = "Model loaded in ${loadTime}ms"
                            val message = if (index == 0) {
                                baseMessage
                            } else {
                                "$baseMessage using ${attempt.readableReason()} fallback"
                            }
                            return@withContext OperationResult.Success(finalManifest, message)
                        } else {
                            ModelConfigStore.clear(appContext)
                            val failReason = "Model loaded but manifest not found"
                            Logger.e(
                                "loadModel:manifest_missing",
                                mapOf(
                                    "file" to File(attempt.config.modelPath).name,
                                    "durationMs" to loadTime
                                )
                            )
                            return@withContext OperationResult.Failure(failReason)
                        }
                    } else {
                        lastError = loadResult.exceptionOrNull()?.message
                            ?: (EngineRuntime.status.value as? EngineRuntime.EngineStatus.Error)?.message
                        retries++
                        if (retries < MAX_LOAD_RETRIES) {
                            val delayMs = (1000L * (1 shl retries)).coerceAtMost(10_000L)
                            kotlinx.coroutines.delay(delayMs)
                        }
                    }
                }

                val reason = lastError?.let { "${attempt.readableReason()}: $it" } ?: "${attempt.readableReason()}: unknown error"
                Logger.w(
                    "loadModel:attempt_failed",
                    mapOf(
                        "file" to File(attempt.config.modelPath).name,
                        "reason" to reason,
                        "retries" to retries
                    )
                )
                failureReasons += reason
            }

            ModelConfigStore.clear(appContext)
            val failureMessage =
                buildString {
                    append("Failed to load model after ${attempts.size} attempt(s)")
                    if (failureReasons.isNotEmpty()) {
                        append(" (")
                        append(failureReasons.joinToString("; "))
                        append(")")
                    }
                }
            Logger.e(
                "loadModel:exhausted",
                mapOf(
                    "file" to File(config.modelPath).name,
                    "message" to failureMessage
                )
            )
            OperationResult.Failure(failureMessage)
        } catch (e: Exception) {
            ModelConfigStore.clear(appContext)
            Logger.e(
                "loadModel:exception",
                mapOf(
                    "file" to File(config.modelPath).name,
                    "error" to (e.message ?: "unknown")
                ),
                e
            )
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
    suspend fun listManifests(): List<ModelManifest> = manifestService.list()
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
        
        // Use optimized compression from KvCacheOptimizer (non-blocking, already on IO dispatcher)
        // For large snapshots, use async compression to avoid blocking
        val packed = if (snapshot.size > 512 * 1024) { // > 512KB
            // Large snapshot - compress asynchronously to avoid blocking
            withContext(Dispatchers.Default) {
                compressOptimized(snapshot)
            }
        } else {
            // Small snapshot - compress synchronously (fast enough)
            compressOptimized(snapshot)
        }
        
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

    /**
     * Optimized compression using KvCacheOptimizer for better performance
     */
    private fun compressOptimized(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        
        val startTime = System.nanoTime()
        // Use KvCacheOptimizer for better compression (LZ4 for small, optimal for large)
        val (compressed, stats) = kvCacheOptimizer.compressOptimal(input)
        val compressionTimeMs = (System.nanoTime() - startTime) / 1_000_000
        
        val result = if (compressed.size >= input.size) input else compressed
        
        // Log compression effectiveness periodically (only for significant compressions)
        if (stats.compressionRatio < 0.9f) { // At least 10% compression
            val savedBytes = input.size - compressed.size
            Logger.d("KV Cache compression", mapOf(
                "originalSize" to input.size,
                "compressedSize" to compressed.size,
                "ratio" to "%.2f".format(stats.compressionRatio),
                "algorithm" to stats.algorithm,
                "savedBytes" to savedBytes,
                "compressionTimeMs" to compressionTimeMs
            ))
        }
        
        return result
    }
    
    /**
     * Legacy compression method (kept for compatibility)
     */
    private fun compress(input: ByteArray): ByteArray {
        return compressOptimized(input)
    }

    /**
     * Optimized decompression using KvCacheOptimizer
     */
    private fun decompress(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input
        return runCatching {
            // Try optimized decompression first
            kvCacheOptimizer.decompressOptimal(input) ?: run {
                // Fallback to legacy GZIP if optimized fails
                GZIPInputStream(ByteArrayInputStream(input)).use { it.readBytes() }
            }
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
        // Check if we need to evict
        val needsEviction = cacheIndex.size > maxCacheFiles || cacheBytes > maxCacheBytes
        if (!needsEviction) {
            updateStatsLocked()
            return
        }

        val initialSize = cacheIndex.size
        val initialBytes = cacheBytes
        var evictedCount = 0

        // Evict until we're under limits
        val iterator = cacheIndex.entries.iterator()
        while ((cacheIndex.size > maxCacheFiles || cacheBytes > maxCacheBytes) && iterator.hasNext()) {
            val eldest = iterator.next()
            val evictedSize = eldest.value.size
            cacheBytes -= evictedSize
            if (cacheBytes < 0) cacheBytes = 0
            runCatching { if (eldest.value.file.exists()) eldest.value.file.delete() }
            iterator.remove()
            cacheEvictions.incrementAndGet()
            evictedCount++
            Logger.d("KV Cache eviction", mapOf(
                "chatId" to eldest.key,
                "size" to evictedSize,
                "remainingEntries" to cacheIndex.size,
                "remainingBytes" to cacheBytes
            ))
        }

        // Log eviction summary
        if (evictedCount > 0) {
            Logger.i("KV Cache trim complete", mapOf(
                "evictedCount" to evictedCount,
                "initialSize" to initialSize,
                "finalSize" to cacheIndex.size,
                "initialBytes" to initialBytes,
                "finalBytes" to cacheBytes,
                "freedBytes" to (initialBytes - cacheBytes)
            ))
        }

        updateStatsLocked()
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
        val hitRate = if (total > 0) (stats.hits.toFloat() / total.toFloat() * 100f).toInt() else 0
        
        // Log when evictions occur, hit rate changes significantly, or cache size is large
        val shouldLog = stats.evictions > 0 || 
                       (total > 0 && (stats.hits % 10 == 0L || stats.misses % 10 == 0L)) ||
                       (stats.bytes > maxCacheBytes * 0.8) // Log when approaching limit
        
        if (shouldLog) {
            Logger.i(
                "KV Cache stats",
                mapOf(
                    "hits" to stats.hits,
                    "misses" to stats.misses,
                    "hitRate" to hitRate,
                    "evictions" to stats.evictions,
                    "bytes" to stats.bytes,
                    "maxBytes" to maxCacheBytes,
                    "bytesUsagePct" to ((stats.bytes.toFloat() / maxCacheBytes.toFloat() * 100f).toInt()),
                    "entries" to cacheIndex.size,
                    "maxEntries" to maxCacheFiles
                )
            )
        }
    }

    private suspend fun buildLoadAttempts(initial: StoredEngineConfig, manifest: ModelManifest): List<LoadAttempt> {
        val attempts = LinkedHashMap<StoredEngineConfig, String>()

        // Get GPU memory profile for intelligent layer allocation
        val gpuProfile = gpuMemoryManager.calculateOptimalLayers(manifest, initial.contextLength)

        // Primary attempt with intelligent GPU allocation
        val optimizedConfig = if (gpuProfile.canUseGpu) {
            val adaptiveLayers = gpuMemoryManager.getAdaptiveLayers(manifest, initial.gpuLayers, initial.contextLength)
            initial.copy(
                gpuLayers = adaptiveLayers,
                useVulkan = adaptiveLayers > 0
            )
        } else {
            initial.copy(gpuLayers = 0, useVulkan = false)
        }

        attempts[optimizedConfig] = "optimized"

        // Fallback attempts
        if (gpuProfile.canUseGpu && optimizedConfig.gpuLayers > 5) {
            // Try with fewer layers if we have many
            val reducedLayers = (optimizedConfig.gpuLayers / 2).coerceAtLeast(5)
            attempts[optimizedConfig.copy(gpuLayers = reducedLayers)] = "reduced_gpu_layers"
        }

        // CPU fallback
        attempts[optimizedConfig.copy(useVulkan = false, gpuLayers = 0)] = "cpu_fallback"

        Logger.i("buildLoadAttempts: generated attempts", mapOf(
            "attemptCount" to attempts.size,
            "gpuProfile" to gpuProfile.reasoning.take(100) + if (gpuProfile.reasoning.length > 100) "..." else "",
            "recommendedLayers" to gpuProfile.recommendedGpuLayers
        ))

        return attempts.map { LoadAttempt(it.key, it.value) }
    }

    private data class LoadAttempt(val config: StoredEngineConfig, val reason: String)

    private fun LoadAttempt.readableReason(): String = when (reason) {
        "optimized" -> "optimized configuration"
        "reduced_gpu_layers" -> "reduced GPU layers"
        "cpu_fallback" -> "CPU execution"
        else -> reason
    }

    companion object {
        private const val DEFAULT_MAX_CACHE_FILES = 50
        private val DEFAULT_MAX_CACHE_BYTES = 500L * 1024L * 1024L
        private const val MAX_LOAD_RETRIES = 3
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
            var emittedTerminal = false
            val restored = runCatching { restoreKv(chatId) }.getOrDefault(false)
            if (!restored) {
                runCatching { EngineRuntime.clearState(false) }
            }

            Logger.i(
                "streamWithCache: start",
                mapOf(
                    "chatId" to chatId,
                    "promptLength" to prompt.length,
                    "restoredKv" to restored,
                    "template" to template
                )
            )

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
                    emittedTerminal = true
                    val success = !event.metrics.isError
                    Logger.i(
                        "streamWithCache: terminal_event",
                        mapOf(
                            "chatId" to chatId,
                            "success" to success,
                            "promptTokens" to event.metrics.promptTokens,
                            "generationTokens" to event.metrics.generationTokens,
                            "ttfsMs" to event.metrics.ttfsMs,
                            "totalMs" to event.metrics.totalMs,
                            "stopReason" to event.metrics.stopReason
                        )
                    )
                    withContext(Dispatchers.IO) {
                        runCatching {
                            if (success) captureKv(chatId) else clearKv(chatId)
                        }
                    }
                }
            }.onCompletion { cause ->
                if (cause != null || !emittedTerminal) {
                    Logger.w(
                        "streamWithCache: onCompletion_cleanup",
                        mapOf(
                            "chatId" to chatId,
                            "cause" to (cause?.message ?: if (emittedTerminal) "missing_terminal" else "unknown")
                        ),
                        cause
                    )
                    withContext(Dispatchers.IO) {
                        runCatching { EngineRuntime.clearState(false) }
                        runCatching { clearKv(chatId) }
                    }
                }
            }.flowOn(Dispatchers.IO)

            emitAll(upstream)
        }
    }
}
