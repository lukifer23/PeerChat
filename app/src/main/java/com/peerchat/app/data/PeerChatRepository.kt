package com.peerchat.app.data

import com.peerchat.app.BuildConfig
import com.peerchat.data.db.Chat
import com.peerchat.data.db.Document
import com.peerchat.data.db.Folder
import com.peerchat.data.db.Message
import com.peerchat.data.db.ModelManifest
import com.peerchat.data.db.PeerDatabase
import com.peerchat.data.db.PeerDatabaseProvider
import com.peerchat.data.db.RagChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow

/**
 * Result wrapper for operations that may succeed or fail.
 */
sealed class OperationResult<out T> {
    data class Success<T>(val data: T, val message: String = "Success") : OperationResult<T>()
    data class Failure(val error: String) : OperationResult<Nothing>()
}

class PeerChatRepository(private val database: PeerDatabase) {
    fun observeFolders(): Flow<List<Folder>> = database.folderDao().observeAll()

    fun observeChats(folderId: Long?): Flow<List<Chat>> =
        database.chatDao().observeByFolder(folderId)

    suspend fun createFolder(name: String): Long = withContext(Dispatchers.IO) {
        database.folderDao().upsert(
            Folder(
                name = name,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun createChat(
        title: String,
        folderId: Long?,
        systemPrompt: String,
        modelId: String
    ): Long = withContext(Dispatchers.IO) {
        database.chatDao().upsert(
            Chat(
                title = title,
                folderId = folderId,
                systemPrompt = systemPrompt,
                modelId = modelId,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                settingsJson = "{}"
            )
        )
    }

    suspend fun renameChat(id: Long, title: String) = withContext(Dispatchers.IO) {
        database.chatDao().rename(id, title, System.currentTimeMillis())
    }

    suspend fun moveChat(id: Long, folderId: Long?) = withContext(Dispatchers.IO) {
        database.chatDao().moveToFolder(id, folderId, System.currentTimeMillis())
    }

    suspend fun getChat(id: Long): Chat? = withContext(Dispatchers.IO) {
        database.chatDao().getById(id)
    }

    suspend fun insertMessage(message: Message): Long = withContext(Dispatchers.IO) {
        database.messageDao().insert(message)
    }

    suspend fun listMessages(chatId: Long): List<Message> = withContext(Dispatchers.IO) {
        database.messageDao().listByChat(chatId)
    }

    suspend fun searchMessages(query: String, limit: Int): List<Message> = withContext(Dispatchers.IO) {
        database.messageDao().searchText(query, limit)
    }

    suspend fun searchChunks(query: String, limit: Int): List<RagChunk> = withContext(Dispatchers.IO) {
        database.ragDao().searchChunks(query, limit)
    }

    fun observeDocuments(): Flow<List<Document>> = database.documentDao().observeAll()

    suspend fun upsertDocument(document: Document): Long = withContext(Dispatchers.IO) {
        database.documentDao().upsert(document)
    }

    suspend fun deleteDocument(id: Long) = withContext(Dispatchers.IO) {
        database.documentDao().delete(id)
    }

    suspend fun getManifests(): List<ModelManifest> = withContext(Dispatchers.IO) {
        database.modelManifestDao().listAll()
    }

    fun database(): PeerDatabase = database

    suspend fun deleteChat(chatId: Long) = withContext(Dispatchers.IO) {
        val db = database
        db.messageDao().deleteByChat(chatId)
        db.chatDao().deleteById(chatId)
    }

    suspend fun deleteFolder(folderId: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val db = database
        // Orphan chats rather than deleting to avoid data loss
        db.chatDao().clearFolder(folderId, now)
        db.folderDao().deleteById(folderId)
    }

    suspend fun renameFolder(folderId: Long, name: String) = withContext(Dispatchers.IO) {
        database.folderDao().rename(folderId, name, System.currentTimeMillis())
    }

    suspend fun updateChatSettings(chatId: Long, systemPrompt: String?, modelId: String?) = withContext(Dispatchers.IO) {
        val dao = database.chatDao()
        val base = dao.getById(chatId) ?: return@withContext
        val updated = base.copy(
            systemPrompt = systemPrompt ?: base.systemPrompt,
            modelId = modelId ?: base.modelId,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(updated)
    }

    suspend fun getRecentChats(limit: Int): List<Chat> = withContext(Dispatchers.IO) {
        database.chatDao().getRecent(limit)
    }

    suspend fun getRecentDocuments(limit: Int): List<Document> = withContext(Dispatchers.IO) {
        database.documentDao().getRecent(limit)
    }

    // Chat operations with error handling (formerly ChatService)
    suspend fun createChatResult(
        title: String,
        folderId: Long? = null,
        systemPrompt: String = "",
        modelId: String = "default"
    ): OperationResult<Long> = withContext(Dispatchers.IO) {
        try {
            val chatId = createChat(
                title = title.trim().ifEmpty { "New Chat" },
                folderId = folderId,
                systemPrompt = systemPrompt,
                modelId = modelId
            )
            OperationResult.Success(chatId, "Chat created")
        } catch (e: Exception) {
            OperationResult.Failure("Create error: ${e.message}")
        }
    }

    suspend fun renameChatResult(chatId: Long, newTitle: String): OperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val title = newTitle.trim().ifEmpty { "Untitled" }
            renameChat(chatId, title)
            OperationResult.Success(Unit, "Chat renamed")
        } catch (e: Exception) {
            OperationResult.Failure("Rename error: ${e.message}")
        }
    }

    suspend fun moveChatResult(chatId: Long, folderId: Long?): OperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            moveChat(chatId, folderId)
            OperationResult.Success(Unit, "Chat moved")
        } catch (e: Exception) {
            OperationResult.Failure("Move error: ${e.message}")
        }
    }

    suspend fun forkChatResult(chatId: Long): OperationResult<Long> = withContext(Dispatchers.IO) {
        try {
            val base = getChat(chatId)
                ?: return@withContext OperationResult.Failure("Chat not found")

            val newChatId = createChat(
                title = base.title + " (copy)",
                folderId = base.folderId,
                systemPrompt = base.systemPrompt,
                modelId = base.modelId
            )

            // Copy all messages
            val messages = listMessages(chatId)
            messages.forEach { message ->
                insertMessage(
                    message.copy(
                        id = 0,
                        chatId = newChatId,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }

            OperationResult.Success(newChatId, "Chat forked")
        } catch (e: Exception) {
            OperationResult.Failure("Fork error: ${e.message}")
        }
    }

    suspend fun deleteChatResult(chatId: Long): OperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            deleteChat(chatId)
            OperationResult.Success(Unit, "Chat deleted")
        } catch (e: Exception) {
            OperationResult.Failure("Delete error: ${e.message}")
        }
    }

    suspend fun updateChatSettingsResult(
        chatId: Long,
        systemPrompt: String? = null,
        modelId: String? = null
    ): OperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            updateChatSettings(chatId, systemPrompt, modelId)
            OperationResult.Success(Unit, "Settings updated")
        } catch (e: Exception) {
            OperationResult.Failure("Update error: ${e.message}")
        }
    }

    // Benchmark operations
    suspend fun insertBenchmarkResult(result: com.peerchat.data.db.BenchmarkResult): Long = withContext(Dispatchers.IO) {
        database.benchmarkResultDao().insert(result)
    }

    suspend fun getRecentBenchmarkResults(manifestId: Long, limit: Int = 10): List<com.peerchat.data.db.BenchmarkResult> = withContext(Dispatchers.IO) {
        val results = database.benchmarkResultDao().getRecentByManifest(manifestId)
        if (limit > 0) results.take(limit) else results
    }

    fun observeBenchmarkResults(manifestId: Long): Flow<List<com.peerchat.data.db.BenchmarkResult>> =
        database.benchmarkResultDao().observeByManifest(manifestId)

    suspend fun deleteBenchmarkResults(manifestId: Long) = withContext(Dispatchers.IO) {
        database.benchmarkResultDao().deleteByManifest(manifestId)
    }

    companion object {
        fun from(context: android.content.Context): PeerChatRepository =
            PeerChatRepository(PeerDatabaseProvider.get(context, BuildConfig.DEBUG))
    }

    suspend fun renameFolderResult(folderId: Long, newName: String): OperationResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val trimmed = newName.trim().ifEmpty { "Folder" }
            renameFolder(folderId, trimmed)
            OperationResult.Success(Unit, "Folder renamed")
        } catch (e: Exception) {
            OperationResult.Failure("Rename error: ${e.message}")
        }
    }
}
