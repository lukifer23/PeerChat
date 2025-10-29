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
        val chunks = simpleTokenChunks(text, maxChunkTokens, overlapTokens)
        val embeddings = EngineNative.embed(chunks.toTypedArray())
        for (i in chunks.indices) {
            val t = chunks[i]
            val vec = embeddings[i]
            val norm = vectorNorm(vec)
            val embId = db.embeddingDao().upsert(
                Embedding(
                    docId = doc.id,
                    chatId = null,
                    textHash = sha256(t),
                    vector = floatArrayToBytes(vec),
                    dim = vec.size,
                    norm = norm,
                    createdAt = System.currentTimeMillis()
                )
            )
            db.ragDao().insertChunk(
                RagChunk(
                    docId = doc.id,
                    start = 0,
                    end = 0,
                    text = t,
                    tokenCount = 0,
                    embeddingId = embId
                )
            )
        }
    }

    suspend fun retrieve(db: PeerDatabase, query: String, topK: Int = 6): List<RagChunk> {
        val qv = EngineNative.embed(arrayOf(query)).firstOrNull() ?: return emptyList()
        val all = db.embeddingDao().listAll() // consider paging later
        val scored = all.mapNotNull { emb ->
            if (emb.dim <= 0 || emb.vector.isEmpty()) return@mapNotNull null
            val v = bytesToFloatArray(emb.vector)
            val s = cosine(qv, v, emb.norm)
            Pair(emb, s)
        }.sortedByDescending { it.second }
        val chosen = scored.take(topK)
        val out = ArrayList<RagChunk>()
        for ((e, _) in chosen) {
            val ch = db.ragDao().getByEmbeddingId(e.id)
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

private fun simpleTokenChunks(text: String, maxTokens: Int, overlap: Int): List<String> {
    // naive whitespace tokenization sizing
    val words = text.split(Regex("\\s+"))
    if (words.isEmpty()) return emptyList()
    val out = ArrayList<String>()
    var i = 0
    while (i < words.size) {
        val end = min(words.size, i + maxTokens)
        out.add(words.subList(i, end).joinToString(" "))
        i = max(end - overlap, i + 1)
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

