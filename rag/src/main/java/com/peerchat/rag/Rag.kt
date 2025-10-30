package com.peerchat.rag

import com.peerchat.data.db.Embedding
import com.peerchat.data.db.RagChunk
import com.peerchat.data.db.Document
import com.peerchat.data.db.PeerDatabase
import com.peerchat.engine.EngineNative
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object RagService {
    // Tokenizer cache for performance optimization
    private val tokenCountCache = ConcurrentHashMap<String, Int>()
    private val cacheLock = ReentrantReadWriteLock()
    private const val MAX_CACHE_SIZE = 10000

    // Clear cache periodically to prevent memory bloat
    private fun evictCacheIfNeeded() {
        if (tokenCountCache.size > MAX_CACHE_SIZE) {
            cacheLock.write {
                // Keep only the most recent half to maintain some locality
                val entries = tokenCountCache.entries.take(MAX_CACHE_SIZE / 2)
                tokenCountCache.clear()
                tokenCountCache.putAll(entries)
            }
        }
    }

    // Cached token counting with fallback
    private fun countTokensCached(text: String): Int {
        val cacheKey = sha256(text).take(16) // Use hash prefix as key

        cacheLock.read {
            tokenCountCache[cacheKey]?.let { return it }
        }

        val count = runCatching { EngineNative.countTokens(text) }.getOrElse {
            (text.length / 4).coerceAtLeast(1)
        }

        cacheLock.write {
            tokenCountCache[cacheKey] = count
        }

        evictCacheIfNeeded()
        return count
    }

    suspend fun indexDocument(db: PeerDatabase, doc: Document, text: String, maxChunkTokens: Int = 512, overlapTokens: Int = 64) {
        val chunks = optimizedTokenizerChunks(text, maxChunkTokens, overlapTokens)
        if (chunks.isEmpty()) return

        // Batch embed all chunks at once for better performance
        val chunkTexts = chunks.map { it.text }.toTypedArray()
        val embeddings = EngineNative.embed(chunkTexts)

        for (i in chunks.indices) {
            val chunk = chunks[i]
            val vec = embeddings[i]
            val norm = vectorNorm(vec)
            val embId = db.embeddingDao().upsert(
                Embedding(
                    docId = doc.id,
                    chatId = null,
                    textHash = sha256(chunk.text),
                    vector = floatArrayToBytes(vec),
                    dim = vec.size,
                    norm = norm,
                    createdAt = System.currentTimeMillis()
                )
            )
            db.ragDao().insertChunk(
                RagChunk(
                    docId = doc.id,
                    start = chunk.start,
                    end = chunk.end,
                    text = chunk.text,
                    tokenCount = chunk.tokenCount,
                    embeddingId = embId
                )
            )
        }
    }

    suspend fun retrieve(db: PeerDatabase, query: String, topK: Int = 6): List<RagChunk> {
        return retrieveHybrid(db, query, topK)
    }

    suspend fun retrieveHybrid(
        db: PeerDatabase,
        query: String,
        topK: Int = 6,
        alphaSemantic: Float = 0.7f,
        alphaLexical: Float = 0.3f,
    ): List<RagChunk> {
        val qv = EngineNative.embed(arrayOf(query)).firstOrNull() ?: return emptyList()

        // Get all embeddings in one query for better performance
        val allEmbeddings = db.embeddingDao().listAll()

        // Pre-filter embeddings and calculate semantic scores
        val semanticScores = HashMap<Long, Float>()
        val validEmbeddings = allEmbeddings.filter { it.dim > 0 && it.vector.isNotEmpty() }

        // Batch cosine similarity calculations for better cache performance
        for (emb in validEmbeddings) {
            val v = bytesToFloatArray(emb.vector)
            val s = cosine(qv, v, emb.norm)
            semanticScores[emb.id] = s
        }

        // Get lexical matches (FTS search)
        val lexicalMatches = db.ragDao().searchChunks(query, limit = topK * 4)
        val lexicalScores = HashMap<Long, Float>()

        for ((rank, ch) in lexicalMatches.withIndex()) {
            val score = (lexicalMatches.size - rank).toFloat() / lexicalMatches.size.toFloat()
            lexicalScores[ch.embeddingId ?: -1L] = score
        }

        // Fuse semantic and lexical scores
        val fused = ArrayList<Pair<Long, Float>>()
        val allIds = HashSet<Long>()
        allIds.addAll(semanticScores.keys)
        allIds.addAll(lexicalScores.keys)

        for (id in allIds) {
            val semanticScore = semanticScores[id] ?: 0f
            val lexicalScore = lexicalScores[id] ?: 0f
            val combinedScore = semanticScore * alphaSemantic + lexicalScore * alphaLexical
            fused.add(id to combinedScore)
        }

        // Sort by score and take top K
        fused.sortByDescending { it.second }
        val topEmbeddingIds = fused.take(topK).map { it.first }

        // Batch fetch chunks for the top results
        if (topEmbeddingIds.isEmpty()) return emptyList()

        val chunks = db.ragDao().getByEmbeddingIds(topEmbeddingIds.filter { it > 0 })
        return chunks.sortedBy { chunk ->
            // Sort by the combined score
            val embId = chunk.embeddingId ?: -1L
            fused.find { it.first == embId }?.second ?: 0f
        }.reversed() // descending order
    }

    fun buildContext(chunks: List<RagChunk>, maxChars: Int = 4000): String {
        val sb = StringBuilder()
        for (c in chunks) {
            if (sb.length + c.text.length + 32 > maxChars) break
            sb.append("<doc>\n")
            sb.append(c.text)
            sb.append("\n</doc>\n\n")
        }
        return sb.toString()
    }
}

private data class ChunkInfo(val text: String, val start: Int, val end: Int, val tokenCount: Int)

    // Optimized chunking using sliding window instead of binary search
    private fun optimizedTokenizerChunks(text: String, maxTokens: Int, overlapTokens: Int): List<ChunkInfo> {
        if (text.isEmpty()) return emptyList()

        val out = ArrayList<ChunkInfo>()
        var pos = 0
        val totalChars = text.length

        // Estimate character-to-token ratio (typically 3-4 chars per token)
        val estimatedCharsPerToken = 4

        while (pos < totalChars) {
            // Estimate chunk size based on token limit
            val estimatedChunkSize = maxTokens * estimatedCharsPerToken
            var chunkEnd = min(totalChars, pos + estimatedChunkSize)

            // If we're near the end, take what's left
            if (chunkEnd >= totalChars - 100) {
                chunkEnd = totalChars
            } else {
                // Find a good breaking point by expanding until we hit token limit
                var currentEnd = pos + (estimatedChunkSize / 2) // Start from middle
                val step = estimatedCharsPerToken * 10 // Step by ~10 tokens

                while (currentEnd < chunkEnd && currentEnd < totalChars) {
                    val candidate = text.substring(pos, currentEnd)
                    val tokens = countTokensCached(candidate)

                    if (tokens >= maxTokens) {
                        // We overshot, back up to previous good position
                        chunkEnd = currentEnd
                        break
                    }
                    currentEnd += step
                }

                // If we didn't find a good break, use the estimated size
                if (currentEnd >= chunkEnd) {
                    chunkEnd = min(totalChars, pos + estimatedChunkSize)
                }
            }

            // Ensure we don't go beyond text bounds
            chunkEnd = min(chunkEnd, totalChars)

            val chunkText = text.substring(pos, chunkEnd)
            val actualTokens = countTokensCached(chunkText)

            // If chunk is too small and we're not at the end, extend it
            if (actualTokens < maxTokens / 4 && chunkEnd < totalChars) {
                val extendedEnd = min(totalChars, chunkEnd + (maxTokens * estimatedCharsPerToken / 2))
                val extendedText = text.substring(pos, extendedEnd)
                val extendedTokens = countTokensCached(extendedText)

                if (extendedTokens <= maxTokens) {
                    out.add(ChunkInfo(extendedText, pos, extendedEnd, extendedTokens))
                    pos = max(pos + 1, extendedEnd - overlapTokens * estimatedCharsPerToken)
                    continue
                }
            }

            out.add(ChunkInfo(chunkText, pos, chunkEnd, actualTokens))

            if (chunkEnd >= totalChars) break

            // Calculate next position with overlap
            val overlapChars = overlapTokens * estimatedCharsPerToken
            pos = max(pos + 1, chunkEnd - overlapChars)
        }

        return out
    }

private fun sha256(s: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val b = md.digest(s.toByteArray())
    return b.joinToString("") { "%02x".format(it) }
}

private fun vectorNorm(v: FloatArray): Float {
    var sum = 0.0
    for (x in v) sum += (x * x)
    return sqrt(sum).toFloat()
}

private fun cosine(q: FloatArray, v: FloatArray, vNorm: Float): Float {
    val n = min(q.size, v.size)
    var dot = 0.0
    for (i in 0 until n) dot += (q[i] * v[i])
    val qn = vectorNorm(q)
    val denom = (qn * (if (vNorm > 0) vNorm else vectorNorm(v)))
    return if (denom > 0f) (dot / denom).toFloat() else 0f
}

private fun floatArrayToBytes(v: FloatArray): ByteArray {
    val bb = java.nio.ByteBuffer.allocate(v.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    for (x in v) bb.putFloat(x)
    return bb.array()
}

private fun bytesToFloatArray(b: ByteArray): FloatArray {
    val bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(b.size / 4)
    for (i in out.indices) out[i] = bb.getFloat()
    return out
}

