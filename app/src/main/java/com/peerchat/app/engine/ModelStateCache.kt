package com.peerchat.app.engine

import android.content.Context
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ModelStateCache(private val context: Context) {
    private val cacheDir: File by lazy {
        File(context.filesDir, "kv_cache").apply { if (!exists()) mkdirs() }
    }

    private fun stateFile(chatId: Long): File = File(cacheDir, "chat_$chatId.bin")

    suspend fun restore(chatId: Long): Boolean = withContext(Dispatchers.IO) {
        val file = stateFile(chatId)
        if (!file.exists()) return@withContext false
        val bytes = runCatching { file.readBytes() }.getOrNull().orEmpty()
        if (bytes.isEmpty()) return@withContext false
        EngineRuntime.restoreState(bytes)
    }

    suspend fun capture(chatId: Long) = withContext(Dispatchers.IO) {
        val snapshot = EngineRuntime.captureState() ?: return@withContext
        val file = stateFile(chatId)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(snapshot)
        }
    }

    suspend fun clear(chatId: Long) = withContext(Dispatchers.IO) {
        stateFile(chatId).delete()
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
