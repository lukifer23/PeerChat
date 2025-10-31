package com.peerchat.app.util

import android.content.Context
import android.os.Debug
import android.os.Process
import android.util.Log
import com.peerchat.app.BuildConfig
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.measureTime

object Logger {
    private const val DEFAULT_TAG = "PeerChat"
    @Volatile private var tag: String = DEFAULT_TAG
    @Volatile private var fileLogger: FileRingLogger? = null
    private val performanceTimers = ConcurrentHashMap<String, Long>()

    fun init(context: Context) {
        if (BuildConfig.DEBUG) {
            fileLogger = FileRingLogger(context)
        }
    }

    fun setTag(newTag: String) { tag = newTag.ifBlank { DEFAULT_TAG } }

    /**
     * Start performance profiling for an operation
     */
    fun startPerfTimer(operation: String, fields: Map<String, Any?> = emptyMap()) {
        val startTime = System.nanoTime()
        performanceTimers[operation] = startTime

        d("PERF_START: $operation", fields + mapOf(
            "perfOperation" to operation,
            "timestamp" to startTime,
            "threadId" to Thread.currentThread().id,
            "threadName" to Thread.currentThread().name
        ))
    }

    /**
     * End performance profiling and log results
     */
    fun endPerfTimer(operation: String, fields: Map<String, Any?> = emptyMap()) {
        val endTime = System.nanoTime()
        val startTime = performanceTimers.remove(operation)

        if (startTime != null) {
            val durationMs = (endTime - startTime) / 1_000_000.0
            val memoryInfo = getMemoryInfo()

            i("PERF_END: $operation", fields + mapOf(
                "perfOperation" to operation,
                "durationMs" to durationMs,
                "startTime" to startTime,
                "endTime" to endTime,
                "threadId" to Thread.currentThread().id,
                "threadName" to Thread.currentThread().name
            ) + memoryInfo)
        } else {
            w("PERF_ERROR: Timer not found for operation: $operation")
        }
    }

    /**
     * Profile a block of code with automatic timing
     */
    inline fun <T> profile(operation: String, fields: Map<String, Any?> = emptyMap(), block: () -> T): T {
        startPerfTimer(operation, fields)
        return try {
            block()
        } finally {
            endPerfTimer(operation, fields)
        }
    }

    /**
     * Log performance metrics with memory usage
     */
    fun perf(message: String, fields: Map<String, Any?> = emptyMap()) {
        val memoryInfo = getMemoryInfo()
        val threadInfo = mapOf(
            "threadId" to Thread.currentThread().id,
            "threadName" to Thread.currentThread().name,
            "processId" to Process.myPid(),
            "cpuTime" to Debug.threadCpuTimeNanos()
        )

        i("PERF: $message", fields + memoryInfo + threadInfo)
    }

    /**
     * Log detailed error context for debugging
     */
    fun errorContext(message: String, error: Throwable?, fields: Map<String, Any?> = emptyMap()) {
        val contextInfo = mapOf(
            "threadId" to Thread.currentThread().id,
            "threadName" to Thread.currentThread().name,
            "processId" to Process.myPid(),
            "threadCpuTime" to Debug.threadCpuTimeNanos(),
            "availableProcessors" to Runtime.getRuntime().availableProcessors(),
            "freeMemoryMB" to Runtime.getRuntime().freeMemory() / 1024 / 1024,
            "totalMemoryMB" to Runtime.getRuntime().totalMemory() / 1024 / 1024,
            "maxMemoryMB" to Runtime.getRuntime().maxMemory() / 1024 / 1024
        ) + getMemoryInfo()

        e("ERROR_CONTEXT: $message", fields + contextInfo, error)
    }

    /**
     * Get detailed memory information
     */
    private fun getMemoryInfo(): Map<String, Any?> {
        val runtime = Runtime.getRuntime()
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)

        return mapOf(
            "heapAllocatedMB" to (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            "heapFreeMB" to runtime.freeMemory() / 1024 / 1024,
            "heapTotalMB" to runtime.totalMemory() / 1024 / 1024,
            "heapMaxMB" to runtime.maxMemory() / 1024 / 1024,
            "nativeAllocatedKB" to memoryInfo.nativePss,
            "dalvikAllocatedKB" to memoryInfo.dalvikPss,
            "totalAllocatedKB" to memoryInfo.totalPss,
            "gcCount" to (Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0),
            "gcTime" to (Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: 0),
            "blocksCanBeFreedKB" to ((Debug.getRuntimeStat("art.gc.bytes-allocated")?.toLongOrNull() ?: 0) / 1024)
        )
    }

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


