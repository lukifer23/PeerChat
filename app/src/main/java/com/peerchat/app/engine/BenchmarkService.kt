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
import com.peerchat.engine.EngineRuntime
import com.peerchat.templates.TemplateCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlin.time.measureTime

/**
 * Service for running automated model benchmarks with standardized prompts and metrics collection.
 */
object BenchmarkService {

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
        val message: String
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
        try {
            Logger.i(
                "BenchmarkService: starting benchmark",
                mapOf(
                    "modelId" to manifest.id,
                    "modelName" to manifest.name,
                    "prompts" to benchmarkPrompts.size
                )
            )

            val results = mutableListOf<BenchmarkResultData>()
            val deviceInfo = getDeviceInfo()

            benchmarkPrompts.forEachIndexed { index, prompt ->
                progressCallback(
                    BenchmarkProgress(
                        stage = "Running benchmark ${index + 1}/${benchmarkPrompts.size}",
                        progress = index.toFloat() / benchmarkPrompts.size,
                        message = "Testing with prompt: ${prompt.take(50)}..."
                    )
                )

                val result = runSingleBenchmark(manifest, prompt, config, deviceInfo)
                results.add(result)
            }

            progressCallback(
                BenchmarkProgress(
                    stage = "Completed",
                    progress = 1.0f,
                    message = "Benchmark finished with ${results.size} tests"
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
                    errorMessage = result.errorMessage,
                    runAt = System.currentTimeMillis(),
                    deviceInfo = result.deviceInfo
                )
                repository.insertBenchmarkResult(dbResult)
            }

            Logger.i(
                "BenchmarkService: benchmark completed",
                mapOf(
                    "modelId" to manifest.id,
                    "totalTests" to results.size,
                    "successfulTests" to results.count { it.errorMessage == null },
                    "failedTests" to results.count { it.errorMessage != null }
                )
            )

            OperationResult.Success(results)

        } catch (e: CancellationException) {
            Logger.i("BenchmarkService: benchmark cancelled", mapOf("modelId" to manifest.id))
            throw e
        } catch (e: Exception) {
            Logger.e(
                "BenchmarkService: benchmark failed",
                mapOf("modelId" to manifest.id, "error" to e.message),
                e
            )
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
        deviceInfo: String
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

                // Run inference with performance monitoring
                Logger.startPerfTimer("benchmark_inference", mapOf("modelId" to manifest.id))
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
                    when (event) {
                        is EngineStreamEvent.Token -> {
                            totalTokens++
                            if (firstTokenTime == null) {
                                firstTokenTime = System.nanoTime()
                            }

                            // Track peak memory usage during inference
                            val currentMemoryInfo = Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }
                            peakMemoryUsageKB = maxOf(peakMemoryUsageKB, currentMemoryInfo.totalPss)
                        }
                        is EngineStreamEvent.Terminal -> {
                            finalMetrics = event.metrics
                        }
                    }
                }
                Logger.endPerfTimer("benchmark_inference", mapOf("tokensGenerated" to totalTokens))

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
                Logger.errorContext(
                    "BenchmarkService: single test failed",
                    e,
                    mapOf(
                        "modelId" to manifest.id,
                        "promptLength" to prompt.length,
                        "error" to e.message
                    )
                )

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
}
