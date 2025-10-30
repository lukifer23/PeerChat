package com.peerchat.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: Folder): Long

    @Query("SELECT * FROM folders ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Folder>>

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface ChatDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: Chat): Long

    @Query("SELECT * FROM chats WHERE folderId IS :folderId ORDER BY updatedAt DESC")
    fun observeByFolder(folderId: Long?): Flow<List<Chat>>

    @Query("UPDATE chats SET title = :title, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, title: String, now: Long)

    @Query("UPDATE chats SET folderId = :folderId, updatedAt = :now WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?, now: Long)

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun getById(id: Long): Chat?

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE chats SET folderId = NULL, updatedAt = :now WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long, now: Long)

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Chat>
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message): Long

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    fun observeByChat(chatId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun listByChat(chatId: Long): List<Message>

    @Query("SELECT messages.* FROM messages JOIN messages_fts ON(messages_fts.contentMarkdown MATCH :query) WHERE messages.rowid = messages_fts.rowid ORDER BY messages.id DESC LIMIT :limit")
    suspend fun searchText(query: String, limit: Int = 50): List<Message>

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: Long)
}

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: Document): Long

    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Document>>

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM documents ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Document>
}

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: Embedding): Long

    @Query("SELECT * FROM embeddings")
    suspend fun listAll(): List<Embedding>
}

@Dao
interface RagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunk(chunk: RagChunk): Long

    @Query("SELECT rag_chunks.* FROM rag_chunks JOIN rag_chunks_fts ON(rag_chunks_fts.text MATCH :query) WHERE rag_chunks.rowid = rag_chunks_fts.rowid ORDER BY rag_chunks.id DESC LIMIT :limit")
    suspend fun searchChunks(query: String, limit: Int = 50): List<RagChunk>

    @Query("SELECT * FROM rag_chunks WHERE embeddingId = :embeddingId LIMIT 1")
    suspend fun getByEmbeddingId(embeddingId: Long): RagChunk?

    @Query("SELECT * FROM rag_chunks WHERE embeddingId IN (:embeddingIds)")
    suspend fun getByEmbeddingIds(embeddingIds: List<Long>): List<RagChunk>
}

@Dao
interface ModelManifestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(manifest: ModelManifest): Long

    @Query("SELECT * FROM model_manifests ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<ModelManifest>>

    @Query("SELECT * FROM model_manifests ORDER BY importedAt DESC")
    suspend fun listAll(): List<ModelManifest>

    @Query("SELECT * FROM model_manifests WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): ModelManifest?

    @Query("DELETE FROM model_manifests WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE model_manifests SET metadataJson = :metadataJson WHERE id = :id")
    suspend fun updateMetadata(id: Long, metadataJson: String)
}
