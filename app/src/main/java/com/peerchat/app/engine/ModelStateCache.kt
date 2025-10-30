package com.peerchat.app.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelStateCache @Inject constructor(
    private val repo: ModelRepository
) {

    suspend fun restore(chatId: Long): Boolean = withContext(Dispatchers.IO) { repo.restoreKv(chatId) }

    suspend fun capture(chatId: Long) = withContext(Dispatchers.IO) { repo.captureKv(chatId) }

    suspend fun clear(chatId: Long) = withContext(Dispatchers.IO) { repo.clearKv(chatId) }

    suspend fun clearAll() = withContext(Dispatchers.IO) { repo.clearAllKv() }

    fun stats(): StateFlow<ModelRepository.CacheStats> = repo.cacheStats
}
