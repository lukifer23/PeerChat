package com.peerchat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders")
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "chats",
    indices = [Index("folderId")]
)
data class Chat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: Long?,
    val title: String,
    val systemPrompt: String,
    val modelId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val settingsJson: String,
)

@Entity(
    tableName = "messages",
    indices = [Index("chatId")]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chatId: Long,
    val role: String,
    val contentMarkdown: String,
    val tokens: Int,
    val ttfsMs: Long,
    val tps: Float,
    val contextUsedPct: Float,
    val createdAt: Long,
    val metaJson: String,
)

@Entity(tableName = "documents")
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val hash: String,
    val mime: String,
    val textBytes: ByteArray,
    val createdAt: Long,
    val metaJson: String,
)

@Entity(
    tableName = "embeddings",
    indices = [Index("docId"), Index("chatId"), Index("textHash")]
)
data class Embedding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long?,
    val chatId: Long?,
    val textHash: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
    val dim: Int,
    val norm: Float,
    val createdAt: Long,
)

@Entity(
    tableName = "rag_chunks",
    indices = [Index("docId")]
)
data class RagChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val start: Int,
    val end: Int,
    val text: String,
    val tokenCount: Int,
    val embeddingId: Long?,
)

@Fts4(contentEntity = Message::class)
@Entity(tableName = "messages_fts")
data class MessageFts(
    val contentMarkdown: String,
)

@Fts4(contentEntity = RagChunk::class)
@Entity(tableName = "rag_chunks_fts")
data class RagChunkFts(
    val text: String,
)

@Entity(
    tableName = "model_manifests",
    indices = [Index(value = ["name"], unique = true)]
)
data class ModelManifest(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val filePath: String,
    val family: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val contextLength: Int,
    val importedAt: Long,
    val sourceUrl: String?,
    val metadataJson: String,
    val isDefault: Boolean,
)
