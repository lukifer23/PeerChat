package com.peerchat.app.engine

import android.app.Application
import android.content.Context
import android.net.Uri
import com.peerchat.app.data.OperationResult
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineRuntime
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class ModelRepository(
    context: Context,
    private val manifestService: ModelManifestService,
) {
    private val appContext = context.applicationContext as Application

    // KV cache
    private val cacheDir: File by lazy { File(appContext.filesDir, "kv_cache").apply { if (!exists()) mkdirs() } }
    private fun stateFile(chatId: Long): File = File(cacheDir, "chat_${chatId}.kvc")
    private val accessTimes = ConcurrentHashMap<Long, Long>()
    private val accessCounter = AtomicLong(0)
    private val maxCacheFiles = 50
    private val maxCacheBytes = 500L * 1024L * 1024L

    // --------------------- Model lifecycle ---------------------
    suspend fun loadModel(config: StoredEngineConfig): OperationResult<ModelManifest> = withContext(Dispatchers.IO) {
        try {
            unloadInternal()
            val loaded = EngineRuntime.load(config.toEngineConfig())
            if (loaded) {
                ModelConfigStore.save(appContext, config)
                val manifest = manifestService.list().firstOrNull { it.filePath == config.modelPath }
                val updated = manifest?.let { manifestService.refreshManifest(it) }
                val finalManifest = updated ?: manifest
                if (finalManifest != null) OperationResult.Success(finalManifest, "Model loaded successfully")
                else OperationResult.Failure("Model loaded but manifest not found")
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
            val isActive = isManifestActive(manifest)
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
        if (!file.exists()) return@withContext false
        val compressed = runCatching { file.readBytes() }.getOrNull() ?: return@withContext false
        accessTimes[chatId] = accessCounter.incrementAndGet()
        val bytes = try { decompress(compressed) } catch (_: Exception) { compressed }
        try {
            EngineRuntime.restoreState(bytes)
        } catch (_: Exception) {
            runCatching { file.delete() }
            accessTimes.remove(chatId)
            false
        }
    }

    suspend fun captureKv(chatId: Long) = withContext(Dispatchers.IO) {
        val snapshot: ByteArray = EngineRuntime.captureState() ?: return@withContext
        val compressed = compress(snapshot)
        if (compressed.size < snapshot.size * 11 / 10) {
            evictIfNeeded()
            val file = stateFile(chatId)
            runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(compressed)
                accessTimes[chatId] = accessCounter.incrementAndGet()
            }
        }
    }

    suspend fun clearKv(chatId: Long) = withContext(Dispatchers.IO) {
        stateFile(chatId).delete()
        accessTimes.remove(chatId)
    }

    suspend fun clearAllKv() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
        accessTimes.clear()
    }

    private fun evictIfNeeded() {
        val files = cacheDir.listFiles() ?: return
        if (files.size >= maxCacheFiles) {
            val sorted = files.mapNotNull { f ->
                val id = f.name.removePrefix("chat_").removeSuffix(".kvc").toLongOrNull()
                id?.let { Triple(f, it, accessTimes[it] ?: 0L) }
            }.sortedBy { it.third }
            val removeCount = (files.size * 0.2).toInt().coerceAtLeast(1)
            sorted.take(removeCount).forEach { (f, id, _) ->
                f.delete(); accessTimes.remove(id)
            }
        }
        var total = 0L
        files.forEach { total += it.length() }
        if (total > maxCacheBytes) {
            val sorted = files.mapNotNull { f ->
                val id = f.name.removePrefix("chat_").removeSuffix(".kvc").toLongOrNull()
                id?.let { Triple(f, it, accessTimes[it] ?: 0L) }
            }.sortedBy { it.third }
            var cur = total
            for ((f, id, _) in sorted) {
                if (cur <= (maxCacheBytes * 8) / 10) break
                cur -= f.length(); f.delete(); accessTimes.remove(id)
            }
        }
    }

    private fun compress(input: ByteArray): ByteArray {
        if (input.size < 100) return input
        val out = ArrayList<Byte>(input.size)
        var i = 0
        while (i < input.size) {
            var count = 1
            val b = input[i]
            while (i + count < input.size && input[i + count] == b && count < 255) count++
            if (count >= 3) {
                out.add((-count).toByte()); out.add(b); i += count
            } else {
                val lit = minOf(count, 127)
                out.add(lit.toByte())
                for (j in 0 until lit) out.add(input[i + j])
                i += lit
            }
        }
        return out.toByteArray()
    }

    private fun decompress(input: ByteArray): ByteArray {
        if (input.size < 100) return input
        val out = ArrayList<Byte>(input.size)
        var i = 0
        while (i < input.size) {
            val count = input[i].toInt(); i++
            if (count < 0) {
                val rep = -count; val b = input[i]; i++
                repeat(rep) { out.add(b) }
            } else {
                for (j in 0 until count) out.add(input[i + j])
                i += count
            }
        }
        return out.toByteArray()
    }
}


