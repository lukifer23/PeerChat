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
        if (fields.isEmpty()) return message
        val obj = JSONObject()
        for ((k, v) in fields) obj.put(k, v)
        return "$message | ${obj.toString()}"
    }
}


