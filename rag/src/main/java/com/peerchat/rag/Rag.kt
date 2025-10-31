package com.peerchat.rag

import com.peerchat.data.db.Embedding
import com.peerchat.data.db.RagChunk
import com.peerchat.data.db.Document
import com.peerchat.data.db.PeerDatabase
import com.peerchat.engine.EngineNative
import com.peerchat.engine.EngineRuntime
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlinx.coroutines.runBlocking
import android.util.Log

// Token & embedding caches with LRU eviction under bounded memory
private val cacheLock = ReentrantReadWriteLock()
private val tokenCountCache = object : LinkedHashMap<String, Int>(256, 0.75f, true) {}
private val embeddingCache = object : LinkedHashMap<String, FloatArray>(256, 0.75f, true) {}
private var embeddingCacheBytes: Long = 0
private val docScoreCache = object : LinkedHashMap<Long, CandidateScore>(512, 0.75f, true) {}

private const val MAX_TOKEN_CACHE_ENTRIES = 5000
private const val MAX_EMBEDDING_CACHE_ENTRIES = 1500
private const val MAX_EMBEDDING_CACHE_BYTES: Long = 32L * 1024L * 1024L // ~32 MB
private const val DEFAULT_DOC_SCORE_ENTRIES = 2000
@Volatile
private var docScoreMaxEntries = DEFAULT_DOC_SCORE_ENTRIES

private data class CandidateScore(var score: Float, var updatedAtMs: Long)

data class RagAnnSnapshot(
    val numPlanes: Int,
    val bucketSize: Int,
    val fallbackSize: Int,
    val records: List<SerializedVector>
)

data class SerializedVector(val id: Long, val vector: FloatArray, val norm: Float)

private data class VectorRecord(val id: Long, val vector: FloatArray, val norm: Float)

fun interface RagEmbeddingIndex {
    fun query(query: FloatArray, topK: Int): List<Long>
}

private object EmbeddingIndexRegistry {
    private val delegate = AtomicReference<RagEmbeddingIndex?>(null)

    fun register(index: RagEmbeddingIndex) {
        delegate.set(index)
    }

    fun clear() {
        delegate.set(null)
    }

    fun query(query: FloatArray, topK: Int): List<Long> {
        val index = delegate.get() ?: return emptyList()
        return runCatching { index.query(query, topK) }.getOrDefault(emptyList())
    }
}

// Cached token counting with fallback
private fun countTokensCached(text: String): Int {
    val cacheKey = sha256(text).take(16) // Use hash prefix as key

    cacheLock.read {
        tokenCountCache[cacheKey]?.let { cached ->
            return cached
        }
    }

    val count = runCatching { EngineNative.countTokens(text) }.getOrElse {
        (text.length / 4).coerceAtLeast(1)
    }

    cacheLock.write {
        tokenCountCache[cacheKey] = count
        trimTokenCacheLocked()
    }
    return count
}

// Cached embedding computation to reduce redundant calculations with Android native fallback
private suspend fun embedCached(texts: Array<String>): Array<FloatArray> {
    val results = Array(texts.size) { FloatArray(0) }
    val uncachedTexts = mutableListOf<String>()
    val uncachedIndices = mutableListOf<Int>()

    // Check cache first
    cacheLock.read {
        texts.forEachIndexed { index, text ->
            val cacheKey = sha256(text).take(32)
            val cached = embeddingCache[cacheKey]
            if (cached != null) {
                results[index] = cached.copyOf()
            } else {
                uncachedTexts.add(text)
                uncachedIndices.add(index)
            }
        }
    }

    // Compute uncached embeddings in batch
    if (uncachedTexts.isNotEmpty()) {
        val uncachedEmbeddings = computeEmbeddingsWithFallback(uncachedTexts.toTypedArray())

        // Cache and assign results
        cacheLock.write {
            uncachedEmbeddings.forEachIndexed { batchIndex, embedding ->
                val textIndex = uncachedIndices[batchIndex]
                val text = texts[textIndex]
                val cacheKey = sha256(text).take(32)

                if (embedding.isNotEmpty()) {
                    putEmbeddingLocked(cacheKey, embedding)
                }
                results[textIndex] = embedding.copyOf()
            }
        }
    }

    return results
}

// Compute embeddings with multiple fallback strategies
private suspend fun computeEmbeddingsWithFallback(texts: Array<String>): Array<FloatArray> {
    val engineStatus = EngineRuntime.status.value

    // Try llama.cpp embeddings first if model is loaded
    if (engineStatus is EngineRuntime.EngineStatus.Loaded) {
        val nativeEmbeddings = runCatching {
            EngineNative.embed(texts)
        }.getOrDefault(Array(texts.size) { FloatArray(0) })

        // Check if we got valid embeddings from all texts
        val hasValidEmbeddings = nativeEmbeddings.all { it.isNotEmpty() }
        if (hasValidEmbeddings) {
            return nativeEmbeddings
        }

        Log.w("RagService", "native embeddings failed or incomplete, trying Android fallback. textsCount=${texts.size}, validEmbeddings=${nativeEmbeddings.count { it.isNotEmpty() }}")
    }

    // Fallback to Android native embeddings
    return tryAndroidNativeEmbeddings(texts)
}

// Android embedding service instance (set via configureAndroidEmbeddings)
private var androidEmbeddingService: (suspend (Array<String>) -> Array<FloatArray>)? = null

// Try Android native embeddings as fallback
private suspend fun tryAndroidNativeEmbeddings(texts: Array<String>): Array<FloatArray> {
    val service = androidEmbeddingService
    return if (service != null) {
        try {
            val embeddings = service(texts)
            // Verify embeddings are valid
            if (embeddings.isNotEmpty() && embeddings.all { it.isNotEmpty() }) {
                Log.i("RagService", "Android native embeddings generated successfully: texts=${texts.size}, dim=${embeddings.firstOrNull()?.size ?: 0}")
                return embeddings
            } else {
                Log.w("RagService", "Android native embeddings returned empty or invalid results, using TF-IDF fallback")
                generateBasicEmbeddings(texts)
            }
        } catch (e: Exception) {
            Log.w("RagService", "Android native embeddings failed: ${e.message}, using TF-IDF fallback")
            generateBasicEmbeddings(texts)
        }
    } else {
        Log.i("RagService", "Android native embeddings not configured, using TF-IDF fallback")
        generateBasicEmbeddings(texts)
    }
}

// Generate basic TF-IDF style embeddings as fallback
private fun generateBasicEmbeddings(texts: Array<String>): Array<FloatArray> {
    if (texts.isEmpty()) return emptyArray()

    // Create vocabulary from all texts
    val allTokens = texts.flatMap { tokenizeBasic(it) }.distinct()
    val vocabulary = allTokens.withIndex().associate { it.value to it.index }

    // Fixed embedding dimension (must match expected dimension)
    val dimension = 384 // Common embedding dimension

    return Array(texts.size) { textIndex ->
        val text = texts[textIndex]
        val tokens = tokenizeBasic(text)

        // Create basic TF-IDF style embedding
        val embedding = FloatArray(dimension)

        // Calculate term frequencies
        val termFreq = mutableMapOf<String, Int>()
        tokens.forEach { token ->
            termFreq[token] = termFreq.getOrDefault(token, 0) + 1
        }

        // Convert to embedding using hash-based projection
        termFreq.forEach { (token, freq) ->
            val vocabIndex = vocabulary[token] ?: 0
            val tf = freq.toFloat() / tokens.size.toFloat()

            // Use multiple hash functions for better distribution
            for (i in 0 until 8) {
                val hash = (token.hashCode() + i * 31) % dimension
                val index = if (hash < 0) hash + dimension else hash
                val idf = 1.0f / (1.0f + vocabIndex.toFloat() / vocabulary.size.toFloat())
                embedding[index] = (embedding[index] + tf * idf).coerceIn(-1.0f, 1.0f)
            }
        }

        // L2 normalize
        val norm = sqrt(embedding.map { it * it }.sum().toDouble()).toFloat()
        if (norm > 0.0f) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        embedding
    }
}

// Basic tokenization for fallback embeddings
private fun tokenizeBasic(text: String): List<String> {
    return text.lowercase()
        .replace(Regex("[^a-zA-Z0-9\\s]"), " ") // Remove punctuation
        .split(Regex("\\s+"))
        .filter { it.length > 2 && it.length < 20 } // Filter reasonable word lengths
        .distinct()
}


object RagService {

    data class DocScoreStats(val size: Int, val maxSize: Int)

    /**
     * Configure Android native embedding service for fallback embeddings.
     * Call this during app initialization to enable Android native embeddings.
     * 
     * @param service Function that generates embeddings for texts. Should return non-empty embeddings
     *                when available, or empty arrays when unavailable.
     */
    fun configureAndroidEmbeddings(service: suspend (Array<String>) -> Array<FloatArray>) {
        androidEmbeddingService = service
        Log.i("RagService", "Android native embeddings configured")
    }

    /**
     * Clear Android native embedding configuration
     */
    fun clearAndroidEmbeddings() {
        androidEmbeddingService = null
        Log.i("RagService", "Android native embeddings cleared")
    }

    fun registerAnnIndex(index: (FloatArray, Int) -> List<Long>) {
        EmbeddingIndexRegistry.register(RagEmbeddingIndex { query, topK -> index(query, topK) })
    }

    fun registerAnnIndex(index: RagEmbeddingIndex) {
        EmbeddingIndexRegistry.register(index)
    }

    fun clearAnnIndex() {
        EmbeddingIndexRegistry.clear()
    }

    suspend fun rebuildAnnIndex(
        db: PeerDatabase,
        maxEmbeddings: Int = 10_000,
        numPlanes: Int = 12,
        bucketSize: Int = 256,
        fallbackSize: Int = 512
    ): RagAnnSnapshot {
        val pageSize = 512
        val records = ArrayList<VectorRecord>(min(maxEmbeddings, 2048))
        var offset = 0
        while (records.size < maxEmbeddings) {
            val batch = db.embeddingDao().listPaginated(pageSize, offset)
            if (batch.isEmpty()) break
            batch.forEach { embedding ->
                if (records.size >= maxEmbeddings) return@forEach
                val vector = bytesToFloatArray(embedding.vector)
                if (vector.isNotEmpty()) {
                    val norm = vectorNorm(vector)
                    if (norm > 0f) {
                        records.add(VectorRecord(embedding.id, vector, norm))
                    }
                }
            }
            offset += batch.size
        }

        if (records.isEmpty()) {
            clearAnnIndex()
            return RagAnnSnapshot(numPlanes, bucketSize, fallbackSize, emptyList())
        }

        registerRecords(records, numPlanes, bucketSize, fallbackSize)

        return RagAnnSnapshot(
            numPlanes = numPlanes,
            bucketSize = bucketSize,
            fallbackSize = fallbackSize,
            records = records.map { SerializedVector(it.id, it.vector, it.norm) }
        )
    }

    fun configureDocScoreCache(maxEntries: Int) {
        val target = maxEntries.coerceAtLeast(64)
        docScoreMaxEntries = target
        cacheLock.write { trimDocScoresLocked() }
    }

    fun docScoreCacheStats(): DocScoreStats {
        cacheLock.read {
            return DocScoreStats(
                size = docScoreCache.size,
                maxSize = docScoreMaxEntries
            )
        }
    }

    fun loadAnnSnapshot(snapshot: RagAnnSnapshot) {
        val records = snapshot.records.map { VectorRecord(it.id, it.vector, it.norm) }
        registerRecords(records, snapshot.numPlanes, snapshot.bucketSize, snapshot.fallbackSize)
    }

    private fun registerRecords(
        records: List<VectorRecord>,
        numPlanes: Int,
        bucketSize: Int,
        fallbackSize: Int
    ) {
        if (records.isEmpty()) {
            clearAnnIndex()
            return
        }
        val annIndex = RagEmbeddingIndex { query, topK ->
            if (query.isEmpty()) return@RagEmbeddingIndex emptyList()
            val qNorm = vectorNorm(query)
            if (qNorm <= 0f) return@RagEmbeddingIndex emptyList()

            val breadthScale = max(2, numPlanes.coerceAtLeast(1) / 4)
            val searchBreadth = max(
                topK.coerceAtLeast(1) * breadthScale,
                max(bucketSize.coerceAtLeast(topK), fallbackSize)
            )

            records.asSequence()
                .filter { it.vector.size == query.size }
                .map { record ->
                    val score = cosine(query, record.vector, record.norm)
                    record.id to score
                }
                .sortedByDescending { it.second }
                .take(searchBreadth)
                .take(topK.coerceAtLeast(1))
                .map { it.first }
                .toList()
        }
        registerAnnIndex(annIndex)
    }

    suspend fun indexDocument(db: PeerDatabase, doc: Document, text: String, maxChunkTokens: Int = 512, overlapTokens: Int = 64) {
        val engineStatus = EngineRuntime.status.value
        if (engineStatus !is EngineRuntime.EngineStatus.Loaded) return

        val chunks = optimizedTokenizerChunks(text, maxChunkTokens, overlapTokens)
        if (chunks.isEmpty()) return

        // Batch embed all chunks with caching for better performance
        val chunkTexts = chunks.map { it.text }.toTypedArray()
        val embeddings = embedCached(chunkTexts)

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
        val engineStatus = EngineRuntime.status.value
        if (engineStatus !is EngineRuntime.EngineStatus.Loaded) return emptyList()
        return retrieveHybrid(db, query, topK)
    }

    suspend fun retrieveHybrid(
        db: PeerDatabase,
        query: String,
        topK: Int = 6,
        alphaSemantic: Float = 0.7f,
        alphaLexical: Float = 0.3f,
    ): List<RagChunk> {
        val engineStatus = EngineRuntime.status.value
        if (engineStatus !is EngineRuntime.EngineStatus.Loaded) return emptyList()

        val qv = embedCached(arrayOf(query)).firstOrNull() ?: return emptyList()
        if (qv.isEmpty()) {
            // No semantic vector available; fall back to lexical-only retrieval
            val lexicalOnly = db.ragDao().searchChunks(query, limit = topK)
            return lexicalOnly
        }

        val lexicalMatches = db.ragDao().searchChunks(query, limit = topK * 6) // Get more candidates for better ranking
        val candidateEmbeddings = LinkedHashMap<Long, com.peerchat.data.db.Embedding>()

        val annIds = EmbeddingIndexRegistry.query(qv, max(topK * 5, 32))
        if (annIds.isNotEmpty()) {
            val annEmbeddings = db.embeddingDao().getByIds(annIds.distinct().take(256))
            annEmbeddings.forEach { candidateEmbeddings.putIfAbsent(it.id, it) }
        }

        val docIds = lexicalMatches.mapNotNull { it.docId }.distinct().take(64)
        if (docIds.isNotEmpty()) {
            val byDoc = db.embeddingDao().getByDocIds(docIds)
            byDoc.forEach { candidateEmbeddings[it.id] = it }
        }

        val desiredCandidates = max(topK * 12, candidateEmbeddings.size)
        if (candidateEmbeddings.size < desiredCandidates) {
            val batchSize = 200
            var offset = 0
            while (candidateEmbeddings.size < desiredCandidates) {
                val batch = db.embeddingDao().listPaginated(batchSize, offset)
                if (batch.isEmpty()) break
                batch.forEach { candidateEmbeddings.putIfAbsent(it.id, it) }
                offset += batch.size
            }
        }

        val semanticScores = HashMap<Long, Float>(candidateEmbeddings.size.coerceAtLeast(topK * 2))
        candidateEmbeddings.values.forEach { emb ->
            if (emb.dim <= 0 || emb.vector.isEmpty()) return@forEach
            if (emb.dim != qv.size) return@forEach
            val v = bytesToFloatArray(emb.vector)
            val similarity = cosine(qv, v, emb.norm)
            semanticScores[emb.id] = similarity
        }

        // Get lexical matches (FTS search) with improved scoring
        val lexicalScores = HashMap<Long, Float>()

        // Calculate TF-IDF inspired lexical scores
        val queryTerms = query.lowercase()
            .split("\\s+".toRegex())
            .filter { it.length > 2 } // Ignore very short terms
            .distinct()

        for ((rank, chunk) in lexicalMatches.withIndex()) {
            // Base rank score (lower is better rank)
            val rankScore = 1f - (rank.toFloat() / lexicalMatches.size.toFloat())

            // Term frequency bonus
            val chunkText = chunk.text.lowercase()
            val termMatches = queryTerms.count { term ->
                chunkText.contains(term)
            }
            val termFrequency = termMatches.toFloat() / queryTerms.size.toFloat()

            // Exact phrase bonus
            val exactMatch = if (chunkText.contains(query.lowercase())) 0.3f else 0f

            // Position bonus (earlier chunks in document get slight preference)
            val positionBonus = if (chunk.start < 1000) 0.1f else 0f

            val score = rankScore * 0.5f + termFrequency * 0.3f + exactMatch + positionBonus

            val embeddingId = chunk.embeddingId ?: continue
            if (score > 0.05f) { // Only keep meaningful scores
                lexicalScores[embeddingId] = score
                recordDocScore(chunk.docId, score)
            }
        }

        // Fuse semantic and lexical scores
        val fused = ArrayList<Pair<Long, Float>>()
        val allIds = HashSet<Long>()
        allIds.addAll(semanticScores.keys)
        allIds.addAll(lexicalScores.keys)

        for (id in allIds) {
            val semanticScore = semanticScores[id] ?: 0f
            val lexicalScore = lexicalScores[id] ?: 0f
            val docBonus = candidateEmbeddings[id]?.docId?.let { docScore(it) } ?: 0f
            val combinedScore = semanticScore * alphaSemantic +
                lexicalScore * alphaLexical +
                docBonus * 0.1f
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

    /**
     * Rebuilds embeddings for a document with different chunking parameters.
     * Useful for optimizing RAG performance by experimenting with different chunk sizes.
     */
    suspend fun reindexDocument(
        db: PeerDatabase,
        doc: Document,
        text: String,
        maxChunkTokens: Int = 512,
        overlapTokens: Int = 64
    ) {
        val engineStatus = EngineRuntime.status.value
        if (engineStatus !is EngineRuntime.EngineStatus.Loaded) return

        // Remove existing embeddings and chunks for this document
        val existingEmbeddings = db.embeddingDao().getByDocId(doc.id)
        val embeddingIds = existingEmbeddings.map { it.id }

        if (embeddingIds.isNotEmpty()) {
            db.ragDao().deleteChunksByEmbeddingIds(embeddingIds)
            db.embeddingDao().deleteByIds(embeddingIds)
        }

        // Re-index with new parameters
        indexDocument(db, doc, text, maxChunkTokens, overlapTokens)
    }

    /**
     * Gets statistics about the RAG index for monitoring and optimization.
     */
    suspend fun getIndexStats(db: PeerDatabase): RagStats {
        val totalChunks = db.ragDao().countChunks()
        val totalEmbeddings = db.embeddingDao().count()
        val totalDocuments = db.documentDao().countDocuments()
        val avgChunkTokens = db.ragDao().getAverageTokenCount() ?: 0f

        return RagStats(
            totalDocuments = totalDocuments,
            totalChunks = totalChunks,
            totalEmbeddings = totalEmbeddings,
            averageChunkTokens = avgChunkTokens
        )
    }
}

/**
 * Statistics about the RAG index for monitoring and optimization.
 */
data class RagStats(
    val totalDocuments: Int,
    val totalChunks: Int,
    val totalEmbeddings: Int,
    val averageChunkTokens: Float
)

private data class ChunkInfo(val text: String, val start: Int, val end: Int, val tokenCount: Int)

/**
 * Tokenizer-aware chunking using binary search to find optimal chunk boundaries.
 * This ensures chunks respect the token limit precisely while finding natural break points.
 */
private fun optimizedTokenizerChunks(text: String, maxTokens: Int, overlapTokens: Int): List<ChunkInfo> {
    if (text.isEmpty()) return emptyList()

    val out = ArrayList<ChunkInfo>()
    var pos = 0
    val totalChars = text.length

    // Estimate character-to-token ratio for initial bounds (typically 3-4 chars per token)
    val estimatedCharsPerToken = 4

    while (pos < totalChars) {
        // If remaining text is small, just take it all
        val remaining = totalChars - pos
        if (remaining < maxTokens * estimatedCharsPerToken / 4) {
            val chunkText = text.substring(pos)
            val tokens = countTokensCached(chunkText)
            if (tokens > 0) {
                out.add(ChunkInfo(chunkText, pos, totalChars, tokens))
            }
            break
        }

        // Binary search to find optimal chunk boundary
        val chunkEnd = findOptimalChunkBoundary(
            text = text,
            start = pos,
            maxTokens = maxTokens,
            estimatedCharsPerToken = estimatedCharsPerToken
        )

        val chunkText = text.substring(pos, chunkEnd)
        val actualTokens = countTokensCached(chunkText)

        out.add(ChunkInfo(chunkText, pos, chunkEnd, actualTokens))

        if (chunkEnd >= totalChars) break

        // Calculate next position with overlap using tokenizer-aware method
        pos = findOverlapStart(text, chunkEnd, overlapTokens, estimatedCharsPerToken)
    }

    return out
}

/**
 * Binary search to find the optimal character position that yields close to maxTokens.
 * Prefers breaking at sentence/paragraph boundaries when possible.
 */
private fun findOptimalChunkBoundary(
    text: String,
    start: Int,
    maxTokens: Int,
    estimatedCharsPerToken: Int
): Int {
    val totalChars = text.length

    // Initial estimate
    var low = start + maxTokens * estimatedCharsPerToken / 2 // Lower bound (too small)
    var high = min(totalChars, start + maxTokens * estimatedCharsPerToken * 2) // Upper bound (likely too large)

    // Ensure we have a valid range
    low = min(low, totalChars - 1)
    high = min(high, totalChars)

    if (low >= high) return totalChars

    var bestPos = high
    
    // Binary search to find position closest to maxTokens
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = text.substring(start, mid)
        val tokens = countTokensCached(candidate)
        
        if (tokens <= maxTokens) {
            // This position is valid, try to expand
            bestPos = mid
            low = mid + 1
            
            // If we're very close to maxTokens, this is a good boundary
            if (tokens >= maxTokens * 0.95f) {
                break
            }
        } else {
            // Too many tokens, need to shrink
            high = mid - 1
        }
    }
    
    // Prefer breaking at sentence/paragraph boundaries near the found position
    val preferredPos = findNaturalBreakPoint(text, start, bestPos, maxTokens)
    
    return preferredPos
}

/**
 * Finds a natural break point (sentence/paragraph) near the binary search result.
 * Returns the best natural break within reasonable distance of the target.
 * Enhanced with better sentence boundary detection and content awareness.
 */
private fun findNaturalBreakPoint(
    text: String,
    start: Int,
    targetPos: Int,
    maxTokens: Int
): Int {
    // Look for natural breaks within ±25% of target position (increased for better results)
    val searchWindow = (targetPos - start) / 4
    val searchStart = max(start, targetPos - searchWindow)
    val searchEnd = min(text.length, targetPos + searchWindow)

    // Priority: paragraph breaks > sentence breaks > clause breaks > word breaks
    var bestBreak = targetPos
    var bestScore = 0
    var bestTokens = countTokensCached(text.substring(start, targetPos))

    for (i in searchEnd downTo searchStart) {
        if (i <= start) continue

        val char = if (i < text.length) text[i] else ' '
        val prevChar = if (i > 0) text[i - 1] else ' '
        val nextChar = if (i < text.length - 1) text[i + 1] else ' '

        val score = when {
            // Paragraph break (double newline, section headers, or markdown headers)
            (prevChar == '\n' && char == '\n') ||
            (i > 1 && text.substring(max(0, i - 2), i).contains("\n\n")) ||
            (char == '\n' && nextChar.isUpperCase() && i > start + 50) || // Potential section start
            (i > start + 2 && text[i - 1] == '\n' && "#*+-".contains(char)) -> 5 // Markdown headers/lists

            // Strong sentence breaks
            ".!?".contains(prevChar) &&
            (char.isWhitespace() || char == '\n') &&
            (i == text.length - 1 || nextChar.isUpperCase() || nextChar == '"' || nextChar == '\'') -> 4

            // Medium sentence breaks (semicolon, colon with capital letter)
            ";:".contains(prevChar) &&
            (char.isWhitespace() || char == '\n') &&
            (i == text.length - 1 || nextChar.isUpperCase()) -> 3

            // Weak sentence breaks (comma, dash with capital)
            ",—".contains(prevChar) &&
            (char.isWhitespace() || char == '\n') &&
            (i == text.length - 1 || nextChar.isUpperCase()) -> 2

            // Word breaks
            char.isWhitespace() -> 1

            else -> 0
        }

        if (score > 0) {
            // Verify this break creates a reasonable chunk
            val chunkText = text.substring(start, i)
            val tokens = countTokensCached(chunkText)

            // Prefer breaks that get us closer to maxTokens
            val tokenRatio = tokens.toFloat() / maxTokens
            val sizePenalty = when {
                tokenRatio < 0.3f -> 0.5f // Too small
                tokenRatio > 1.3f -> 0.7f // Too large
                else -> 1.0f // Good size
            }

            val adjustedScore = (score * sizePenalty).toInt()

            if (adjustedScore > bestScore ||
                (adjustedScore == bestScore && abs(tokens - maxTokens) < abs(bestTokens - maxTokens))) {
                bestBreak = i
                bestScore = adjustedScore
                bestTokens = tokens

                // Early exit for very good breaks
                if (score >= 4 && tokenRatio in 0.7f..1.2f) {
                    break
                }
            }
        }
    }

    return bestBreak
}

/**
 * Finds the start position for the next chunk considering overlap.
 * Uses tokenizer to ensure overlap is approximately overlapTokens.
 */
private fun findOverlapStart(
    text: String,
    currentEnd: Int,
    overlapTokens: Int,
    estimatedCharsPerToken: Int
): Int {
    if (currentEnd <= 0) return currentEnd
    
    // Estimate overlap in characters
    val estimatedOverlap = overlapTokens * estimatedCharsPerToken
    var overlapStart = max(0, currentEnd - estimatedOverlap * 2) // Start searching from further back
    
    // Binary search to find position with approximately overlapTokens
    var low = max(0, currentEnd - estimatedOverlap * 3)
    var high = currentEnd
    
    var bestPos = overlapStart
    var bestDiff = Int.MAX_VALUE
    
    while (low <= high) {
        val mid = (low + high) / 2
        val overlapText = text.substring(mid, currentEnd)
        val tokens = countTokensCached(overlapText)
        
        val diff = kotlin.math.abs(tokens - overlapTokens)
        if (diff < bestDiff) {
            bestDiff = diff
            bestPos = mid
        }
        
        when {
            tokens < overlapTokens -> {
                // Need more overlap, move start backward
                high = mid - 1
            }
            tokens > overlapTokens -> {
                // Too much overlap, move start forward
                low = mid + 1
            }
            else -> {
                // Perfect match
                return mid
            }
        }
    }
    
    return bestPos
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
private fun trimTokenCacheLocked() {
    if (tokenCountCache.size <= MAX_TOKEN_CACHE_ENTRIES) return
    val iterator = tokenCountCache.entries.iterator()
    while (tokenCountCache.size > MAX_TOKEN_CACHE_ENTRIES && iterator.hasNext()) {
        iterator.next()
        iterator.remove()
    }
}

private fun putEmbeddingLocked(key: String, embedding: FloatArray) {
    val existing = embeddingCache.put(key, embedding.copyOf())
    if (existing != null) {
        embeddingCacheBytes -= (existing.size * 4L)
    }
    embeddingCacheBytes += (embedding.size * 4L)
    if (embeddingCacheBytes <= MAX_EMBEDDING_CACHE_BYTES && embeddingCache.size <= MAX_EMBEDDING_CACHE_ENTRIES) {
        return
    }
    val iterator = embeddingCache.entries.iterator()
    while ((embeddingCacheBytes > MAX_EMBEDDING_CACHE_BYTES || embeddingCache.size > MAX_EMBEDDING_CACHE_ENTRIES) && iterator.hasNext()) {
        val eldest = iterator.next()
        embeddingCacheBytes -= (eldest.value.size * 4L)
        iterator.remove()
    }
    if (embeddingCacheBytes < 0) embeddingCacheBytes = 0
}

private fun recordDocScore(docId: Long, score: Float) {
    cacheLock.write {
        val existing = docScoreCache[docId]
        val now = System.currentTimeMillis()
        if (existing == null || score > existing.score) {
            docScoreCache[docId] = CandidateScore(score, now)
        } else {
            existing.updatedAtMs = now
        }
        trimDocScoresLocked()
    }
}

private fun docScore(docId: Long): Float {
    cacheLock.read {
        return docScoreCache[docId]?.score ?: 0f
    }
}

private fun trimDocScoresLocked() {
    if (docScoreCache.size <= docScoreMaxEntries) return
    val iterator = docScoreCache.entries.iterator()
    while (docScoreCache.size > docScoreMaxEntries && iterator.hasNext()) {
        iterator.next()
        iterator.remove()
    }
}
