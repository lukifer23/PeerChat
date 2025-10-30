package com.peerchat.app.engine

import android.content.Context
import com.peerchat.app.data.PeerChatRepository

/**
 * Registry for accessing application services.
 * Provides centralized service instantiation and dependency injection.
 */
object ServiceRegistry {

    private lateinit var context: Context

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    val modelRepository: ModelRepository by lazy {
        ModelRepository(context = context, manifestService = modelManifestService)
    }

    val modelService: ModelService by lazy {
        ModelService(modelRepository)
    }

    val documentService: DocumentService by lazy {
        DocumentService(context, repository)
    }

    val searchService: SearchService by lazy {
        SearchService(repository)
    }

    val ragRetriever: com.peerchat.rag.Retriever by lazy {
        com.peerchat.rag.RetrieverProvider.instance
    }

    // Core dependencies
    private val repository: PeerChatRepository by lazy {
        PeerChatRepository.from(context)
    }

    private val modelManifestService: ModelManifestService by lazy {
        ModelManifestService(context)
    }
}
