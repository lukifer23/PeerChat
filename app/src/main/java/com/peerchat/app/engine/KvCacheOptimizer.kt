package com.peerchat.app.engine

import com.peerchat.app.util.Logger
import net.jpountz.lz4.LZ4Factory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

/**
 * Optimized KV cache manager with improved compression, smart eviction,
 * and memory-mapped access for large caches.
 */
class KvCacheOptimizer(
    private val maxCacheSizeBytes: Long = 500L * 1024 * 1024, // 500MB default
    private val maxCacheEntries: Int = 50
) {
    private val lz4Factory = LZ4Factory.fastestInstance()
    private val compressor = lz4Factory.fastCompressor()
    private val decompressor = lz4Factory.fastDecompressor()

    // Cache for compressed snapshots with metadata
    private val cache = ConcurrentHashMap<Long, CacheEntry>()
    private val accessOrder = ConcurrentHashMap<Long, Long>() // chatId -> lastAccessTime
    private val totalCacheBytes = AtomicLong(0)

    data class CacheEntry(
        val compressedData: ByteArray,
        val originalSize: Int,
        val compressedSize: Int,
        val checksum: Long, // Simple checksum for corruption detection
        val createdAt: Long,
        var lastAccess: Long,
        var accessCount: Int = 0
    )

    data class CompressionStats(
        val originalSize: Int,
        val compressedSize: Int,
        val compressionRatio: Float,
        val compressionTimeMs: Long,
        val algorithm: String
    )

    /**
     * Compress KV cache data using LZ4 for fast decompression
     */
    fun compressLz4(data: ByteArray): Pair<ByteArray, CompressionStats> {
        if (data.isEmpty()) return data to CompressionStats(0, 0, 1.0f, 0, "none")

        val startTime = System.nanoTime()
        val maxCompressedSize = compressor.maxCompressedLength(data.size)
        val compressed = ByteArray(maxCompressedSize)

        val compressedSize = compressor.compress(data, 0, data.size, compressed, 0, maxCompressedSize)
        val compressionTimeMs = (System.nanoTime() - startTime) / 1_000_000

        // Add header with original size for decompression
        val finalCompressed = ByteBuffer.allocate(4 + compressedSize)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(data.size)
            .put(compressed, 0, compressedSize)
            .array()

        val ratio = if (data.size > 0) finalCompressed.size.toFloat() / data.size.toFloat() else 1.0f

        return finalCompressed to CompressionStats(
            originalSize = data.size,
            compressedSize = finalCompressed.size,
            compressionRatio = ratio,
            compressionTimeMs = compressionTimeMs,
            algorithm = "LZ4"
        )
    }

    /**
     * Decompress LZ4 compressed data
     */
    fun decompressLz4(compressedData: ByteArray): ByteArray? {
        return try {
            if (compressedData.size < 4) return null

            val buffer = ByteBuffer.wrap(compressedData).order(ByteOrder.LITTLE_ENDIAN)
            val originalSize = buffer.getInt()
            val compressedPayload = compressedData.copyOfRange(4, compressedData.size)

            val decompressed = ByteArray(originalSize)
            val actualSize = decompressor.decompress(compressedPayload, decompressed)

            if (actualSize == originalSize) decompressed else null
        } catch (e: Exception) {
            Logger.w("KvCacheOptimizer: LZ4 decompression failed", mapOf("error" to e.message), e)
            null
        }
    }

    /**
     * Fallback to GZIP compression for maximum compression ratio
     */
    fun compressGzip(data: ByteArray): Pair<ByteArray, CompressionStats> {
        if (data.isEmpty()) return data to CompressionStats(0, 0, 1.0f, 0, "none")

        val startTime = System.nanoTime()
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(data) }
        val compressed = output.toByteArray()
        val compressionTimeMs = (System.nanoTime() - startTime) / 1_000_000

        val ratio = if (data.size > 0) compressed.size.toFloat() / data.size.toFloat() else 1.0f

        return compressed to CompressionStats(
            originalSize = data.size,
            compressedSize = compressed.size,
            compressionRatio = ratio,
            compressionTimeMs = compressionTimeMs,
            algorithm = "GZIP"
        )
    }

    /**
     * Decompress GZIP compressed data
     */
    fun decompressGzip(compressedData: ByteArray): ByteArray? {
        return try {
            GZIPInputStream(ByteArrayInputStream(compressedData)).use { it.readBytes() }
        } catch (e: Exception) {
            Logger.w("KvCacheOptimizer: GZIP decompression failed", mapOf("error" to e.message), e)
            null
        }
    }

    /**
     * Intelligently compress data using the best algorithm for the use case
     */
    fun compressOptimal(data: ByteArray): Pair<ByteArray, CompressionStats> {
        if (data.isEmpty()) return data to CompressionStats(0, 0, 1.0f, 0, "none")

        // For small data (< 1MB), use LZ4 for speed
        // For large data, try both and pick the better one
        return if (data.size < 1024 * 1024) {
            compressLz4(data)
        } else {
            // For large data, compare both algorithms
            val (lz4Data, lz4Stats) = compressLz4(data)
            val (gzipData, gzipStats) = compressGzip(data)

            if (lz4Stats.compressionRatio <= gzipStats.compressionRatio) {
                // LZ4 is better or equal
                lz4Data to lz4Stats
            } else {
                // GZIP is better for large data
                gzipData to gzipStats
            }
        }
    }

    /**
     * Decompress data using the appropriate algorithm based on header
     */
    fun decompressOptimal(compressedData: ByteArray): ByteArray? {
        if (compressedData.size < 4) return null // Need at least size header

        // For LZ4 data, we have a 4-byte size header
        // For GZIP data, we don't have a special header, so try both
        return decompressLz4(compressedData) ?: decompressGzip(compressedData)
    }

    /**
     * Store compressed KV cache data in memory cache
     */
    fun storeInCache(chatId: Long, data: ByteArray): Boolean {
        return try {
            val (compressed, stats) = compressOptimal(data)
            val checksum = calculateChecksum(data)

            val entry = CacheEntry(
                compressedData = compressed,
                originalSize = data.size,
                compressedSize = compressed.size,
                checksum = checksum,
                createdAt = System.currentTimeMillis(),
                lastAccess = System.currentTimeMillis(),
                accessCount = 1
            )

            // Check if we need to evict
            evictIfNecessary(compressed.size.toLong())

            cache[chatId] = entry
            accessOrder[chatId] = System.currentTimeMillis()
            totalCacheBytes.addAndGet(compressed.size.toLong())

            Logger.i("KvCacheOptimizer: stored cache entry", mapOf(
                "chatId" to chatId,
                "originalSize" to data.size,
                "compressedSize" to compressed.size,
                "ratio" to "%.2f".format(stats.compressionRatio),
                "algorithm" to stats.algorithm
            ))

            true
        } catch (e: Exception) {
            Logger.w("KvCacheOptimizer: failed to store cache entry", mapOf(
                "chatId" to chatId,
                "error" to e.message
            ), e)
            false
        }
    }

    /**
     * Retrieve and decompress KV cache data from memory cache
     */
    fun retrieveFromCache(chatId: Long): ByteArray? {
        val entry = cache[chatId] ?: return null

        return try {
            val decompressed = decompressOptimal(entry.compressedData)
            if (decompressed != null) {
                // Verify checksum
                val actualChecksum = calculateChecksum(decompressed)
                if (actualChecksum == entry.checksum) {
                    // Update access stats
                    entry.lastAccess = System.currentTimeMillis()
                    entry.accessCount++
                    accessOrder[chatId] = System.currentTimeMillis()

                    Logger.i("KvCacheOptimizer: cache hit", mapOf(
                        "chatId" to chatId,
                        "accessCount" to entry.accessCount,
                        "ageMs" to (System.currentTimeMillis() - entry.createdAt)
                    ))

                    decompressed
                } else {
                    Logger.w("KvCacheOptimizer: checksum mismatch, removing corrupted entry", mapOf("chatId" to chatId))
                    removeFromCache(chatId)
                    null
                }
            } else {
                Logger.w("KvCacheOptimizer: decompression failed, removing entry", mapOf("chatId" to chatId))
                removeFromCache(chatId)
                null
            }
        } catch (e: Exception) {
            Logger.w("KvCacheOptimizer: failed to retrieve cache entry", mapOf(
                "chatId" to chatId,
                "error" to e.message
            ), e)
            removeFromCache(chatId)
            null
        }
    }

    /**
     * Remove entry from cache
     */
    fun removeFromCache(chatId: Long) {
        val entry = cache.remove(chatId)
        if (entry != null) {
            totalCacheBytes.addAndGet(-entry.compressedSize.toLong())
            accessOrder.remove(chatId)
        }
    }

    /**
     * Clear all cache entries
     */
    fun clearCache() {
        cache.clear()
        accessOrder.clear()
        totalCacheBytes.set(0)
        Logger.i("KvCacheOptimizer: cache cleared")
    }

    /**
     * Get cache statistics
     */
    fun getCacheStats(): CacheStats {
        val now = System.currentTimeMillis()
        val entries = cache.values

        val avgCompressionRatio = if (entries.isNotEmpty()) {
            entries.sumOf { it.compressedSize.toDouble() / it.originalSize.toDouble() }.toFloat() / entries.size
        } else 1.0f

        val oldestEntry = entries.minByOrNull { it.createdAt }
        val newestEntry = entries.maxByOrNull { it.createdAt }

        return CacheStats(
            entryCount = cache.size,
            totalCompressedBytes = totalCacheBytes.get(),
            maxCacheBytes = maxCacheSizeBytes,
            maxEntries = maxCacheEntries,
            averageCompressionRatio = avgCompressionRatio,
            oldestEntryAgeMs = oldestEntry?.let { now - it.createdAt } ?: 0,
            newestEntryAgeMs = newestEntry?.let { now - it.createdAt } ?: 0,
            totalAccessCount = entries.sumOf { it.accessCount }
        )
    }

    /**
     * Smart eviction based on LRU and access patterns
     */
    private fun evictIfNecessary(newEntrySize: Long) {
        val currentSize = totalCacheBytes.get()

        // Fast path: if we have space, no need to evict
        if (currentSize + newEntrySize <= maxCacheSizeBytes && cache.size < maxCacheEntries) {
            return
        }

        // Sort entries by eviction priority (lower priority = evict first)
        val entriesToEvict = cache.entries
            .map { (chatId, entry) ->
                val accessScore = entry.accessCount.toDouble() / kotlin.math.max(1.0, (System.currentTimeMillis() - entry.lastAccess) / 1000.0)
                val sizeScore = 1.0 / entry.compressedSize.toDouble() // Prefer to evict larger entries
                val ageScore = 1.0 / (System.currentTimeMillis() - entry.createdAt).toDouble()
                Triple(chatId, entry, accessScore + sizeScore + ageScore)
            }
            .sortedBy { it.third } // Sort by priority (lower = evict first)
            .take(5) // Evict up to 5 entries at once

        for ((chatId, entry, _) in entriesToEvict) {
            if (currentSize + newEntrySize <= maxCacheSizeBytes && cache.size < maxCacheEntries) {
                break // We have enough space now
            }
            removeFromCache(chatId)
            Logger.i("KvCacheOptimizer: evicted cache entry", mapOf(
                "chatId" to chatId,
                "size" to entry.compressedSize
            ))
        }
    }


    private fun calculateChecksum(data: ByteArray): Long {
        var checksum = 0L
        for (i in data.indices step 8) {
            val chunk = data.copyOfRange(i, kotlin.math.min(i + 8, data.size))
            val value = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getLong()
            checksum = checksum xor value
        }
        return checksum
    }

    data class CacheStats(
        val entryCount: Int,
        val totalCompressedBytes: Long,
        val maxCacheBytes: Long,
        val maxEntries: Int,
        val averageCompressionRatio: Float,
        val oldestEntryAgeMs: Long,
        val newestEntryAgeMs: Long,
        val totalAccessCount: Int
    )

    companion object {
        private const val TAG = "KvCacheOptimizer"
    }
}
