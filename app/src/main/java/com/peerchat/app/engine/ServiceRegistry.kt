package com.peerchat.app.engine

import android.content.Context
import com.peerchat.app.data.PeerChatRepository

/**
 * Registry for accessing application services.
 * Provides centralized service instantiation and dependency injection.
 */
object ServiceRegistry {

    private lateinit var context: Context
    private val services = mutableMapOf<String, Any>()

    fun initialize(context: Context) {
        this.context = context.applicationContext
    }

    val modelService: ModelService by lazy {
        getOrCreate("modelService") {
            ModelService(
                context = context,
                manifestService = modelManifestService,
                modelCache = ModelStateCache(context)
            )
        }
    }

    val documentService: DocumentService by lazy {
        getOrCreate("documentService") {
            DocumentService(context, repository)
        }
    }

    val chatService: ChatService by lazy {
        getOrCreate("chatService") {
            ChatService(repository)
        }
    }

    val searchService: SearchService by lazy {
        getOrCreate("searchService") {
            SearchService(repository)
        }
    }

    // Core dependencies
    private val repository: PeerChatRepository by lazy {
        PeerChatRepository.from(context)
    }

    private val modelManifestService: ModelManifestService by lazy {
        ModelManifestService(context)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> getOrCreate(key: String, factory: () -> T): T {
        return services.getOrPut(key, factory) as T
    }

    /**
     * Clear all cached services (useful for testing or cleanup).
     */
    fun clear() {
        services.clear()
    }
}
