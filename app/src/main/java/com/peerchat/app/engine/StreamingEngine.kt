package com.peerchat.app.engine

import com.peerchat.app.util.Logger
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineNative
import com.peerchat.engine.EngineRuntime
import com.peerchat.engine.TokenCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.atomic.AtomicBoolean

sealed interface EngineStreamEvent {
    data class Token(val text: String) : EngineStreamEvent
    data class Terminal(val metrics: EngineMetrics) : EngineStreamEvent
}

object StreamingEngine {
    // Optimized streaming with batching and backpressure management
    private val optimizer = StreamingOptimizer()
    fun stream(
        prompt: String,
        systemPrompt: String?,
        template: String?,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        stop: Array<String>
    ): Flow<EngineStreamEvent> {
        // Get raw stream and optimize it
        val rawStream = createRawStream(prompt, systemPrompt, template, temperature, topP, topK, maxTokens, stop)
        return optimizer.optimizeStream(rawStream)
    }

    // Raw streaming without optimization (for internal use)
    private fun createRawStream(
        prompt: String,
        systemPrompt: String?,
        template: String?,
        temperature: Float,
        topP: Float,
        topK: Int,
        maxTokens: Int,
        stop: Array<String>
    ): Flow<EngineStreamEvent> = callbackFlow {
        EngineRuntime.ensureInitialized()
        val completed = AtomicBoolean(false)
        val firstChunkLogged = AtomicBoolean(false)
        var tokenChars = 0
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
                    if (!firstChunkLogged.get()) {
                        firstChunkLogged.set(true)
                        Logger.i(
                            "StreamingEngine: first_chunk",
                            mapOf("chars" to chunk.length)
                        )
                    }
                    tokenChars += chunk.length
                    Logger.i(
                        "StreamingEngine: chunk",
                        mapOf("chars" to chunk.length, "totalChars" to tokenChars)
                    )
                    val result = trySendBlocking(EngineStreamEvent.Token(chunk))
                    if (result.isFailure) {
                        Logger.w(
                            "StreamingEngine: chunk_delivery_failed",
                            mapOf("reason" to (result.exceptionOrNull()?.message ?: "backpressure"))
                        )
                        EngineNative.abort()
                    }
                }
            } else {
                Logger.i("StreamingEngine: done_chunk_received", mapOf("totalChars" to tokenChars))
                val metrics = EngineRuntime.updateMetricsFromNative()
                completed.set(true)
                trySendBlocking(EngineStreamEvent.Terminal(metrics))
                Logger.i(
                    "StreamingEngine: terminal",
                    mapOf(
                        "tokens" to metrics.generationTokens,
                        "promptTokens" to metrics.promptTokens,
                        "ttfsMs" to metrics.ttfsMs,
                        "totalMs" to metrics.totalMs,
                        "stopReason" to metrics.stopReason,
                        "truncated" to metrics.truncated,
                        "streamedChars" to tokenChars
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
            trySendBlocking(EngineStreamEvent.Terminal(errorMetrics))
            Logger.e(
                "StreamingEngine: start_failed",
                mapOf("error" to (start.exceptionOrNull()?.message ?: "unknown")),
                start.exceptionOrNull()
            )
            close(start.exceptionOrNull())
        }
        awaitClose {
            if (!completed.get()) {
                EngineNative.abort()
            }
        }
    }
}
