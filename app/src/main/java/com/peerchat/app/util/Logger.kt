package com.peerchat.app.util

import android.content.Context
import android.util.Log
import com.peerchat.app.BuildConfig
import org.json.JSONObject

object Logger {
    private const val DEFAULT_TAG = "PeerChat"
    @Volatile private var tag: String = DEFAULT_TAG
    @Volatile private var fileLogger: FileRingLogger? = null

    fun init(context: Context) {
        if (BuildConfig.DEBUG) {
            fileLogger = FileRingLogger(context)
        }
    }

    fun setTag(newTag: String) { tag = newTag.ifBlank { DEFAULT_TAG } }

    fun d(message: String, fields: Map<String, Any?> = emptyMap()) {
        val msg = format(message, fields)
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, msg)
        fileLogger?.append("D/" + tag + ": " + msg)
    }

    fun i(message: String, fields: Map<String, Any?> = emptyMap()) {
        val msg = format(message, fields)
        Log.i(tag, msg)
        fileLogger?.append("I/" + tag + ": " + msg)
    }

    fun w(message: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) {
        val msg = format(message, fields)
        Log.w(tag, msg, throwable)
        fileLogger?.append("W/" + tag + ": " + msg + (throwable?.message?.let { " | $it" } ?: ""))
    }

    fun e(message: String, fields: Map<String, Any?> = emptyMap(), throwable: Throwable? = null) {
        val msg = format(message, fields)
        Log.e(tag, msg, throwable)
        fileLogger?.append("E/" + tag + ": " + msg + (throwable?.message?.let { " | $it" } ?: ""))
    }

    private fun format(message: String, fields: Map<String, Any?>): String {
        if (fields.isEmpty()) return redactSensitiveData(message)
        val obj = JSONObject()
        for ((k, v) in fields) {
            // Redact sensitive fields
            val value = if (k.contains("path", ignoreCase = true) || 
                            k.contains("uri", ignoreCase = true) ||
                            k.contains("token", ignoreCase = true) ||
                            k.contains("password", ignoreCase = true) ||
                            k.contains("secret", ignoreCase = true) ||
                            k.contains("key", ignoreCase = true)) {
                redactValue(v?.toString() ?: "null")
            } else {
                v
            }
            obj.put(k, value)
        }
        return "${redactSensitiveData(message)} | ${obj.toString()}"
    }
    
    /**
     * Redacts sensitive data from log messages.
     * Removes file paths, URIs, and other potentially sensitive information.
     */
    private fun redactSensitiveData(message: String): String {
        return message
            .replace(Regex("/[\\w/.-]+/[\\w.-]+\\.gguf"), "[MODEL_FILE]")
            .replace(Regex("file://[^\\s]+"), "[FILE_URI]")
            .replace(Regex("content://[^\\s]+"), "[CONTENT_URI]")
            .replace(Regex("/data/[^\\s]+"), "[APP_DATA]")
            .replace(Regex("\\b[A-Za-z0-9]{32,}\\b"), "[HASH]") // Redact long alphanumeric strings (potential hashes)
    }
    
    /**
     * Redacts sensitive values while preserving structure.
     */
    private fun redactValue(value: String): String {
        if (value.length > 100) {
            return "[LONG_VALUE_${value.length}_CHARS]"
        }
        if (value.contains("/") || value.contains("://")) {
            return "[PATH_OR_URI]"
        }
        return "[REDACTED]"
    }
}


