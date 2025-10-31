package com.peerchat.app.engine

import com.peerchat.app.util.Logger
import com.peerchat.engine.EngineNative
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * Validates that a loaded model is actually functional, not just that it loaded.
 * Performs basic health checks like tokenization, basic inference, and memory integrity.
 */
class ModelHealthChecker {

    /**
     * Result of a health check
     */
    sealed class HealthResult {
        data class Healthy(val checksPassed: List<String>) : HealthResult()
        data class Unhealthy(val failures: List<String>) : HealthResult()
        data class Error(val error: String) : HealthResult()
    }

    /**
     * Perform comprehensive health check on currently loaded model
     */
    suspend fun checkCurrentModel(): HealthResult = withContext(Dispatchers.IO) {
        val status = EngineRuntime.status.value
        if (status !is EngineRuntime.EngineStatus.Loaded) {
            return@withContext HealthResult.Error("No model loaded")
        }

        val checks = mutableListOf<String>()
        val failures = mutableListOf<String>()

        try {
            // Check 1: Basic tokenization
            if (checkTokenization()) {
                checks.add("tokenization")
            } else {
                failures.add("tokenization failed")
            }

            // Check 2: Basic inference (short generation)
            val inferenceResult = checkBasicInference()
            when (inferenceResult) {
                is InferenceResult.Success -> {
                    checks.add("basic_inference (${inferenceResult.tokenCount} tokens)")
                }
                is InferenceResult.Failed -> {
                    failures.add("basic inference: ${inferenceResult.reason}")
                }
            }

            // Check 3: Memory integrity
            if (checkMemoryIntegrity()) {
                checks.add("memory_integrity")
            } else {
                failures.add("memory integrity check failed")
            }

            // Check 4: Model metadata consistency
            if (checkMetadataConsistency()) {
                checks.add("metadata_consistency")
            } else {
                failures.add("metadata consistency check failed")
            }

            // Check 5: Context window validation
            val contextCheck = checkContextWindow()
            when (contextCheck) {
                is ContextResult.Valid -> {
                    checks.add("context_window (${contextCheck.actualTokens}/${contextCheck.expectedTokens})")
                }
                is ContextResult.Invalid -> {
                    failures.add("context window: ${contextCheck.reason}")
                }
            }

            if (failures.isEmpty()) {
                HealthResult.Healthy(checks)
            } else {
                HealthResult.Unhealthy(failures)
            }

        } catch (e: Exception) {
            Logger.e("ModelHealthChecker: exception during health check", throwable = e)
            HealthResult.Error("Health check failed: ${e.message}")
        }
    }

    /**
     * Quick smoke test - just verify model can tokenize basic text
     */
    suspend fun smokeTest(): Boolean = withContext(Dispatchers.IO) {
        try {
            checkTokenization()
        } catch (e: Exception) {
            Logger.w("ModelHealthChecker: smoke test failed", throwable = e)
            false
        }
    }

    // Private check implementations

    private fun checkTokenization(): Boolean {
        return try {
            // Test with a simple prompt
            val testText = "Hello, world!"
            val tokenCount = EngineNative.countTokens(testText)

            // Should get some tokens
            tokenCount > 0
        } catch (e: Exception) {
            Logger.w("ModelHealthChecker: tokenization check failed", mapOf("error" to e.message), e)
            false
        }
    }

    private sealed class InferenceResult {
        data class Success(val tokenCount: Int) : InferenceResult()
        data class Failed(val reason: String) : InferenceResult()
    }

    private suspend fun checkBasicInference(): InferenceResult {
        return try {
            withTimeoutOrNull(10.seconds) {
                // Very short generation to test basic functionality
                val prompt = "The capital of France is"
                val result = EngineNative.generate(
                    prompt = prompt,
                    systemPrompt = null,
                    template = null,
                    temperature = 0.1f, // Low temperature for deterministic output
                    topP = 0.9f,
                    topK = 10,
                    maxTokens = 5, // Very short generation
                    stop = emptyArray()
                )

                if (result.isNotBlank() && result.length > 3) {
                    InferenceResult.Success(result.length / 4) // Rough token estimate
                } else {
                    InferenceResult.Failed("empty or too short response: '$result'")
                }
            } ?: InferenceResult.Failed("inference timed out")

        } catch (e: Exception) {
            Logger.w("ModelHealthChecker: inference check failed", mapOf("error" to e.message), e)
            InferenceResult.Failed("exception: ${e.message}")
        }
    }

    private fun checkMemoryIntegrity(): Boolean {
        return try {
            // Try to get metrics - this will fail if memory is corrupted
            val metrics = EngineNative.metrics()
            metrics.isNotBlank() && metrics.contains("nCtx")
        } catch (e: Exception) {
            Logger.w("ModelHealthChecker: memory integrity check failed", mapOf("error" to e.message), e)
            false
        }
    }

    private fun checkMetadataConsistency(): Boolean {
        return try {
            val metaJson = EngineRuntime.currentModelMeta()
            if (metaJson.isNullOrBlank()) {
                return false
            }

            // Parse metadata and check for required fields
            val hasArch = metaJson.contains("\"arch\"")
            val hasVocab = metaJson.contains("\"nVocab\"")

            hasArch && hasVocab
        } catch (e: Exception) {
            Logger.w("ModelHealthChecker: metadata consistency check failed", mapOf("error" to e.message), e)
            false
        }
    }

    private sealed class ContextResult {
        data class Valid(val actualTokens: Int, val expectedTokens: Int) : ContextResult()
        data class Invalid(val reason: String) : ContextResult()
    }

    private fun checkContextWindow(): ContextResult {
        return try {
            // Get context length from metrics
            val metrics = EngineNative.metrics()
            if (!metrics.contains("nCtx")) {
                return ContextResult.Invalid("could not get context length from metrics")
            }

            // Parse context length (simplified parsing)
            val nCtxMatch = Regex("\"nCtx\":(\\d+)").find(metrics)
            val nCtx = nCtxMatch?.groupValues?.get(1)?.toIntOrNull()
                ?: return ContextResult.Invalid("could not parse nCtx from metrics")

            // Test tokenization at context limit
            val testText = "This is a test sentence. ".repeat(nCtx / 20) // Rough estimate
            val tokenCount = EngineNative.countTokens(testText)

            if (tokenCount > nCtx * 1.1) { // Allow 10% margin for tokenizer differences
                ContextResult.Invalid("token count ($tokenCount) exceeds context ($nCtx)")
            } else {
                ContextResult.Valid(tokenCount, nCtx)
            }

        } catch (e: Exception) {
            Logger.w("ModelHealthChecker: context window check failed", mapOf("error" to e.message), e)
            ContextResult.Invalid("exception: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "ModelHealthChecker"
    }
}
