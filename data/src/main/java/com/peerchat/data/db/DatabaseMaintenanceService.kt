package com.peerchat.data.db

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Database maintenance service for data integrity, cleanup, and optimization
 */
object DatabaseMaintenanceService {

    private const val TAG = "DatabaseMaintenance"

    /**
     * Comprehensive database maintenance operation
     */
    suspend fun performMaintenance(context: Context, isDebug: Boolean = false): MaintenanceResult {
        return withContext(Dispatchers.IO) {
            val database = PeerDatabaseProvider.get(context, isDebug)

            try {
                database.withTransaction {
                    val startTime = System.currentTimeMillis()

                    // Clean up orphaned records
                    val orphanedEmbeddings = database.embeddingDao().deleteOrphanedByDoc() +
                                           database.embeddingDao().deleteOrphanedByChat()

                    // Clean up orphaned RAG chunks
                    val orphanedChunks = database.openHelper.writableDatabase.compileStatement(
                        "DELETE FROM rag_chunks WHERE embeddingId IS NOT NULL AND embeddingId NOT IN (SELECT id FROM embeddings)"
                    ).use { statement ->
                        statement.executeUpdateDelete().toInt()
                    }

                    // Optimize database (rebuild indexes and vacuum)
                    database.openHelper.writableDatabase.execSQL("VACUUM")

                    // Analyze tables for query optimization
                    database.openHelper.writableDatabase.execSQL("ANALYZE")

                    val endTime = System.currentTimeMillis()
                    val duration = endTime - startTime

                    MaintenanceResult.Success(
                        durationMs = duration,
                        orphanedEmbeddingsCleaned = orphanedEmbeddings,
                        orphanedChunksCleaned = orphanedChunks
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Database maintenance failed", e)
                MaintenanceResult.Failure(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Get database statistics
     */
    suspend fun getDatabaseStats(context: Context): DatabaseStats {
        return withContext(Dispatchers.IO) {
            val database = PeerDatabaseProvider.get(context)

            try {
                val folderCount = database.query("SELECT COUNT(*) FROM folders", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                val chatCount = database.query("SELECT COUNT(*) FROM chats", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                val messageCount = database.query("SELECT COUNT(*) FROM messages", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                val documentCount = database.query("SELECT COUNT(*) FROM documents", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                val embeddingCount = database.query("SELECT COUNT(*) FROM embeddings", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                val chunkCount = database.query("SELECT COUNT(*) FROM rag_chunks", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                val modelCount = database.query("SELECT COUNT(*) FROM model_manifests", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }

                // Get database file size
                val dbFile = context.getDatabasePath("peerchat.db")
                val dbSize = if (dbFile.exists()) dbFile.length() else 0L

                DatabaseStats.Success(
                    folderCount = folderCount,
                    chatCount = chatCount,
                    messageCount = messageCount,
                    documentCount = documentCount,
                    embeddingCount = embeddingCount,
                    chunkCount = chunkCount,
                    modelCount = modelCount,
                    databaseSizeBytes = dbSize
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get database stats", e)
                DatabaseStats.Failure(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Validate data integrity
     */
    suspend fun validateIntegrity(context: Context): IntegrityResult {
        return withContext(Dispatchers.IO) {
            val database = PeerDatabaseProvider.get(context)

            try {
                val issues = mutableListOf<String>()

                // Check for orphaned chats
                val orphanedChats = database.query(
                    "SELECT COUNT(*) FROM chats WHERE folderId IS NOT NULL AND folderId NOT IN (SELECT id FROM folders)",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                if (orphanedChats > 0) {
                    issues.add("$orphanedChats orphaned chats found")
                }

                // Check for orphaned messages
                val orphanedMessages = database.query(
                    "SELECT COUNT(*) FROM messages WHERE chatId NOT IN (SELECT id FROM chats)",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                if (orphanedMessages > 0) {
                    issues.add("$orphanedMessages orphaned messages found")
                }

                // Check for orphaned embeddings
                val orphanedEmbeddings = database.query(
                    "SELECT COUNT(*) FROM embeddings WHERE (docId IS NOT NULL AND docId NOT IN (SELECT id FROM documents)) OR (chatId IS NOT NULL AND chatId NOT IN (SELECT id FROM chats))",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                if (orphanedEmbeddings > 0) {
                    issues.add("$orphanedEmbeddings orphaned embeddings found")
                }

                // Check for orphaned chunks
                val orphanedChunks = database.query(
                    "SELECT COUNT(*) FROM rag_chunks WHERE docId NOT IN (SELECT id FROM documents) OR (embeddingId IS NOT NULL AND embeddingId NOT IN (SELECT id FROM embeddings))",
                    null
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(0) else 0
                }
                if (orphanedChunks > 0) {
                    issues.add("$orphanedChunks orphaned RAG chunks found")
                }

                if (issues.isEmpty()) {
                    IntegrityResult.Valid
                } else {
                    IntegrityResult.Invalid(issues)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Integrity check failed", e)
                IntegrityResult.Error(e.message ?: "Unknown error")
            }
        }
    }

}

/**
 * Maintenance operation result
 */
sealed class MaintenanceResult {
    data class Success(
        val durationMs: Long,
        val orphanedEmbeddingsCleaned: Int,
        val orphanedChunksCleaned: Int
    ) : MaintenanceResult()

    data class Failure(val error: String) : MaintenanceResult()
}

/**
 * Database statistics result
 */
sealed class DatabaseStats {
    data class Success(
        val folderCount: Int,
        val chatCount: Int,
        val messageCount: Int,
        val documentCount: Int,
        val embeddingCount: Int,
        val chunkCount: Int,
        val modelCount: Int,
        val databaseSizeBytes: Long
    ) : DatabaseStats()

    data class Failure(val error: String) : DatabaseStats()
}

/**
 * Data integrity check result
 */
sealed class IntegrityResult {
    object Valid : IntegrityResult()
    data class Invalid(val issues: List<String>) : IntegrityResult()
    data class Error(val message: String) : IntegrityResult()
}
