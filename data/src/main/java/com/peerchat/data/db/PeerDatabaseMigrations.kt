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
}
