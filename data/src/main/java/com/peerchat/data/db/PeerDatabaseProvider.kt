package com.peerchat.data.db

import android.content.Context

object PeerDatabaseProvider {
    @Volatile private var INSTANCE: PeerDatabase? = null

    fun get(context: Context, isDebug: Boolean = false): PeerDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: SecureDatabaseProvider.getDatabase(context, isDebug).also { INSTANCE = it }
        }
    }
}
