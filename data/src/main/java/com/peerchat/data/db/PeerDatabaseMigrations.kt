package com.peerchat.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object PeerDatabaseMigrations {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS model_manifests (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    name TEXT NOT NULL,
                    filePath TEXT NOT NULL,
                    family TEXT NOT NULL,
                    sizeBytes INTEGER NOT NULL,
                    checksumSha256 TEXT NOT NULL,
                    contextLength INTEGER NOT NULL,
                    importedAt INTEGER NOT NULL,
                    sourceUrl TEXT,
                    metadataJson TEXT NOT NULL,
                    isDefault INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_model_manifests_name ON model_manifests(name)")
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Add foreign key constraints and additional indexes
            // Note: SQLite doesn't support adding foreign keys to existing tables,
            // so we recreate tables with proper constraints

            // Backup existing data
            db.execSQL("ALTER TABLE chats RENAME TO chats_old")
            db.execSQL("ALTER TABLE messages RENAME TO messages_old")
            db.execSQL("ALTER TABLE embeddings RENAME TO embeddings_old")
            db.execSQL("ALTER TABLE rag_chunks RENAME TO rag_chunks_old")

            // Recreate tables with foreign keys and improved indexes
            db.execSQL("""
                CREATE TABLE chats (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    folderId INTEGER,
                    title TEXT NOT NULL,
                    systemPrompt TEXT NOT NULL,
                    modelId TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    settingsJson TEXT NOT NULL,
                    FOREIGN KEY (folderId) REFERENCES folders(id) ON DELETE SET NULL
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    chatId INTEGER NOT NULL,
                    role TEXT NOT NULL,
                    contentMarkdown TEXT NOT NULL,
                    tokens INTEGER NOT NULL,
                    ttfsMs INTEGER NOT NULL,
                    tps REAL NOT NULL,
                    contextUsedPct REAL NOT NULL,
                    createdAt INTEGER NOT NULL,
                    metaJson TEXT NOT NULL,
                    FOREIGN KEY (chatId) REFERENCES chats(id) ON DELETE CASCADE
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE embeddings (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    docId INTEGER,
                    chatId INTEGER,
                    textHash TEXT NOT NULL,
                    vector BLOB NOT NULL,
                    dim INTEGER NOT NULL,
                    norm REAL NOT NULL,
                    createdAt INTEGER NOT NULL,
                    FOREIGN KEY (docId) REFERENCES documents(id) ON DELETE CASCADE,
                    FOREIGN KEY (chatId) REFERENCES chats(id) ON DELETE CASCADE
                )
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE rag_chunks (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    docId INTEGER NOT NULL,
                    start INTEGER NOT NULL,
                    end INTEGER NOT NULL,
                    text TEXT NOT NULL,
                    tokenCount INTEGER NOT NULL,
                    embeddingId INTEGER,
                    FOREIGN KEY (docId) REFERENCES documents(id) ON DELETE CASCADE,
                    FOREIGN KEY (embeddingId) REFERENCES embeddings(id) ON DELETE SET NULL
                )
            """.trimIndent())

            // Copy data back
            db.execSQL("""
                INSERT INTO chats (id, folderId, title, systemPrompt, modelId, createdAt, updatedAt, settingsJson)
                SELECT id, folderId, title, systemPrompt, modelId, createdAt, updatedAt, settingsJson FROM chats_old
            """.trimIndent())

            db.execSQL("""
                INSERT INTO messages (id, chatId, role, contentMarkdown, tokens, ttfsMs, tps, contextUsedPct, createdAt, metaJson)
                SELECT id, chatId, role, contentMarkdown, tokens, ttfsMs, tps, contextUsedPct, createdAt, metaJson FROM messages_old
            """.trimIndent())

            db.execSQL("""
                INSERT INTO embeddings (id, docId, chatId, textHash, vector, dim, norm, createdAt)
                SELECT id, docId, chatId, textHash, vector, dim, norm, createdAt FROM embeddings_old
            """.trimIndent())

            db.execSQL("""
                INSERT INTO rag_chunks (id, docId, start, end, text, tokenCount, embeddingId)
                SELECT id, docId, start, end, text, tokenCount, embeddingId FROM rag_chunks_old
            """.trimIndent())

            // Create indexes
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_folderId ON chats(folderId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_updatedAt ON chats(updatedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_createdAt ON chats(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_chats_folderId_updatedAt ON chats(folderId, updatedAt)")

            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId ON messages(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_createdAt ON messages(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_role ON messages(role)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId_createdAt ON messages(chatId, createdAt)")

            db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_hash ON documents(hash)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_mime ON documents(mime)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_createdAt ON documents(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_documents_mime_createdAt ON documents(mime, createdAt)")

            db.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_docId ON embeddings(docId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_chatId ON embeddings(chatId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_textHash ON embeddings(textHash)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_createdAt ON embeddings(createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_docId_createdAt ON embeddings(docId, createdAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_embeddings_chatId_createdAt ON embeddings(chatId, createdAt)")

            db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_docId ON rag_chunks(docId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_embeddingId ON rag_chunks(embeddingId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_tokenCount ON rag_chunks(tokenCount)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_docId_start ON rag_chunks(docId, start)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_rag_chunks_docId_tokenCount ON rag_chunks(docId, tokenCount)")

            // Drop old tables
            db.execSQL("DROP TABLE chats_old")
            db.execSQL("DROP TABLE messages_old")
            db.execSQL("DROP TABLE embeddings_old")
            db.execSQL("DROP TABLE rag_chunks_old")

            // Ensure FTS tables exist and are properly linked
            db.execSQL("DROP TABLE IF EXISTS messages_fts")
            db.execSQL("CREATE VIRTUAL TABLE messages_fts USING fts4(content= messages, contentMarkdown)")

            db.execSQL("DROP TABLE IF EXISTS rag_chunks_fts")
            db.execSQL("CREATE VIRTUAL TABLE rag_chunks_fts USING fts4(content= rag_chunks, text)")

            // Populate FTS tables
            db.execSQL("INSERT INTO messages_fts(rowid, contentMarkdown) SELECT id, contentMarkdown FROM messages")
            db.execSQL("INSERT INTO rag_chunks_fts(rowid, text) SELECT id, text FROM rag_chunks")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS benchmark_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    manifestId INTEGER NOT NULL,
                    promptText TEXT NOT NULL,
                    promptTokens INTEGER NOT NULL,
                    generatedTokens INTEGER NOT NULL,
                    ttftMs INTEGER NOT NULL,
                    totalMs INTEGER NOT NULL,
                    tps REAL NOT NULL,
                    contextUsedPct REAL NOT NULL,
                    errorMessage TEXT,
                    runAt INTEGER NOT NULL,
                    deviceInfo TEXT NOT NULL,
                    FOREIGN KEY (manifestId) REFERENCES model_manifests(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )

            // Create indexes
            db.execSQL("CREATE INDEX IF NOT EXISTS index_benchmark_results_manifestId ON benchmark_results(manifestId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_benchmark_results_runAt ON benchmark_results(runAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_benchmark_results_manifestId_runAt ON benchmark_results(manifestId, runAt)")
        }
    }
}
