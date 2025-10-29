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

object RagService {
    suspend fun indexDocument(db: PeerDatabase, doc: Document, text: String, maxChunkTokens: Int = 512, overlapTokens: Int = 64) {
        val chunks = tokenizerAwareChunks(text, maxChunkTokens, overlapTokens)
        if (chunks.isEmpty()) return
        val embeddings = EngineNative.embed(chunks.map { it.text }.toTypedArray())
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
        val all = db.embeddingDao().listAll()
        val semanticScores = HashMap<Long, Float>()
        for (emb in all) {
            if (emb.dim <= 0 || emb.vector.isEmpty()) continue
            val v = bytesToFloatArray(emb.vector)
            val s = cosine(qv, v, emb.norm)
            semanticScores[emb.id] = s
        }
        val lexical = db.ragDao().searchChunks(query, limit = topK * 4)
        val lexicalScores = HashMap<Long, Float>()
        for ((rank, ch) in lexical.withIndex()) {
            lexicalScores[ch.embeddingId ?: -1L] = ((lexical.size - rank).toFloat() / lexical.size.toFloat())
        }
        val fused = ArrayList<Pair<Long, Float>>()
        val ids = HashSet<Long>()
        ids.addAll(semanticScores.keys)
        ids.addAll(lexicalScores.keys)
        for (id in ids) {
            val s = (semanticScores[id] ?: 0f) * alphaSemantic + (lexicalScores[id] ?: 0f) * alphaLexical
            fused.add(id to s)
        }
        fused.sortByDescending { it.second }
        val chosen = fused.take(topK)
        val out = ArrayList<RagChunk>()
        for ((embId, _) in chosen) {
            val ch = if (embId > 0) db.ragDao().getByEmbeddingId(embId) else null
            if (ch != null) out.add(ch)
        }
        return out
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

private fun tokenizerAwareChunks(text: String, maxTokens: Int, overlapTokens: Int): List<ChunkInfo> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<ChunkInfo>()
    var pos = 0
    val chars = text.toCharArray()
    val totalChars = chars.size

    while (pos < totalChars) {
        var searchStart = pos
        var searchEnd = min(totalChars, pos + (maxTokens * 4))
        var chunkEnd = searchEnd

        while (searchStart < searchEnd) {
            val mid = (searchStart + searchEnd) / 2
            val candidate = String(chars, pos, mid - pos)
            val tokens = runCatching { EngineNative.countTokens(candidate) }.getOrElse { 
                (candidate.length / 4).coerceAtLeast(1) 
            }
            if (tokens <= maxTokens) {
                chunkEnd = mid
                searchStart = mid + 1
            } else {
                searchEnd = mid - 1
            }
        }

        if (chunkEnd <= pos) {
            chunkEnd = min(totalChars, pos + 100)
        }

        val chunkText = String(chars, pos, chunkEnd - pos)
        val actualTokens = runCatching { EngineNative.countTokens(chunkText) }.getOrElse { 
            (chunkText.length / 4).coerceAtLeast(1) 
        }

        out.add(ChunkInfo(chunkText, pos, chunkEnd, actualTokens))

        if (chunkEnd >= totalChars) break
        val backStep = max(overlapTokens * 2, overlapTokens)
        pos = max(pos + 1, chunkEnd - backStep)
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

