package com.peerchat.engine

import org.json.JSONObject

data class EngineMetrics(
    val rawJson: String,
    val nCtx: Int,
    val nThreads: Int,
    val nGpuLayers: Int,
    val useVulkan: Boolean,
    val promptTokens: Int,
    val generationTokens: Int,
    val ttfsMs: Double,
    val prefillMs: Double,
    val decodeMs: Double,
    val totalMs: Double,
    val tps: Double,
    val promptTps: Double,
    val contextUsedPct: Double,
    val truncated: Boolean,
    val stopReason: String,
    val stopSequence: String,
) {
    val isError: Boolean get() = stopReason.equals("error", ignoreCase = true)

    companion object {
        fun empty(): EngineMetrics = EngineMetrics(
            rawJson = "{}",
            nCtx = 0,
            nThreads = 0,
            nGpuLayers = 0,
            useVulkan = false,
            promptTokens = 0,
            generationTokens = 0,
            ttfsMs = 0.0,
            prefillMs = 0.0,
            decodeMs = 0.0,
            totalMs = 0.0,
            tps = 0.0,
            promptTps = 0.0,
            contextUsedPct = 0.0,
            truncated = false,
            stopReason = "none",
            stopSequence = "",
        )

        fun fromJson(raw: String): EngineMetrics {
            return runCatching {
                val obj = JSONObject(raw)
                EngineMetrics(
                    rawJson = raw,
                    nCtx = obj.optInt("nCtx", obj.optInt("n_ctx", 0)),
                    nThreads = obj.optInt("nThreads", obj.optInt("n_threads", 0)),
                    nGpuLayers = obj.optInt("nGpuLayers", obj.optInt("n_gpu_layers", 0)),
                    useVulkan = obj.optBoolean("useVulkan", obj.optBoolean("use_vulkan", false)),
                    promptTokens = obj.optInt("promptTokens", obj.optInt("prompt_tokens", 0)),
                    generationTokens = obj.optInt("generationTokens", obj.optInt("generation_tokens", 0)),
                    ttfsMs = obj.optDouble("ttfsMs", obj.optDouble("ttfs_ms", 0.0)),
                    prefillMs = obj.optDouble("prefillMs", obj.optDouble("prefill_ms", 0.0)),
                    decodeMs = obj.optDouble("decodeMs", obj.optDouble("decode_ms", 0.0)),
                    totalMs = obj.optDouble("totalMs", obj.optDouble("total_ms", 0.0)),
                    tps = obj.optDouble("tps", 0.0),
                    promptTps = obj.optDouble("promptTps", obj.optDouble("prompt_tps", 0.0)),
                    contextUsedPct = obj.optDouble("contextUsedPct", obj.optDouble("context_used_pct", 0.0)),
                    truncated = obj.optBoolean("truncated", false),
                    stopReason = obj.optString("stopReason", obj.optString("stop_reason", "none")),
                    stopSequence = obj.optString("stopSequence", obj.optString("stop_sequence", "")),
                )
            }.getOrElse { empty() }
        }
    }
}

