package com.peerchat.app.db

import android.content.Context
import com.peerchat.data.db.PeerDatabase
import com.peerchat.data.db.PeerDatabaseProvider

object AppDatabase {
    @Volatile private var INSTANCE: PeerDatabase? = null

    fun get(context: Context): PeerDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: PeerDatabaseProvider.get(context).also { INSTANCE = it }
        }
    }
}
