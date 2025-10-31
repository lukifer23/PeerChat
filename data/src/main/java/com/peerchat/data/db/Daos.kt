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

    @Query("UPDATE folders SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)
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

    @androidx.room.Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<Message>): List<Long>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    fun observeByChat(chatId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun listByChat(chatId: Long): List<Message>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY id DESC LIMIT 1")
    suspend fun getLastMessage(chatId: Long): Message?

    @Query("SELECT COUNT(*) FROM messages WHERE chatId = :chatId")
    suspend fun countByChat(chatId: Long): Int

    @Query("SELECT SUM(tokens) FROM messages WHERE chatId = :chatId")
    suspend fun getTotalTokens(chatId: Long): Int?

    @Query("SELECT messages.* FROM messages JOIN messages_fts ON(messages_fts.contentMarkdown MATCH :query) WHERE messages.rowid = messages_fts.rowid AND messages.chatId = :chatId ORDER BY messages.id DESC LIMIT :limit")
    suspend fun searchTextInChat(chatId: Long, query: String, limit: Int = 50): List<Message>

    @Query("SELECT messages.* FROM messages JOIN messages_fts ON(messages_fts.contentMarkdown MATCH :query) WHERE messages.rowid = messages_fts.rowid ORDER BY messages.id DESC LIMIT :limit")
    suspend fun searchText(query: String, limit: Int = 50): List<Message>

    @androidx.room.Transaction
    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteByChat(chatId: Long)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: Long)

    @Query("SELECT * FROM messages WHERE id > :afterId AND chatId = :chatId ORDER BY id ASC LIMIT :limit")
    suspend fun getMessagesAfter(chatId: Long, afterId: Long, limit: Int): List<Message>
}

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(doc: Document): Long

    @Query("SELECT * FROM documents ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Document>>

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun countDocuments(): Int

    @Query("SELECT * FROM documents ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<Document>
}

@Dao
interface EmbeddingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: Embedding): Long

    @androidx.room.Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(embeddings: List<Embedding>): List<Long>

    @Query("SELECT * FROM embeddings WHERE docId = :docId ORDER BY id ASC")
    suspend fun getByDocId(docId: Long): List<Embedding>

    @Query("SELECT * FROM embeddings WHERE chatId = :chatId ORDER BY id ASC")
    suspend fun getByChatId(chatId: Long): List<Embedding>

    @Query("SELECT * FROM embeddings WHERE textHash = :textHash LIMIT 1")
    suspend fun getByTextHash(textHash: String): Embedding?

    @Query("SELECT * FROM embeddings WHERE docId IN (:docIds)")
    suspend fun getByDocIds(docIds: List<Long>): List<Embedding>

    @Query("SELECT * FROM embeddings WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<Embedding>

    @Query("SELECT * FROM embeddings LIMIT :limit OFFSET :offset")
    suspend fun listPaginated(limit: Int, offset: Int): List<Embedding>

    @Query("SELECT COUNT(*) FROM embeddings")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM embeddings WHERE docId = :docId")
    suspend fun countByDocId(docId: Long): Int

    @Query("SELECT COUNT(*) FROM embeddings WHERE chatId = :chatId")
    suspend fun countByChatId(chatId: Long): Int

    @androidx.room.Transaction
    @Query("DELETE FROM embeddings WHERE docId = :docId")
    suspend fun deleteByDocId(docId: Long)

    @androidx.room.Transaction
    @Query("DELETE FROM embeddings WHERE chatId = :chatId")
    suspend fun deleteByChatId(chatId: Long)

    @Query("DELETE FROM embeddings WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM embeddings WHERE textHash = :textHash")
    suspend fun deleteByTextHash(textHash: String)

    // Cleanup orphaned embeddings (those without valid docId or chatId)
    @Query("DELETE FROM embeddings WHERE docId IS NOT NULL AND docId NOT IN (SELECT id FROM documents)")
    suspend fun deleteOrphanedByDoc(): Int

    @Query("DELETE FROM embeddings WHERE chatId IS NOT NULL AND chatId NOT IN (SELECT id FROM chats)")
    suspend fun deleteOrphanedByChat(): Int
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

    @Query("DELETE FROM rag_chunks WHERE embeddingId IN (:embeddingIds)")
    suspend fun deleteChunksByEmbeddingIds(embeddingIds: List<Long>)

    @Query("SELECT COUNT(*) FROM rag_chunks")
    suspend fun countChunks(): Int

    @Query("SELECT AVG(tokenCount) FROM rag_chunks")
    suspend fun getAverageTokenCount(): Float?
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

@Dao
interface BenchmarkResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(result: BenchmarkResult): Long

    @Query("SELECT * FROM benchmark_results WHERE manifestId = :manifestId ORDER BY runAt DESC")
    fun observeByManifest(manifestId: Long): Flow<List<BenchmarkResult>>

    @Query("SELECT * FROM benchmark_results WHERE manifestId = :manifestId ORDER BY runAt DESC LIMIT 10")
    suspend fun getRecentByManifest(manifestId: Long): List<BenchmarkResult>

    @Query("SELECT * FROM benchmark_results ORDER BY runAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<BenchmarkResult>

    @Query("DELETE FROM benchmark_results WHERE manifestId = :manifestId")
    suspend fun deleteByManifest(manifestId: Long)

    @Query("DELETE FROM benchmark_results WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM benchmark_results WHERE manifestId = :manifestId")
    suspend fun countByManifest(manifestId: Long): Int
}
