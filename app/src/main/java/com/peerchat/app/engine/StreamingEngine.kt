package com.peerchat.app.engine

import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineNative
import com.peerchat.engine.EngineRuntime
import com.peerchat.engine.TokenCallback
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface EngineStreamEvent {
    data class Token(val text: String) : EngineStreamEvent
    data class Terminal(val metrics: EngineMetrics) : EngineStreamEvent
}

object StreamingEngine {
    fun stream(
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
        val callback = TokenCallback { chunk, done ->
            if (!done) {
                if (chunk.isNotEmpty()) {
                    trySendBlocking(EngineStreamEvent.Token(chunk))
                }
            } else {
                val metrics = EngineRuntime.updateMetricsFromNative()
                trySendBlocking(EngineStreamEvent.Terminal(metrics))
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
            val metrics = EngineRuntime.updateMetricsFromNative()
            val errorMetrics = if (metrics.isError) metrics else metrics.copy(stopReason = "error")
            trySendBlocking(EngineStreamEvent.Terminal(errorMetrics))
            close(start.exceptionOrNull())
        }
        awaitClose { }
    }
}
