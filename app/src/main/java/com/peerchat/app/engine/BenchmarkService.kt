package com.peerchat.app.engine

import android.content.Context
import android.os.Build
import android.os.Debug
import com.peerchat.app.data.OperationResult
import com.peerchat.app.data.PeerChatRepository
import com.peerchat.app.util.Logger
import com.peerchat.data.db.BenchmarkResult
import com.peerchat.data.db.ModelManifest
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineNative
import com.peerchat.engine.EngineRuntime
import com.peerchat.templates.TemplateCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

/**
 * Service for running automated model benchmarks with standardized prompts and metrics collection.
 */
object BenchmarkService {

    // Timeout constants
    private const val TEST_TIMEOUT_MINUTES = 5L
    private const val OVERALL_TIMEOUT_MINUTES = 30L

    /**
     * Benchmark configuration
     */
    data class BenchmarkConfig(
        val maxTokens: Int = 128,
        val temperature: Float = 0.8f,
        val topP: Float = 0.9f,
        val topK: Int = 40
    )

    /**
     * Benchmark progress callback
     */
    data class BenchmarkProgress(
        val stage: String,
        val progress: Float, // 0.0 to 1.0
        val message: String,
        val completedResults: List<BenchmarkResultData> = emptyList()
    )

    /**
     * Benchmark result with comprehensive performance metrics
     */
    data class BenchmarkResultData(
        val promptText: String,
        val promptTokens: Int,
        val generatedTokens: Int,
        val ttftMs: Long, // Time to First Token
        val totalMs: Long,
        val tps: Float, // Tokens Per Second
        val contextUsedPct: Float,
        val prefillMs: Long, // Prefill phase duration
        val decodeMs: Long, // Decode phase duration
        val memoryUsageMB: Long, // Peak memory usage during benchmark
        val gcCount: Int, // Number of GC events during benchmark
        val threadCpuTimeNs: Long, // CPU time spent on benchmark thread
        val gpuMode: Boolean, // true = GPU accelerated, false = CPU only
        val errorMessage: String? = null,
        val deviceInfo: String
    )

    /**
     * Predefined benchmark prompts designed to exercise different model capabilities
     */
    private val benchmarkPrompts = listOf(
        // Reasoning task
        "Explain step by step how a neural network learns from data. Be detailed but concise.",

        // Creative writing task
        "Write a short story about a robot who discovers emotions. Make it exactly 200 words.",

        // Technical explanation
        "Explain the difference between supervised and unsupervised machine learning using real-world examples.",

        // Analytical task
        "Analyze the pros and cons of electric vehicles compared to traditional gasoline cars. Provide specific examples.",

        // Conversational task
        "Pretend you are a helpful assistant. A user asks: 'How do I bake chocolate chip cookies?' Provide a detailed recipe."
    )

    /**
     * Run a comprehensive benchmark on a model with multiple prompts
     */
    suspend fun runBenchmark(
        context: Context,
        manifest: ModelManifest,
        config: BenchmarkConfig = BenchmarkConfig(),
        progressCallback: suspend (BenchmarkProgress) -> Unit = {}
    ): OperationResult<List<BenchmarkResultData>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<BenchmarkResultData>()

        try {
            // Determine actual GPU mode from loaded model
            val actualGpuMode = when (val status = EngineRuntime.status.value) {
                is EngineRuntime.EngineStatus.Loaded -> status.config.gpuLayers > 0 || status.config.useVulkan
                else -> false
            }

            Logger.i(
                "BenchmarkService: starting comprehensive benchmark",
                mapOf(
                    "modelId" to manifest.id,
                    "modelName" to manifest.name,
                    "prompts" to benchmarkPrompts.size,
                    "actualGpuMode" to actualGpuMode
                )
            )
            val deviceInfo = getDeviceInfo()

            // Test current model configuration
            val modeName = if (actualGpuMode) "GPU" else "CPU"
            Logger.i("BenchmarkService: starting benchmarks", mapOf("modelId" to manifest.id, "mode" to modeName))

            runCatching {
                progressCallback(
                    BenchmarkProgress(
                        stage = "$modeName Benchmarks",
                        progress = 0.0f,
                        message = "Running inference tests with current model configuration..."
                    )
                )
            }.onFailure { e ->
                Logger.w("BenchmarkService: initial progress callback failed", mapOf("modelId" to manifest.id, "error" to e.message), e)
            }

            val benchmarkResults = withTimeoutOrNull(OVERALL_TIMEOUT_MINUTES.minutes) {
                runBenchmarkForMode(
                    manifest = manifest,
                    config = config,
                    deviceInfo = deviceInfo,
                    useGpu = actualGpuMode,
                    progressCallback = { progress ->
                        runCatching {
                            try {
                                withContext(Dispatchers.Main) {
                                    progressCallback(progress)
                                }
                            } catch (e: Exception) {
                                Logger.w(
                                    "BenchmarkService: progress callback failed with context switch",
                                    mapOf("modelId" to manifest.id, "error" to e.message),
                                    e
                                )
                                progressCallback(progress)
                            }
                        }.onFailure { e ->
                            Logger.w(
                                "BenchmarkService: progress callback failed",
                                mapOf("modelId" to manifest.id, "error" to e.message),
                                e
                            )
                            try {
                                progressCallback(progress)
                            } catch (e2: Exception) {
                                Logger.e(
                                    "BenchmarkService: progress callback failed even without context switch",
                                    mapOf("modelId" to manifest.id, "error" to e2.message),
                                    e2
                                )
                            }
                        }
                    },
                    progressOffset = 0.0f,
                    progressRange = 0.9f
                )
            } ?: run {
                val elapsedMs = System.currentTimeMillis() - System.currentTimeMillis() // This would be better tracked
                Logger.e(
                    "BenchmarkService: overall benchmark timeout",
                    mapOf(
                        "modelId" to manifest.id,
                        "timeoutMinutes" to OVERALL_TIMEOUT_MINUTES,
                        "completedTests" to results.size,
                        "totalTests" to benchmarkPrompts.size
                    )
                )
                try {
                    progressCallback(
                        BenchmarkProgress(
                            stage = "Timeout",
                            progress = results.size.toFloat() / benchmarkPrompts.size,
                            message = "Benchmark timed out after ${OVERALL_TIMEOUT_MINUTES} minutes. Completed ${results.size}/${benchmarkPrompts.size} tests.",
                            completedResults = results
                        )
                    )
                } catch (e: Exception) {
                    Logger.e("BenchmarkService: failed to send timeout progress", mapOf("error" to e.message), e)
                }
                emptyList<BenchmarkResultData>()
            }
            
            results.addAll(benchmarkResults)

            runCatching {
                progressCallback(
                    BenchmarkProgress(
                        stage = "Saving Results",
                        progress = 0.9f,
                        message = "Saving benchmark results to database...",
                        completedResults = results
                    )
                )
            }.onFailure { e ->
                Logger.w("BenchmarkService: save progress callback failed", mapOf("modelId" to manifest.id, "error" to e.message), e)
            }

            Logger.i(
                "BenchmarkService: comprehensive benchmark completed",
                mapOf(
                    "modelId" to manifest.id,
                    "totalTests" to results.size,
                    "successfulTests" to results.count { it.errorMessage == null },
                    "failedTests" to results.count { it.errorMessage != null }
                )
            )

            // Save results to database
            val repository = PeerChatRepository.from(context)
            results.forEach { result ->
                val dbResult = BenchmarkResult(
                    id = 0,
                    manifestId = manifest.id,
                    promptText = result.promptText,
                    promptTokens = result.promptTokens,
                    generatedTokens = result.generatedTokens,
                    ttftMs = result.ttftMs,
                    totalMs = result.totalMs,
                    tps = result.tps,
                    contextUsedPct = result.contextUsedPct,
                    prefillMs = result.prefillMs,
                    decodeMs = result.decodeMs,
                    memoryUsageMB = result.memoryUsageMB,
                    gcCount = result.gcCount,
                    threadCpuTimeNs = result.threadCpuTimeNs,
                    gpuMode = result.gpuMode,
                    errorMessage = result.errorMessage,
                    runAt = System.currentTimeMillis(),
                    deviceInfo = result.deviceInfo
                )
                repository.insertBenchmarkResult(dbResult)
            }

            runCatching {
                try {
                    withContext(Dispatchers.Main) {
                        progressCallback(
                            BenchmarkProgress(
                                stage = "Completed",
                                progress = 1.0f,
                                message = "Benchmark finished with ${results.size} tests",
                                completedResults = results
                            )
                        )
                    }
                } catch (e: Exception) {
                    Logger.w("BenchmarkService: completion callback failed with context switch", mapOf("modelId" to manifest.id, "error" to e.message), e)
                    progressCallback(
                        BenchmarkProgress(
                            stage = "Completed",
                            progress = 1.0f,
                            message = "Benchmark finished with ${results.size} tests",
                            completedResults = results
                        )
                    )
                }
            }.onFailure { e ->
                Logger.w("BenchmarkService: completion progress callback failed", mapOf("modelId" to manifest.id, "error" to e.message), e)
                try {
                    progressCallback(
                        BenchmarkProgress(
                            stage = "Completed",
                            progress = 1.0f,
                            message = "Benchmark finished with ${results.size} tests",
                            completedResults = results
                        )
                    )
                } catch (e2: Exception) {
                    Logger.e("BenchmarkService: completion callback failed even without context switch", mapOf("error" to e2.message), e2)
                }
            }

            // Final cleanup after all benchmarks complete
            cleanupAfterBenchmark()

            OperationResult.Success(results)

        } catch (e: CancellationException) {
            Logger.i("BenchmarkService: benchmark cancelled", mapOf("modelId" to manifest.id))
            // Ensure native generation is aborted on cancellation
            runCatching { EngineNative.abort() }
            throw e
        } catch (e: TimeoutCancellationException) {
            Logger.e(
                "BenchmarkService: benchmark timeout exception",
                mapOf(
                    "modelId" to manifest.id,
                    "error" to e.message,
                    "timeoutMinutes" to OVERALL_TIMEOUT_MINUTES,
                    "completedTests" to results.size
                ),
                e
            )
            // Ensure native generation is aborted on timeout
            runCatching { EngineNative.abort() }
            OperationResult.Failure(
                "Benchmark timed out after ${OVERALL_TIMEOUT_MINUTES} minutes. " +
                "Completed ${results.size}/${benchmarkPrompts.size} tests before timeout."
            )
        } catch (e: Exception) {
            Logger.e(
                "BenchmarkService: benchmark failed",
                mapOf("modelId" to manifest.id, "error" to e.message),
                e
            )
            // Ensure native generation is aborted on error
            runCatching { EngineNative.abort() }
            OperationResult.Failure("Benchmark failed: ${e.message}")
        }
    }

    /**
     * Run a single benchmark test with comprehensive performance monitoring
     */
    private suspend fun runSingleBenchmark(
        manifest: ModelManifest,
        prompt: String,
        config: BenchmarkConfig,
        deviceInfo: String,
        useGpu: Boolean = true,
        progressCallback: suspend (BenchmarkProgress) -> Unit = {}
    ): BenchmarkResultData {
        return Logger.profile("benchmark_single_test", mapOf("modelId" to manifest.id, "promptLength" to prompt.length)) {
            try {
                // Ensure model is loaded
                if (EngineRuntime.status.value !is EngineRuntime.EngineStatus.Loaded) {
                    throw IllegalStateException("Model must be loaded before benchmarking")
                }

                // Get template
                val template = TemplateCatalog.resolve(null) ?: TemplateCatalog.default()

                // Collect baseline performance metrics
                val baselineGcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0
                val baselineCpuTime = Debug.threadCpuTimeNanos()
                val baselineMemoryInfo = Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }

                Logger.i(
                    "BenchmarkService: starting single test",
                    mapOf(
                        "modelId" to manifest.id,
                        "promptLength" to prompt.length,
                        "template" to template.id,
                        "baselineGcCount" to baselineGcCount,
                        "baselineMemoryKB" to baselineMemoryInfo.totalPss
                    )
                )

                // Record start time for TTFT measurement
                val startTime = System.nanoTime()

                var firstTokenTime: Long? = null
                var totalTokens = 0
                var finalMetrics: EngineMetrics? = null
                var peakMemoryUsageKB = baselineMemoryInfo.totalPss
                var streamError: String? = null
                var lastProgressUpdateTime = System.currentTimeMillis()
                val progressUpdateIntervalMs = 1000L // Update every second

                // Run inference with performance monitoring and timeout
                Logger.startPerfTimer("benchmark_inference", mapOf("modelId" to manifest.id))
                
                val streamResult = withTimeoutOrNull(TEST_TIMEOUT_MINUTES.minutes) {
                    try {
                        var lastTokenTime = System.nanoTime()
                        val heartbeatTimeout = TEST_TIMEOUT_MINUTES.minutes.inWholeMilliseconds / 2
                        
                        Logger.i("BenchmarkService: starting stream collection", mapOf("modelId" to manifest.id, "promptLength" to prompt.length))
                        
                        coroutineScope {
                            // Launch heartbeat coroutine to send periodic updates even without tokens
                            val heartbeatJob = launch {
                                var heartbeatCount = 0
                                while (true) {
                                    delay(progressUpdateIntervalMs)
                                    heartbeatCount++
                                    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                                    val heartbeatMessage = when {
                                        totalTokens == 0 && heartbeatCount == 1 -> "Starting inference..."
                                        totalTokens == 0 -> "Waiting for first token... (${elapsedMs}ms elapsed)"
                                        else -> null // Don't send heartbeat if we're already getting token updates
                                    }
                                    if (heartbeatMessage != null) {
                                        runCatching {
                                            progressCallback(
                                                BenchmarkProgress(
                                                    stage = if (useGpu) "GPU Benchmark" else "CPU Benchmark",
                                                    progress = 0.3f, // Early progress indication
                                                    message = heartbeatMessage
                                                )
                                            )
                                        }.onFailure { e ->
                                            Logger.w("BenchmarkService: heartbeat progress callback failed", mapOf("modelId" to manifest.id, "error" to e.message), e)
                                        }
                                    }
                                }
                            }
                            
                            StreamingEngine.stream(
                                prompt = prompt,
                                systemPrompt = null,
                                template = template.id,
                                temperature = config.temperature,
                                topP = config.topP,
                                topK = config.topK,
                                maxTokens = config.maxTokens,
                                stop = emptyArray<String>()
                            ).collect { event ->
                                // Cancel heartbeat once we start receiving tokens
                                if (totalTokens == 0 && event is EngineStreamEvent.Token) {
                                    heartbeatJob.cancel()
                                }
                                
                                when (event) {
                                is EngineStreamEvent.Token -> {
                                    totalTokens++
                                    val currentTime = System.nanoTime()
                                    lastTokenTime = currentTime
                                    if (firstTokenTime == null) {
                                        firstTokenTime = currentTime
                                        Logger.i("BenchmarkService: first token received", mapOf("modelId" to manifest.id, "elapsedMs" to ((currentTime - startTime) / 1_000_000)))
                                    }

                                    // Track peak memory usage during inference
                                    val currentMemoryInfo = Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }
                                    peakMemoryUsageKB = maxOf(peakMemoryUsageKB, currentMemoryInfo.totalPss)
                                    
                                    // Send periodic progress updates
                                    val now = System.currentTimeMillis()
                                    if (now - lastProgressUpdateTime >= progressUpdateIntervalMs) {
                                        lastProgressUpdateTime = now
                                        val elapsedMs = (currentTime - startTime) / 1_000_000
                                        val progressMessage = if (totalTokens == 1) {
                                            "Generating tokens... (first token received)"
                                        } else {
                                            "Generating tokens... ($totalTokens tokens, ${elapsedMs}ms elapsed)"
                                        }
                                        runCatching {
                                            progressCallback(
                                                BenchmarkProgress(
                                                    stage = if (useGpu) "GPU Benchmark" else "CPU Benchmark",
                                                    progress = 0.5f, // Intermediate progress during inference
                                                    message = progressMessage
                                                )
                                            )
                                        }.onFailure { e ->
                                            Logger.w("BenchmarkService: stream progress callback failed", mapOf("modelId" to manifest.id, "error" to e.message), e)
                                        }
                                    }
                                    
                                    // Heartbeat check: if no tokens for too long, log warning
                                    val timeSinceLastToken = (System.nanoTime() - lastTokenTime) / 1_000_000
                                    if (timeSinceLastToken > heartbeatTimeout && totalTokens > 0) {
                                        Logger.w("BenchmarkService: possible stream stall", mapOf(
                                            "modelId" to manifest.id,
                                            "msSinceLastToken" to timeSinceLastToken,
                                            "totalTokens" to totalTokens
                                        ))
                                    }
                                }
                                is EngineStreamEvent.Terminal -> {
                                    Logger.i("BenchmarkService: stream terminal received", mapOf("modelId" to manifest.id, "totalTokens" to totalTokens))
                                    finalMetrics = event.metrics
                                }
                                is EngineStreamEvent.Error -> {
                                    Logger.w("BenchmarkService: stream error", mapOf("error" to event.message))
                                    streamError = event.message
                                    finalMetrics = EngineMetrics.empty().copy(stopReason = "error")
                                }
                                is EngineStreamEvent.Checkpoint -> {
                                    // Ignore checkpoints during benchmarking
                                }
                            }
                            
                            // Cancel heartbeat when stream completes
                            heartbeatJob.cancel()
                        }
                        Logger.i("BenchmarkService: stream collection completed", mapOf("modelId" to manifest.id, "totalTokens" to totalTokens))
                        true
                    } catch (e: CancellationException) {
                        Logger.w("BenchmarkService: stream cancelled", mapOf("modelId" to manifest.id))
                        runCatching { EngineNative.abort() }
                        throw e
                    } catch (e: Exception) {
                        Logger.e("BenchmarkService: stream exception", mapOf("modelId" to manifest.id, "error" to e.message), e)
                        streamError = e.message ?: "Unknown stream error"
                        false
                    }
                }

                Logger.endPerfTimer("benchmark_inference", mapOf("tokensGenerated" to totalTokens))

                if (streamResult == null) {
                    // Timeout occurred
                    val elapsedMs = (System.nanoTime() - startTime) / 1_000_000
                    Logger.w(
                        "BenchmarkService: test timeout",
                        mapOf(
                            "modelId" to manifest.id,
                            "promptLength" to prompt.length,
                            "elapsedMs" to elapsedMs,
                            "timeoutMinutes" to TEST_TIMEOUT_MINUTES,
                            "tokensGenerated" to totalTokens
                        )
                    )
                    runCatching { EngineNative.abort() }
                    throw java.util.concurrent.TimeoutException(
                        "Test timed out after $TEST_TIMEOUT_MINUTES minutes. " +
                        "Generated $totalTokens tokens in ${elapsedMs}ms before timeout."
                    )
                }

                val endTime = System.nanoTime()
                val totalDurationMs = (endTime - startTime) / 1_000_000.0
                val ttftMs = firstTokenTime?.let { (it - startTime) / 1_000_000.0 } ?: totalDurationMs

                // Calculate TPS (tokens per second)
                val tps = if (totalDurationMs > 0) {
                    (totalTokens.toDouble() / totalDurationMs * 1000.0).toFloat()
                } else {
                    0.0f
                }

                // Collect final performance metrics
                val finalGcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0
                val finalCpuTime = Debug.threadCpuTimeNanos()
                val gcEvents = (finalGcCount - baselineGcCount).toInt()
                val threadCpuTimeNs = finalCpuTime - baselineCpuTime

                val result = BenchmarkResultData(
                    promptText = prompt,
                    promptTokens = finalMetrics?.promptTokens ?: 0,
                    generatedTokens = totalTokens,
                    ttftMs = ttftMs.toLong(),
                    totalMs = totalDurationMs.toLong(),
                    tps = tps,
                    contextUsedPct = finalMetrics?.contextUsedPct?.toFloat() ?: 0f,
                    prefillMs = finalMetrics?.prefillMs?.toLong() ?: 0L,
                    decodeMs = finalMetrics?.decodeMs?.toLong() ?: 0L,
                    memoryUsageMB = (peakMemoryUsageKB / 1024).toLong(),
                    gcCount = gcEvents,
                    threadCpuTimeNs = threadCpuTimeNs,
                    gpuMode = useGpu,
                    errorMessage = streamError,
                    deviceInfo = deviceInfo
                )

                Logger.perf("BenchmarkService: single test completed",
                    mapOf(
                        "modelId" to manifest.id,
                        "promptTokens" to result.promptTokens,
                        "generatedTokens" to result.generatedTokens,
                        "ttftMs" to result.ttftMs,
                        "totalMs" to result.totalMs,
                        "tps" to result.tps,
                        "contextUsedPct" to result.contextUsedPct,
                        "prefillMs" to result.prefillMs,
                        "decodeMs" to result.decodeMs,
                        "memoryUsageMB" to result.memoryUsageMB,
                        "gcCount" to result.gcCount,
                        "threadCpuTimeNs" to result.threadCpuTimeNs
                    )
                )

                result

            } catch (e: Exception) {
                val isTimeout = e is java.util.concurrent.TimeoutException ||
                               e.message?.contains("timeout", ignoreCase = true) == true ||
                               e.message?.contains("timed out", ignoreCase = true) == true

                Logger.errorContext(
                    if (isTimeout) "BenchmarkService: single test timeout" else "BenchmarkService: single test error",
                    e,
                    mapOf(
                        "modelId" to manifest.id,
                        "promptLength" to prompt.length,
                        "isTimeout" to isTimeout
                    ) + if (isTimeout) mapOf("timeoutMinutes" to TEST_TIMEOUT_MINUTES) else emptyMap()
                )
                // Ensure native generation is aborted
                runCatching { EngineNative.abort() }

                BenchmarkResultData(
                    promptText = prompt,
                    promptTokens = 0,
                    generatedTokens = 0,
                    ttftMs = 0,
                    totalMs = 0,
                    tps = 0f,
                    contextUsedPct = 0f,
                    prefillMs = 0L,
                    decodeMs = 0L,
                    memoryUsageMB = 0L,
                    gcCount = 0,
                    threadCpuTimeNs = 0L,
                    gpuMode = useGpu,
                    errorMessage = if (isTimeout) "Test timed out after $TEST_TIMEOUT_MINUTES minutes" else "Test failed: ${e.message}",
                    deviceInfo = deviceInfo
                )
            } catch (e: CancellationException) {
                Logger.w("BenchmarkService: single test cancelled", mapOf("modelId" to manifest.id))
                // Ensure native generation is aborted
                runCatching { EngineNative.abort() }
                throw e
            } catch (e: Exception) {
                Logger.errorContext(
                    "BenchmarkService: single test failed",
                    e,
                    mapOf(
                        "modelId" to manifest.id,
                        "promptLength" to prompt.length,
                        "error" to e.message
                    )
                )
                // Ensure native generation is aborted on error
                runCatching { EngineNative.abort() }

                BenchmarkResultData(
                    promptText = prompt,
                    promptTokens = 0,
                    generatedTokens = 0,
                    ttftMs = 0,
                    totalMs = 0,
                    tps = 0f,
                    contextUsedPct = 0f,
                    prefillMs = 0L,
                    decodeMs = 0L,
                    memoryUsageMB = 0L,
                    gcCount = 0,
                    threadCpuTimeNs = 0L,
                    gpuMode = useGpu,
                    errorMessage = e.message ?: "Unknown error",
                    deviceInfo = deviceInfo
                )
            }
        }
    }

    /**
     * Get device information for benchmark results
     */
    private fun getDeviceInfo(): String {
        return buildString {
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            append("; ${Build.MANUFACTURER} ${Build.MODEL}")
            append("; CPU: ${Runtime.getRuntime().availableProcessors()} cores")
            append("; RAM: ${Runtime.getRuntime().maxMemory() / 1024 / 1024}MB")
        }
    }

    /**
     * Get benchmark history for a model
     */
    suspend fun getBenchmarkHistory(
        context: Context,
        manifest: ModelManifest,
        limit: Int = 10
    ): OperationResult<List<BenchmarkResult>> = withContext(Dispatchers.IO) {
        try {
            val repository = PeerChatRepository.from(context)
            val results = repository.getRecentBenchmarkResults(manifest.id, limit)
            OperationResult.Success(results)
        } catch (e: Exception) {
            OperationResult.Failure("Failed to load benchmark history: ${e.message}")
        }
    }

    /**
     * Calculate average metrics from benchmark results
     */
    fun calculateAverageMetrics(results: List<BenchmarkResult>): BenchmarkMetrics {
        val successfulResults = results.filter { it.errorMessage == null }

        return BenchmarkMetrics(
            averageTtftMs = if (successfulResults.isNotEmpty()) successfulResults.map { it.ttftMs.toDouble() }.average().toLong() else 0L,
            averageTps = if (successfulResults.isNotEmpty()) successfulResults.map { it.tps.toDouble() }.average().toFloat() else 0f,
            averageContextUsedPct = if (successfulResults.isNotEmpty()) successfulResults.map { it.contextUsedPct.toDouble() }.average().toFloat() else 0f,
            totalTests = results.size,
            successfulTests = successfulResults.size,
            failedTests = results.size - successfulResults.size
        )
    }

    /**
     * Aggregate benchmark metrics
     */
    data class BenchmarkMetrics(
        val averageTtftMs: Long,
        val averageTps: Float,
        val averageContextUsedPct: Float,
        val totalTests: Int,
        val successfulTests: Int,
        val failedTests: Int
    )

    /**
     * Run benchmarks for a specific CPU/GPU configuration
     */
    private suspend fun runBenchmarkForMode(
        manifest: ModelManifest,
        config: BenchmarkConfig,
        deviceInfo: String,
        useGpu: Boolean,
        progressCallback: suspend (BenchmarkProgress) -> Unit,
        progressOffset: Float,
        progressRange: Float
    ): List<BenchmarkResultData> {
        val results = mutableListOf<BenchmarkResultData>()

        benchmarkPrompts.forEachIndexed { index, prompt ->
            val promptProgress = progressOffset + (index.toFloat() / benchmarkPrompts.size) * progressRange
            val modeName = if (useGpu) "GPU" else "CPU"

            runCatching {
                progressCallback(
                    BenchmarkProgress(
                        stage = "$modeName Benchmark ${index + 1}/${benchmarkPrompts.size}",
                        progress = promptProgress,
                        message = "Testing $modeName with prompt: ${prompt.take(50)}...",
                        completedResults = results.toList()
                    )
                )
            }.onFailure { e ->
                Logger.w("BenchmarkService: test start progress callback failed", mapOf("modelId" to manifest.id, "testIndex" to index, "error" to e.message), e)
            }

            val result = runSingleBenchmark(
                manifest = manifest,
                prompt = prompt,
                config = config,
                deviceInfo = deviceInfo,
                useGpu = useGpu,
                progressCallback = { testProgress ->
                    // Update progress during individual test execution
                    val adjustedProgress = promptProgress + (testProgress.progress * (progressRange / benchmarkPrompts.size))
                    runCatching {
                        progressCallback(
                            BenchmarkProgress(
                                stage = testProgress.stage,
                                progress = adjustedProgress.coerceIn(0f, 1f),
                                message = testProgress.message,
                                completedResults = results.toList()
                            )
                        )
                    }.onFailure { e ->
                        Logger.w("BenchmarkService: nested progress callback failed", mapOf("modelId" to manifest.id, "error" to e.message), e)
                    }
                }
            )
            results.add(result)

            // Report progress with completed results
            runCatching {
                progressCallback(
                    BenchmarkProgress(
                        stage = "$modeName Benchmark ${index + 1}/${benchmarkPrompts.size}",
                        progress = promptProgress,
                        message = "Completed test ${index + 1}/${benchmarkPrompts.size}",
                        completedResults = results.toList()
                    )
                )
            }.onFailure { e ->
                Logger.w("BenchmarkService: test completion progress callback failed", mapOf("modelId" to manifest.id, "testIndex" to index, "error" to e.message), e)
            }

            // Brief pause between tests to allow system stabilization
            delay(500)
        }

        return results
    }

    /**
     * Force cleanup after benchmark to prevent memory leaks
     */
    private suspend fun cleanupAfterBenchmark() {
        try {
            Logger.i("BenchmarkService: starting cleanup after benchmark")

            // Small delay to allow any pending operations to complete
            delay(100)

            // Single GC call - multiple calls are ineffective
            System.gc()

            Logger.i("BenchmarkService: cleanup completed after benchmark")

        } catch (e: Exception) {
            Logger.w("BenchmarkService: cleanup failed", mapOf("error" to e.message), e)
        }
    }
}
