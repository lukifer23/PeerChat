package com.peerchat.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.ForeignKey
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
    foreignKeys = [
        ForeignKey(
            entity = Folder::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("folderId"),
        Index("updatedAt"),
        Index("createdAt"),
        Index(value = ["folderId", "updatedAt"])
    ]
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
    foreignKeys = [
        ForeignKey(
            entity = Chat::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("chatId"),
        Index("createdAt"),
        Index("role"),
        Index(value = ["chatId", "createdAt"])
    ]
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

@Entity(
    tableName = "documents",
    indices = [
        Index("hash", unique = true),
        Index("mime"),
        Index("createdAt"),
        Index(value = ["mime", "createdAt"])
    ]
)
data class Document(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val title: String,
    val hash: String,
    val mime: String,
    val textBytes: ByteArray,
    val createdAt: Long,
    val metaJson: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Document

        if (id != other.id) return false
        if (uri != other.uri) return false
        if (title != other.title) return false
        if (hash != other.hash) return false
        if (mime != other.mime) return false
        if (!textBytes.contentEquals(other.textBytes)) return false
        if (createdAt != other.createdAt) return false
        if (metaJson != other.metaJson) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + hash.hashCode()
        result = 31 * result + mime.hashCode()
        result = 31 * result + textBytes.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + metaJson.hashCode()
        return result
    }
}

@Entity(
    tableName = "embeddings",
    foreignKeys = [
        ForeignKey(
            entity = Document::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Chat::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("docId"),
        Index("chatId"),
        Index("textHash"),
        Index("createdAt"),
        Index(value = ["docId", "createdAt"]),
        Index(value = ["chatId", "createdAt"])
    ]
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
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Embedding

        if (id != other.id) return false
        if (docId != other.docId) return false
        if (chatId != other.chatId) return false
        if (textHash != other.textHash) return false
        if (!vector.contentEquals(other.vector)) return false
        if (dim != other.dim) return false
        if (norm != other.norm) return false
        if (createdAt != other.createdAt) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (docId?.hashCode() ?: 0)
        result = 31 * result + (chatId?.hashCode() ?: 0)
        result = 31 * result + textHash.hashCode()
        result = 31 * result + vector.contentHashCode()
        result = 31 * result + dim
        result = 31 * result + norm.hashCode()
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

@Entity(
    tableName = "rag_chunks",
    foreignKeys = [
        ForeignKey(
            entity = Document::class,
            parentColumns = ["id"],
            childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Embedding::class,
            parentColumns = ["id"],
            childColumns = ["embeddingId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("docId"),
        Index("embeddingId"),
        Index("tokenCount"),
        Index(value = ["docId", "start"]),
        Index(value = ["docId", "tokenCount"])
    ]
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

@Entity(
    tableName = "benchmark_results",
    foreignKeys = [
        ForeignKey(
            entity = ModelManifest::class,
            parentColumns = ["id"],
            childColumns = ["manifestId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("manifestId"),
        Index("runAt"),
        Index(value = ["manifestId", "runAt"])
    ]
)
data class BenchmarkResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val manifestId: Long,
    val promptText: String,
    val promptTokens: Int,
    val generatedTokens: Int,
    val ttftMs: Long, // Time to First Token
    val totalMs: Long,
    val tps: Float, // Tokens Per Second
    val contextUsedPct: Float,
    val prefillMs: Long = 0L, // Prefill phase duration
    val decodeMs: Long = 0L, // Decode phase duration
    val memoryUsageMB: Long = 0L, // Peak memory usage during benchmark
    val gcCount: Int = 0, // Number of GC events during benchmark
    val threadCpuTimeNs: Long = 0L, // CPU time spent on benchmark thread
    val gpuMode: Boolean = true, // true = GPU accelerated, false = CPU only
    val errorMessage: String?, // Null if successful
    val runAt: Long,
    val deviceInfo: String, // Device/hardware info
)
