package com.peerchat.rag

import com.peerchat.data.db.PeerDatabase
import com.peerchat.data.db.RagChunk
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

interface Retriever {
    suspend fun retrieve(db: PeerDatabase, query: String, topK: Int = 6): List<RagChunk>
}

class HybridRetriever(maxCores: Int = 3) : Retriever {
    private val dispatcher = Executors.newFixedThreadPool(maxCores.coerceIn(1, 4)).asCoroutineDispatcher()

    override suspend fun retrieve(db: PeerDatabase, query: String, topK: Int): List<RagChunk> = withContext(dispatcher) {
        RagService.retrieveHybrid(db, query, topK)
    }
}

object RetrieverProvider {
    private val cores: Int by lazy {
        val prop = runCatching { System.getProperty("peerchat.runtime.maxCores") }.getOrNull()
        prop?.toIntOrNull()?.coerceIn(1, 4) ?: 3
    }
    val instance: Retriever by lazy { HybridRetriever(cores) }
}


