package com.peerchat.app.engine

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
    private const val KEY_MODEL_PATH = "modelPath"
    private const val KEY_THREADS = "threads"
    private const val KEY_CONTEXT_LENGTH = "contextLength"
    private const val KEY_GPU_LAYERS = "gpuLayers"
    private const val KEY_USE_VULKAN = "useVulkan"

    private fun getEncryptedPrefs(context: Context): EncryptedSharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    fun load(context: Context): StoredEngineConfig? {
        return try {
            val prefs = getEncryptedPrefs(context)
            val path = prefs.getString(KEY_MODEL_PATH, null) ?: return null
            val threads = prefs.getInt(KEY_THREADS, 6)
            val contextLength = prefs.getInt(KEY_CONTEXT_LENGTH, 4096)
            val gpuLayers = prefs.getInt(KEY_GPU_LAYERS, 20)
            val useVulkan = prefs.getBoolean(KEY_USE_VULKAN, true)
            StoredEngineConfig(path, threads, contextLength, gpuLayers, useVulkan)
        } catch (e: Exception) {
            null
        }
    }

    fun save(context: Context, config: StoredEngineConfig) {
        try {
            val prefs = getEncryptedPrefs(context)
            prefs.edit()
                .putString(KEY_MODEL_PATH, config.modelPath)
                .putInt(KEY_THREADS, config.threads)
                .putInt(KEY_CONTEXT_LENGTH, config.contextLength)
                .putInt(KEY_GPU_LAYERS, config.gpuLayers)
                .putBoolean(KEY_USE_VULKAN, config.useVulkan)
                .apply()
        } catch (e: Exception) {
        }
    }

    fun clear(context: Context) {
        try {
            val prefs = getEncryptedPrefs(context)
            prefs.edit().clear().apply()
        } catch (e: Exception) {
        }
    }
}

object ModelStorage {
    suspend fun importModel(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = sanitizeFileName(resolveDisplayName(context, uri) ?: "model.gguf")
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
        val tempFile = File.createTempFile("import_", ".tmp", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null
            
            if (dest.exists()) dest.delete()
            tempFile.renameTo(dest) || run {
                tempFile.copyTo(dest, overwrite = true)
                tempFile.delete()
            }
            dest.absolutePath
        } catch (e: Exception) {
            tempFile.delete()
            null
        }
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

    private fun sanitizeFileName(raw: String): String {
        val trimmed = raw.trim().take(200)
        val cleaned = trimmed.replace(Regex("[\\/:*?\"<>|]"), "_")
            .replace(Regex("[\u0000-\u001F]"), "_")
            .replace(Regex("\u007F"), "_")
        val noTraversal = cleaned.replace("..", "_")
        return if (noTraversal.isBlank()) "model.gguf" else noTraversal
    }
}
