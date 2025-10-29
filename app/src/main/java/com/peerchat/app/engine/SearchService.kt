package com.peerchat.app.engine

import com.peerchat.app.data.PeerChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * Service for unified search across messages, documents, and chunks.
 */
class SearchService(private val repository: PeerChatRepository) {

    data class SearchResult(
        val type: SearchResultType,
        val title: String,
        val content: String,
        val chatId: Long? = null,
        val messageId: Long? = null,
        val documentId: Long? = null,
        val chunkId: Long? = null
    )

    enum class SearchResultType {
        MESSAGE, DOCUMENT_CHUNK
    }

    /**
     * Search across messages and document chunks.
     */
    suspend fun search(query: String, limit: Int = 50): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.trim().isEmpty()) return@withContext emptyList()

        val trimmed = query.trim()

        // Search messages
        val messageResults = repository.searchMessages(trimmed, limit / 2)
            .map { message ->
                SearchResult(
                    type = SearchResultType.MESSAGE,
                    title = "Message",
                    content = message.contentMarkdown.take(200) + if (message.contentMarkdown.length > 200) "..." else "",
                    chatId = message.chatId,
                    messageId = message.id
                )
            }

        // Search document chunks
        val chunkResults = repository.searchChunks(trimmed, limit / 2)
            .map { chunk ->
                SearchResult(
                    type = SearchResultType.DOCUMENT_CHUNK,
                    title = "Document",
                    content = chunk.text.take(200) + if (chunk.text.length > 200) "..." else "",
                    documentId = chunk.documentId,
                    chunkId = chunk.id
                )
            }

        // Combine and limit results
        (messageResults + chunkResults).take(limit)
    }

    /**
     * Get search suggestions based on recent content.
     */
    suspend fun getSearchSuggestions(limit: Int = 10): List<String> = withContext(Dispatchers.IO) {
        // Get recent chat titles and document names as suggestions
        val recentChats = repository.getRecentChats(limit / 2).map { it.title }
        val recentDocs = repository.getRecentDocuments(limit / 2).map { it.title }

        (recentChats + recentDocs).distinct().take(limit)
    }

    /**
     * Create a reactive search flow that updates as query changes.
     */
    fun createSearchFlow(queryFlow: Flow<String>, limit: Int = 50): Flow<List<SearchResult>> {
        return queryFlow.combine(
            flowOf(Unit) // Dummy flow to trigger search
        ) { query, _ ->
            if (query.trim().isEmpty()) emptyList() else search(query, limit)
        }
    }
}
