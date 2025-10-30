package com.peerchat.app.engine

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ModelStorage {
    /**
     * Import a model file from the given [uri] into the app's private models directory.
     * Returns the absolute path of the copied file on success, or null on failure.
     */
    fun importModel(context: Context, uri: Uri): String? {
        val app = context.applicationContext as Application
        val resolver = app.contentResolver
        val modelsDir = File(app.filesDir, "models").apply { mkdirs() }
        val name = resolveDisplayName(resolver, uri)?.ifBlank { null }
            ?: "model_${System.currentTimeMillis()}.gguf"
        val dest = File(modelsDir, sanitizeFileName(name))

        return runCatching {
            resolver.openInputStream(uri)?.use { input ->
                copyToFile(input, dest)
                dest.absolutePath
            }
        }.getOrNull()
    }

    private fun resolveDisplayName(resolver: ContentResolver, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun copyToFile(input: InputStream, dest: File) {
        FileOutputStream(dest).use { out ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            out.flush()
        }
    }
}
