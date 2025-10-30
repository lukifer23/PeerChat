package com.peerchat.app.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelStateCache(private val context: Context) {
    private val repo: ModelRepository by lazy { ServiceRegistry.modelRepository }

    suspend fun restore(chatId: Long): Boolean = withContext(Dispatchers.IO) { repo.restoreKv(chatId) }

    suspend fun capture(chatId: Long) = withContext(Dispatchers.IO) { repo.captureKv(chatId) }

    suspend fun clear(chatId: Long) = withContext(Dispatchers.IO) { repo.clearKv(chatId) }

    suspend fun clearAll() = withContext(Dispatchers.IO) { repo.clearAllKv() }
}
