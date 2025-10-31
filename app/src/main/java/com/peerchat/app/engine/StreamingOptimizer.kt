package com.peerchat.app.engine

import com.peerchat.app.util.Logger
import com.peerchat.engine.EngineMetrics
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * Optimizes streaming inference by batching tokens and managing backpressure
 * to reduce JNI overhead and improve overall throughput.
 */
class StreamingOptimizer(
    private val maxBatchSize: Int = 10,
    private val maxBatchDelayMs: Long = 50,
    private val channelCapacity: Int = 100
) {

    /**
     * Wraps a streaming flow with token batching and backpressure management
     */
    fun optimizeStream(upstream: Flow<EngineStreamEvent>): Flow<EngineStreamEvent> = flow {
        val batchChannel = Channel<TokenBatch>(capacity = channelCapacity)

        coroutineScope {
            // Start batch processor
            launch { processBatches(batchChannel, this@flow) }

            // Collect upstream and batch tokens
            val tokenBuffer = mutableListOf<String>()
            var batchStartTime = System.currentTimeMillis()

            upstream.collect { event ->
                when (event) {
                    is EngineStreamEvent.Token -> {
                        tokenBuffer.add(event.text)

                        // Check if we should flush the batch
                        val shouldFlush = tokenBuffer.size >= maxBatchSize ||
                                (System.currentTimeMillis() - batchStartTime) >= maxBatchDelayMs

                        if (shouldFlush && tokenBuffer.isNotEmpty()) {
                            val batch = TokenBatch(tokenBuffer.toList(), null)
                            batchChannel.send(batch)
                            tokenBuffer.clear()
                            batchStartTime = System.currentTimeMillis()
                        }
                    }
                    is EngineStreamEvent.Terminal -> {
                        // Flush any remaining tokens
                        if (tokenBuffer.isNotEmpty()) {
                            val batch = TokenBatch(tokenBuffer.toList(), event.metrics)
                            batchChannel.send(batch)
                            tokenBuffer.clear()
                        } else {
                            // Send terminal event directly if no pending tokens
                            batchChannel.send(TokenBatch(emptyList(), event.metrics))
                        }
                    }
                }
            }

            // Close the batch channel
            batchChannel.close()
        }
    }

    /**
     * Processes batched tokens and emits them downstream
     */
    private suspend fun processBatches(
        batchChannel: ReceiveChannel<TokenBatch>,
        downstream: FlowCollector<EngineStreamEvent>
    ) {
        for (batch in batchChannel) {
            // Emit individual tokens
            batch.tokens.forEach { token ->
                downstream.emit(EngineStreamEvent.Token(token))
            }

            // Emit terminal event if present
            batch.terminalMetrics?.let { metrics ->
                downstream.emit(EngineStreamEvent.Terminal(metrics))
            }

            // Log batching stats periodically
            if (batch.tokens.size > 1) {
                Logger.i("StreamingOptimizer: processed batch", mapOf(
                    "tokenCount" to batch.tokens.size,
                    "totalChars" to batch.tokens.sumOf { it.length },
                    "hasTerminal" to (batch.terminalMetrics != null)
                ))
            }
        }
    }

    /**
     * Creates an optimized streaming engine wrapper
     */
    fun createOptimizedEngine(): StreamingEngineWrapper {
        return StreamingEngineWrapper(this)
    }

    private data class TokenBatch(
        val tokens: List<String>,
        val terminalMetrics: EngineMetrics?
    )

    /**
     * Wrapper for StreamingEngine that applies optimizations
     */
    class StreamingEngineWrapper(private val optimizer: StreamingOptimizer) {

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
            val upstream = StreamingEngine.stream(
                prompt = prompt,
                systemPrompt = systemPrompt,
                template = template,
                temperature = temperature,
                topP = topP,
                topK = topK,
                maxTokens = maxTokens,
                stop = stop
            )

            return optimizer.optimizeStream(upstream)
        }
    }

    companion object {
        private const val TAG = "StreamingOptimizer"
    }
}
