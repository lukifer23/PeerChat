package com.peerchat.app.data

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

    suspend fun upsertDocument(document: Document): Long = withContext(Dispatchers.IO) {
        database.documentDao().upsert(document)
    }

    suspend fun getManifests(): List<ModelManifest> = withContext(Dispatchers.IO) {
        database.modelManifestDao().listAll()
    }

    fun database(): PeerDatabase = database

    companion object {
        fun from(context: android.content.Context): PeerChatRepository =
            PeerChatRepository(PeerDatabaseProvider.get(context))
    }
}
