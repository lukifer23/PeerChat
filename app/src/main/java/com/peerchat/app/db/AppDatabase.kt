package com.peerchat.app.db

import android.content.Context
import androidx.room.Room
import com.peerchat.data.db.PeerDatabase

object AppDatabase {
    @Volatile private var INSTANCE: PeerDatabase? = null

    fun get(context: Context): PeerDatabase {
        return INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                PeerDatabase::class.java,
                "peer.db"
            ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
        }
    }
}

