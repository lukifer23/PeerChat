package com.peerchat.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.peerchat.app.util.Logger
import com.peerchat.data.db.ModelManifest
import kotlin.math.max
import kotlin.math.min

/**
 * Intelligent GPU memory manager that dynamically allocates GPU layers
 * based on device capabilities, model size, and available VRAM.
 */
class GpuMemoryManager(private val context: Context) {

    data class GpuCapabilities(
        val hasVulkan: Boolean = false,
        val maxVramBytes: Long = 0,
        val recommendedLayers: Int = 0,
        val deviceName: String = "unknown",
        val driverVersion: String = "unknown"
    )

    data class MemoryProfile(
        val modelSizeBytes: Long,
        val contextLength: Int,
        val recommendedGpuLayers: Int,
        val estimatedVramUsageBytes: Long,
        val canUseGpu: Boolean,
        val reasoning: String
    )

    /**
     * Detect GPU capabilities of the current device
     */
    fun detectCapabilities(): GpuCapabilities {
        return try {
            // For now, we use heuristics based on device properties
            // In a production app, this would query Vulkan capabilities
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            // Estimate VRAM based on total RAM (rough heuristic)
            val totalRamBytes = memoryInfo.totalMem
            val estimatedVramBytes = when {
                // High-end devices (16GB+ RAM) likely have good GPUs
                totalRamBytes >= 16L * 1024 * 1024 * 1024 -> 8L * 1024 * 1024 * 1024 // 8GB VRAM
                totalRamBytes >= 8L * 1024 * 1024 * 1024 -> 4L * 1024 * 1024 * 1024  // 4GB VRAM
                totalRamBytes >= 4L * 1024 * 1024 * 1024 -> 2L * 1024 * 1024 * 1024  // 2GB VRAM
                else -> 1L * 1024 * 1024 * 1024 // 1GB VRAM (minimum)
            }

            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

            GpuCapabilities(
                hasVulkan = true, // Assume Vulkan support on modern Android
                maxVramBytes = estimatedVramBytes,
                recommendedLayers = calculateRecommendedLayers(estimatedVramBytes),
                deviceName = deviceName,
                driverVersion = "Android ${Build.VERSION.RELEASE}"
            )
        } catch (e: Exception) {
            Logger.w("GpuMemoryManager: failed to detect capabilities", mapOf("error" to e.message), e)
            // Fallback to conservative defaults
            GpuCapabilities(
                hasVulkan = false,
                maxVramBytes = 1L * 1024 * 1024 * 1024, // 1GB
                recommendedLayers = 10,
                deviceName = "unknown",
                driverVersion = "unknown"
            )
        }
    }

    /**
     * Calculate optimal GPU layer allocation for a specific model
     */
    fun calculateOptimalLayers(
        manifest: ModelManifest,
        contextLength: Int,
        targetMemoryUsage: Double = 0.7 // Use 70% of available VRAM
    ): MemoryProfile {
        val capabilities = detectCapabilities()

        if (!capabilities.hasVulkan) {
            return MemoryProfile(
                modelSizeBytes = manifest.sizeBytes,
                contextLength = contextLength,
                recommendedGpuLayers = 0,
                estimatedVramUsageBytes = 0,
                canUseGpu = false,
                reasoning = "Vulkan not supported on this device"
            )
        }

        // Estimate VRAM requirements per layer (rough heuristic based on model architecture)
        val vramPerLayerBytes = when {
            manifest.family.contains("qwen", ignoreCase = true) -> 150L * 1024 * 1024 // ~150MB per layer
            manifest.family.contains("llama", ignoreCase = true) -> 120L * 1024 * 1024 // ~120MB per layer
            manifest.family.contains("mistral", ignoreCase = true) -> 100L * 1024 * 1024 // ~100MB per layer
            manifest.family.contains("granite", ignoreCase = true) -> 90L * 1024 * 1024 // ~90MB per layer
            else -> 130L * 1024 * 1024 // Default ~130MB per layer
        }

        // Estimate base model memory usage (not offloaded to GPU)
        val baseModelMemoryBytes = kotlin.math.min(
            manifest.sizeBytes / 4.0, // Assume 25% stays in CPU memory
            1L * 1024 * 1024 * 1024.0 // Cap at 1GB
        ).toLong()

        // Estimate KV cache memory per layer (scales with context length)
        val kvCachePerLayerBytes = (contextLength / 1000) * 50L * 1024 * 1024 // ~50MB per 1000 tokens per layer

        // Calculate available VRAM for layers (accounting for base model and KV cache)
        val availableVramBytes = (capabilities.maxVramBytes * targetMemoryUsage).toLong()
        val reservedForBaseAndKvBytes = baseModelMemoryBytes + (kvCachePerLayerBytes * capabilities.recommendedLayers)

        val availableForLayersBytes = max(0, availableVramBytes - reservedForBaseAndKvBytes)

        // Calculate how many layers we can fit
        val maxLayersByVram = (availableForLayersBytes / vramPerLayerBytes).toInt()

        // Apply model-specific limits
        val modelMaxLayers = when {
            manifest.contextLength <= 4096 -> 35 // Small models can use more layers
            manifest.contextLength <= 8192 -> 25 // Medium models
            else -> 20 // Large models need to be conservative
        }

        val recommendedLayers = kotlin.math.min(
            kotlin.math.min(maxLayersByVram, modelMaxLayers),
            capabilities.recommendedLayers
        ).coerceAtLeast(0)

        // Calculate estimated VRAM usage
        val estimatedVramUsageBytes = baseModelMemoryBytes +
            (recommendedLayers * vramPerLayerBytes) +
            (recommendedLayers * kvCachePerLayerBytes)

        val reasoning = buildString {
            append("Device: ${capabilities.deviceName}, ")
            append("VRAM: ${capabilities.maxVramBytes / (1024*1024*1024)}GB, ")
            append("Model: ${manifest.name}, ")
            append("Layers: $recommendedLayers/${capabilities.recommendedLayers} max, ")
            append("Usage: ${estimatedVramUsageBytes / (1024*1024)}MB")
        }

        return MemoryProfile(
            modelSizeBytes = manifest.sizeBytes,
            contextLength = contextLength,
            recommendedGpuLayers = recommendedLayers,
            estimatedVramUsageBytes = estimatedVramUsageBytes,
            canUseGpu = recommendedLayers > 0,
            reasoning = reasoning
        )
    }

    /**
     * Get adaptive GPU layer count based on current system state
     */
    fun getAdaptiveLayers(
        manifest: ModelManifest,
        requestedLayers: Int,
        contextLength: Int
    ): Int {
        val profile = calculateOptimalLayers(manifest, contextLength)

        return when {
            !profile.canUseGpu -> 0
            requestedLayers <= 0 -> profile.recommendedGpuLayers
            requestedLayers > profile.recommendedGpuLayers -> {
                Logger.w("GpuMemoryManager: requested layers ($requestedLayers) exceed recommended (${profile.recommendedGpuLayers})",
                    mapOf("reasoning" to profile.reasoning))
                profile.recommendedGpuLayers // Cap at recommended
            }
            else -> requestedLayers
        }
    }

    /**
     * Check if GPU acceleration is recommended for this model/device combination
     */
    fun shouldUseGpu(manifest: ModelManifest, contextLength: Int): Boolean {
        val profile = calculateOptimalLayers(manifest, contextLength)
        return profile.canUseGpu && profile.recommendedGpuLayers >= 5 // Need at least 5 layers for meaningful GPU acceleration
    }

    private fun calculateRecommendedLayers(maxVramBytes: Long): Int {
        // Conservative estimate: assume 200MB per layer on average
        val bytesPerLayer = 200L * 1024 * 1024
        return (maxVramBytes / bytesPerLayer).toInt().coerceIn(5, 50)
    }

    companion object {
        private const val TAG = "GpuMemoryManager"
    }
}
