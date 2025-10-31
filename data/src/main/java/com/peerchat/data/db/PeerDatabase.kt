package com.peerchat.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        Folder::class,
        Chat::class,
        Message::class,
        Document::class,
        Embedding::class,
        RagChunk::class,
        MessageFts::class,
        RagChunkFts::class,
        ModelManifest::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class PeerDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun documentDao(): DocumentDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun ragDao(): RagDao
    abstract fun modelManifestDao(): ModelManifestDao
}
