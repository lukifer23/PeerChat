package com.peerchat.data.db

import android.content.Context
import androidx.room.Room

object PeerDatabaseProvider {
    @Volatile private var INSTANCE: PeerDatabase? = null

    fun get(context: Context): PeerDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: build(context).also { INSTANCE = it }
        }
    }

    private fun build(context: Context): PeerDatabase {
        return Room.databaseBuilder(context.applicationContext, PeerDatabase::class.java, "peer.db")
            .addMigrations(PeerDatabaseMigrations.MIGRATION_1_2)
            .build()
    }
}
