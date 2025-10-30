package com.peerchat.app.engine

import android.content.Context
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class ModelStateCache(private val context: Context) {
    private val cacheDir: File by lazy {
        File(context.filesDir, "kv_cache").apply { if (!exists()) mkdirs() }
    }

    // LRU cache management
    private val accessTimes = ConcurrentHashMap<Long, Long>()
    private val accessCounter = AtomicLong(0)
    private val maxCacheSize = 50 // Maximum number of cached states
    private val maxCacheSizeBytes = 500 * 1024 * 1024L // 500MB max

    private fun stateFile(chatId: Long): File = File(cacheDir, "chat_$chatId.kvc")

    // Simple LZ4-like compression (byte run-length encoding)
    private fun compress(data: ByteArray): ByteArray {
        if (data.size < 100) return data // Don't compress small data

        val compressed = mutableListOf<Byte>()
        var i = 0
        while (i < data.size) {
            var count = 1
            val current = data[i]

            // Count consecutive same bytes (max 255)
            while (i + count < data.size && data[i + count] == current && count < 255) {
                count++
            }

            if (count >= 3) {
                // Use RLE: negative count + byte
                compressed.add((-count).toByte())
                compressed.add(current)
                i += count
            } else {
                // Use literal: positive count + bytes
                val literalCount = minOf(count, 127) // Max 127 for literals
                compressed.add(literalCount.toByte())
                for (j in 0 until literalCount) {
                    compressed.add(data[i + j])
                }
                i += literalCount
            }
        }
        return compressed.toByteArray()
    }

    private fun decompress(data: ByteArray): ByteArray {
        if (data.size < 100) return data // Assume uncompressed

        val decompressed = mutableListOf<Byte>()
        var i = 0
        while (i < data.size) {
            val count = data[i].toInt()
            i++

            if (count < 0) {
                // RLE: negative count means repeat next byte
                val repeatCount = -count
                val byte = data[i]
                i++
                repeat(repeatCount) {
                    decompressed.add(byte)
                }
            } else {
                // Literal: positive count means copy next N bytes
                for (j in 0 until count) {
                    decompressed.add(data[i + j])
                }
                i += count
            }
        }
        return decompressed.toByteArray()
    }

    private fun evictLRUIfNeeded() {
        val currentTime = accessCounter.incrementAndGet()

        // Check file count
        val cacheFiles = cacheDir.listFiles() ?: return
        if (cacheFiles.size >= maxCacheSize) {
            // Sort by access time (oldest first) and delete oldest
            val filesWithTime = cacheFiles.mapNotNull { file ->
                val chatId = file.name.removePrefix("chat_").removeSuffix(".kvc").toLongOrNull()
                chatId?.let { id ->
                    val accessTime = accessTimes[id] ?: 0L
                    Triple(file, id, accessTime)
                }
            }.sortedBy { it.third } // Sort by access time (oldest first)

            // Remove oldest 20% of files
            val toRemove = filesWithTime.take((cacheFiles.size * 0.2).toInt())
            toRemove.forEach { (file, id, _) ->
                file.delete()
                accessTimes.remove(id)
            }
        }

        // Check total size
        var totalSize = 0L
        cacheFiles.forEach { totalSize += it.length() }
        if (totalSize > maxCacheSizeBytes) {
            // Sort by access time and remove until under limit
            val filesWithTime = cacheFiles.mapNotNull { file ->
                val chatId = file.name.removePrefix("chat_").removeSuffix(".kvc").toLongOrNull()
                chatId?.let { id ->
                    val accessTime = accessTimes[id] ?: 0L
                    Triple(file, id, accessTime)
                }
            }.sortedBy { it.third }

            var currentSize = totalSize
            for ((file, id, _) in filesWithTime) {
                if (currentSize <= maxCacheSizeBytes * 0.8) break
                currentSize -= file.length()
                file.delete()
                accessTimes.remove(id)
            }
        }
    }

    suspend fun restore(chatId: Long): Boolean = withContext(Dispatchers.IO) {
        val file = stateFile(chatId)
        if (!file.exists()) return@withContext false

        val compressedBytes = runCatching { file.readBytes() }.getOrNull() ?: ByteArray(0)
        if (compressedBytes.isEmpty()) return@withContext false

        // Update access time for LRU
        accessTimes[chatId] = accessCounter.incrementAndGet()

        val bytes = decompress(compressedBytes)
        EngineRuntime.restoreState(bytes)
    }

    suspend fun capture(chatId: Long) = withContext(Dispatchers.IO) {
        val snapshot = EngineRuntime.captureState() ?: return@withContext
        val compressed = compress(snapshot)

        // Only cache if compression gives reasonable benefit
        if (compressed.size < snapshot.size * 1.1) {
            evictLRUIfNeeded()

            val file = stateFile(chatId)
            runCatching {
                file.parentFile?.mkdirs()
                file.writeBytes(compressed)
                // Update access time
                accessTimes[chatId] = accessCounter.incrementAndGet()
            }
        }
    }

    suspend fun clear(chatId: Long) = withContext(Dispatchers.IO) {
        stateFile(chatId).delete()
        accessTimes.remove(chatId)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
        accessTimes.clear()
    }
}
