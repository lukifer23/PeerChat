package com.peerchat.app.engine

import android.content.Context
import android.net.Uri
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class StoredEngineConfig(
    val modelPath: String,
    val threads: Int,
    val contextLength: Int,
    val gpuLayers: Int,
    val useVulkan: Boolean
) {
    fun toEngineConfig(): EngineRuntime.EngineConfig =
        EngineRuntime.EngineConfig(modelPath, threads, contextLength, gpuLayers, useVulkan)
}

object ModelConfigStore {
    private const val PREFS = "engine_config"

    fun load(context: Context): StoredEngineConfig? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val path = prefs.getString("modelPath", null) ?: return null
        val threads = prefs.getInt("threads", 6)
        val contextLength = prefs.getInt("contextLength", 4096)
        val gpuLayers = prefs.getInt("gpuLayers", 20)
        val useVulkan = prefs.getBoolean("useVulkan", true)
        return StoredEngineConfig(path, threads, contextLength, gpuLayers, useVulkan)
    }

    fun save(context: Context, config: StoredEngineConfig) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("modelPath", config.modelPath)
            .putInt("threads", config.threads)
            .putInt("contextLength", config.contextLength)
            .putInt("gpuLayers", config.gpuLayers)
            .putBoolean("useVulkan", config.useVulkan)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

object ModelStorage {
    suspend fun importModel(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolveDisplayName(context, uri) ?: "model.gguf"
        val modelsDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
        var dest = File(modelsDir, name)
        var suffix = 1
        while (dest.exists()) {
            val base = name.substringBeforeLast(".", name)
            val ext = name.substringAfterLast('.', "")
            val candidate = if (ext.isEmpty()) "$base($suffix)" else "$base($suffix).$ext"
            dest = File(modelsDir, candidate)
            suffix++
        }
        resolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: return@withContext null
        dest.absolutePath
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    return cursor.getString(idx)
                }
            }
        }
        return uri.lastPathSegment
    }
}
