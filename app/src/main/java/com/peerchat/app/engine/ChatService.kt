package com.peerchat.app.engine

import com.peerchat.app.data.PeerChatRepository
import com.peerchat.data.db.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for managing chat operations including creation, modification, and conversation management.
 */
class ChatService(private val repository: PeerChatRepository) {

    data class CreateResult(
        val success: Boolean,
        val message: String,
        val chatId: Long? = null
    )

    data class UpdateResult(
        val success: Boolean,
        val message: String
    )

    /**
     * Create a new chat conversation.
     */
    suspend fun createChat(
        title: String,
        folderId: Long? = null,
        systemPrompt: String = "",
        modelId: String = "default"
    ): CreateResult = withContext(Dispatchers.IO) {
        try {
            val chatId = repository.createChat(
                title = title.trim().ifEmpty { "New Chat" },
                folderId = folderId,
                systemPrompt = systemPrompt,
                modelId = modelId
            )
            CreateResult(success = true, message = "Chat created", chatId = chatId)
        } catch (e: Exception) {
            CreateResult(success = false, message = "Create error: ${e.message}")
        }
    }

    /**
     * Rename an existing chat.
     */
    suspend fun renameChat(chatId: Long, newTitle: String): UpdateResult = withContext(Dispatchers.IO) {
        try {
            val title = newTitle.trim().ifEmpty { "Untitled" }
            repository.renameChat(chatId, title)
            UpdateResult(success = true, message = "Chat renamed")
        } catch (e: Exception) {
            UpdateResult(success = false, message = "Rename error: ${e.message}")
        }
    }

    /**
     * Move a chat to a different folder.
     */
    suspend fun moveChat(chatId: Long, folderId: Long?): UpdateResult = withContext(Dispatchers.IO) {
        try {
            repository.moveChat(chatId, folderId)
            UpdateResult(success = true, message = "Chat moved")
        } catch (e: Exception) {
            UpdateResult(success = false, message = "Move error: ${e.message}")
        }
    }

    /**
     * Fork a chat by duplicating it with all messages.
     */
    suspend fun forkChat(chatId: Long): CreateResult = withContext(Dispatchers.IO) {
        try {
            val base = repository.getChat(chatId)
                ?: return@withContext CreateResult(success = false, message = "Chat not found")

            val newChatId = repository.createChat(
                title = base.title + " (copy)",
                folderId = base.folderId,
                systemPrompt = base.systemPrompt,
                modelId = base.modelId
            )

            // Copy all messages
            val messages = repository.listMessages(chatId)
            messages.forEach { message ->
                repository.insertMessage(
                    message.copy(
                        id = 0,
                        chatId = newChatId,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }

            CreateResult(success = true, message = "Chat forked", chatId = newChatId)
        } catch (e: Exception) {
            CreateResult(success = false, message = "Fork error: ${e.message}")
        }
    }

    /**
     * Delete a chat and all its messages.
     */
    suspend fun deleteChat(chatId: Long): UpdateResult = withContext(Dispatchers.IO) {
        try {
            repository.deleteChat(chatId)
            UpdateResult(success = true, message = "Chat deleted")
        } catch (e: Exception) {
            UpdateResult(success = false, message = "Delete error: ${e.message}")
        }
    }

    /**
     * Update chat settings (system prompt, model, etc.).
     */
    suspend fun updateChatSettings(
        chatId: Long,
        systemPrompt: String? = null,
        modelId: String? = null
    ): UpdateResult = withContext(Dispatchers.IO) {
        try {
            repository.updateChatSettings(chatId, systemPrompt, modelId)
            UpdateResult(success = true, message = "Settings updated")
        } catch (e: Exception) {
            UpdateResult(success = false, message = "Update error: ${e.message}")
        }
    }

    /**
     * Get messages for a chat.
     */
    suspend fun getMessages(chatId: Long): List<Message> = withContext(Dispatchers.IO) {
        repository.listMessages(chatId)
    }
}
