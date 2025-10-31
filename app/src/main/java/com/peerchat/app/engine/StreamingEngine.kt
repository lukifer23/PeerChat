package com.peerchat.app.engine

import android.app.ActivityManager
import android.content.Context
import com.peerchat.app.util.Logger
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineNative
import com.peerchat.engine.EngineRuntime
import com.peerchat.engine.TokenCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface EngineStreamEvent {
    data class Token(val text: String) : EngineStreamEvent
    data class Terminal(val metrics: EngineMetrics) : EngineStreamEvent
    data class Error(val message: String, val recoverable: Boolean = false) : EngineStreamEvent
    data class Checkpoint(val state: ByteArray) : EngineStreamEvent
}

object StreamingEngine {
    // Memory pressure thresholds
    private const val MEMORY_PRESSURE_THRESHOLD = 0.85 // 85% memory usage
    private const val MEMORY_CHECK_INTERVAL_MS = 10000L // Check every 10 seconds (reduced frequency)
    private var lastMemoryCheckResult: Boolean? = null
    private var lastMemoryCheckTime: Long = 0
    
    /**
     * Check if device is under memory pressure
     */
    private fun isMemoryPressure(context: Context?): Boolean {
        if (context == null) return false
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val usedMemory = memInfo.totalMem - memInfo.availMem
            val usageRatio = usedMemory.toDouble() / memInfo.totalMem.toDouble()
            usageRatio >= MEMORY_PRESSURE_THRESHOLD
        } catch (e: Exception) {
            Logger.w("StreamingEngine: memory check failed", mapOf("error" to e.message), e)
            false
        }
    }
    
    fun stream(
        prompt: String,
        systemPrompt: String?,
        template: String?,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        stop: Array<String>,
        context: Context? = null
    ): Flow<EngineStreamEvent> = callbackFlow {
        EngineRuntime.ensureInitialized()
        val completed = AtomicBoolean(false)
        val firstChunkLogged = AtomicBoolean(false)
        val lastMemoryCheck = AtomicLong(System.currentTimeMillis())
        var tokenChars = 0
        var memoryPressureDetected = false
        var abortedForPressure = false
        
        Logger.i(
            "StreamingEngine: start",
            mapOf(
                "promptLength" to prompt.length,
                "systemPrompt" to (systemPrompt?.length ?: 0),
                "template" to template,
                "temperature" to temperature,
                "topP" to topP,
                "topK" to topK,
                "maxTokens" to maxTokens,
                "stopCount" to stop.size
            )
        )
        
        val callback = TokenCallback { chunk, done ->
            if (!done) {
                if (chunk.isNotEmpty()) {
                    // Check memory pressure periodically (cached result for performance)
                    val now = System.currentTimeMillis()
                    if (context != null && now - lastMemoryCheck.get() > MEMORY_CHECK_INTERVAL_MS) {
                        lastMemoryCheck.set(now)
                        memoryPressureDetected = isMemoryPressure(context)
                        // Update global cache
                        lastMemoryCheckResult = memoryPressureDetected
                        lastMemoryCheckTime = now
                        if (memoryPressureDetected && !abortedForPressure) {
                            abortedForPressure = true
                            Logger.w("StreamingEngine: memory pressure detected, aborting generation")
                            trySend(EngineStreamEvent.Error("Memory pressure", recoverable = true))
                            EngineNative.abort()
                            return@TokenCallback
                        }
                    } else if (lastMemoryCheckResult == true && (now - lastMemoryCheckTime) < MEMORY_CHECK_INTERVAL_MS * 2) {
                        // Use cached result if recent and positive (memory pressure persists)
                        memoryPressureDetected = true
                        if (!abortedForPressure) {
                            abortedForPressure = true
                            Logger.w("StreamingEngine: using cached memory pressure result, aborting generation")
                            trySend(EngineStreamEvent.Error("Memory pressure", recoverable = true))
                            EngineNative.abort()
                            return@TokenCallback
                        }
                    }

                    if (!firstChunkLogged.get()) {
                        firstChunkLogged.set(true)
                        Logger.i(
                            "StreamingEngine: first_chunk",
                            mapOf("chars" to chunk.length)
                        )
                    }
                    tokenChars += chunk.length

                    // Backpressure: Check if channel is closed before sending
                    if (isClosedForSend) {
                        Logger.w("StreamingEngine: channel closed, aborting")
                        EngineNative.abort()
                        return@TokenCallback
                    }

                    // Try to send with backpressure handling
                    val result = trySend(EngineStreamEvent.Token(chunk))
                    if (result.isFailure) {
                        Logger.w(
                            "StreamingEngine: chunk_delivery_failed",
                            mapOf("closed" to isClosedForSend, "isClosed" to result.isClosed)
                        )
                        // Abort generation if channel is closed or failing
                        EngineNative.abort()
                    }
                }
            } else {
                Logger.i("StreamingEngine: done_chunk_received", mapOf("totalChars" to tokenChars))
                val metrics = EngineRuntime.updateMetricsFromNative()
                completed.set(true)

                // Send terminal event if channel is still open
                if (!isClosedForSend) {
                    val terminalResult = trySend(EngineStreamEvent.Terminal(metrics))
                    if (terminalResult.isFailure) {
                        Logger.w(
                            "StreamingEngine: terminal_delivery_failed",
                            mapOf("closed" to isClosedForSend)
                        )
                    }
                }

                Logger.i(
                    "StreamingEngine: terminal",
                    mapOf(
                        "tokens" to metrics.generationTokens,
                        "promptTokens" to metrics.promptTokens,
                        "ttfsMs" to metrics.ttfsMs,
                        "totalMs" to metrics.totalMs,
                        "stopReason" to metrics.stopReason,
                        "truncated" to metrics.truncated,
                        "streamedChars" to tokenChars,
                        "memoryPressure" to memoryPressureDetected
                    )
                )
                close()
            }
        }
        
        val start = runCatching {
            EngineNative.generateStream(
                prompt,
                systemPrompt,
                template,
                temperature,
                topP,
                topK,
                maxTokens,
                stop,
                callback
            )
        }
        
        if (start.isFailure) {
            completed.set(true)
            val metrics = EngineRuntime.updateMetricsFromNative()
            val errorMetrics = if (metrics.isError) metrics else metrics.copy(stopReason = "error")
            
            if (!isClosedForSend) {
                trySend(errorMetrics.let { EngineStreamEvent.Terminal(it) })
            }
            
            Logger.e(
                "StreamingEngine: start_failed",
                mapOf("error" to (start.exceptionOrNull()?.message ?: "unknown")),
                start.exceptionOrNull()
            )
            close(start.exceptionOrNull())
        }
        
        awaitClose {
            // Ensure cleanup: abort any ongoing generation
            if (!completed.get()) {
                Logger.i("StreamingEngine: awaitClose - aborting generation")
                EngineNative.abort()
            }
            Logger.d("StreamingEngine: cleanup complete")
        }
    }.flowOn(Dispatchers.IO)
}
