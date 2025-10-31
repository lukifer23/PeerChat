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

    /**
     * Sanitizes a filename to prevent path traversal attacks.
     * Removes path separators, relative path components, and other dangerous characters.
     *
     * @param name The original filename.
     * @return A sanitized filename safe for use in file operations.
     */
    private fun sanitizeFileName(name: String): String {
        // Remove path separators and relative path components
        var sanitized = name
            .replace(Regex("[\\\\/]"), "_") // Remove path separators
            .replace(Regex("\\.\\.+"), "_") // Remove .. sequences
            .replace(Regex("^\\."), "") // Remove leading dot
            .replace(Regex("\\.$"), "") // Remove trailing dot
        
        // Remove dangerous characters but preserve safe ones
        sanitized = sanitized.replace(Regex("[^A-Za-z0-9._-]"), "_")
        
        // Limit length and prevent empty names
        sanitized = sanitized.take(255).ifBlank { "model_file" }
        
        // Ensure it doesn't start or end with special characters
        return sanitized.trimStart('_', '-').trimEnd('_', '-').ifBlank { "model_file" }
    }

    /**
     * Atomically copies input stream to destination file.
     * Uses a temporary file and atomic rename to ensure file integrity.
     *
     * @param input The input stream to copy.
     * @param dest The destination file (must be in a safe directory).
     */
    private fun copyToFile(input: InputStream, dest: File) {
        // Validate destination is in expected directory
        val parent = dest.parentFile ?: throw IllegalArgumentException("Invalid destination path")
        if (!parent.exists() && !parent.mkdirs()) {
            throw SecurityException("Cannot create destination directory")
        }
        
        // Use atomic write: write to temp file, then rename
        val tempFile = File(parent, "${dest.name}.tmp.${System.currentTimeMillis()}")
        try {
            FileOutputStream(tempFile).use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
                out.flush()
                out.fd.sync() // Force sync to disk
            }
            
            // Atomic rename
            if (!tempFile.renameTo(dest)) {
                throw SecurityException("Failed to atomically move file to destination")
            }
        } catch (e: Exception) {
            // Cleanup temp file on error
            runCatching { tempFile.delete() }
            throw e
        }
    }
}
