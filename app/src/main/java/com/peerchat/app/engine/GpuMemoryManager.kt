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
     * Model family memory profiles for accurate VRAM estimation
     */
    data class ModelFamilyProfile(
        val vramPerLayerMB: Long,
        val baseMemoryMB: Long,
        val kvCachePerLayerPer1kTokensMB: Float,
        val maxLayersSmallContext: Int, // <= 4k context
        val maxLayersMediumContext: Int, // 4k-8k context
        val maxLayersLargeContext: Int // > 8k context
    )

    val modelFamilyProfiles = mapOf(
        // Qwen models - efficient transformer architecture
        "qwen" to ModelFamilyProfile(
            vramPerLayerMB = 150L,
            baseMemoryMB = 800L,
            kvCachePerLayerPer1kTokensMB = 50f,
            maxLayersSmallContext = 40,
            maxLayersMediumContext = 30,
            maxLayersLargeContext = 25
        ),
        // Llama models - standard transformer
        "llama" to ModelFamilyProfile(
            vramPerLayerMB = 120L,
            baseMemoryMB = 600L,
            kvCachePerLayerPer1kTokensMB = 45f,
            maxLayersSmallContext = 35,
            maxLayersMediumContext = 25,
            maxLayersLargeContext = 20
        ),
        // Mistral models - efficient architecture
        "mistral" to ModelFamilyProfile(
            vramPerLayerMB = 100L,
            baseMemoryMB = 500L,
            kvCachePerLayerPer1kTokensMB = 40f,
            maxLayersSmallContext = 38,
            maxLayersMediumContext = 28,
            maxLayersLargeContext = 22
        ),
        // Granite models - IBM's efficient models
        "granite" to ModelFamilyProfile(
            vramPerLayerMB = 90L,
            baseMemoryMB = 450L,
            kvCachePerLayerPer1kTokensMB = 38f,
            maxLayersSmallContext = 42,
            maxLayersMediumContext = 32,
            maxLayersLargeContext = 25
        ),
        // Phi models - Microsoft's efficient models
        "phi" to ModelFamilyProfile(
            vramPerLayerMB = 80L,
            baseMemoryMB = 400L,
            kvCachePerLayerPer1kTokensMB = 35f,
            maxLayersSmallContext = 45,
            maxLayersMediumContext = 35,
            maxLayersLargeContext = 28
        )
    )

    /**
     * Detect GPU capabilities of the current device
     */
    fun detectCapabilities(): GpuCapabilities {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memoryInfo)
            val totalRamBytes = memoryInfo.totalMem
            
            // Enhanced Vulkan detection
            val vulkanSupported = detectVulkanSupport()
            
            // Estimate VRAM based on total RAM and device capabilities
            val estimatedVramBytes = estimateVramBytes(totalRamBytes, vulkanSupported)
            
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val driverVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
            
            val recommendedLayers = calculateRecommendedLayers(estimatedVramBytes)
            
            val capabilities = GpuCapabilities(
                hasVulkan = vulkanSupported,
                maxVramBytes = estimatedVramBytes,
                recommendedLayers = recommendedLayers,
                deviceName = deviceName,
                driverVersion = driverVersion
            )
            
            // Log GPU capabilities for diagnostics
            Logger.i("GpuMemoryManager: detected capabilities", mapOf(
                "device" to deviceName,
                "hasVulkan" to vulkanSupported,
                "estimatedVramGB" to (estimatedVramBytes / (1024L * 1024 * 1024)),
                "recommendedLayers" to recommendedLayers,
                "totalRamGB" to (totalRamBytes / (1024L * 1024 * 1024)),
                "sdkLevel" to Build.VERSION.SDK_INT
            ))
            
            capabilities
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
     * Detect Vulkan support on the device
     */
    private fun detectVulkanSupport(): Boolean {
        return try {
            // Check SDK level - Vulkan support was added in API 24
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                Logger.d("GpuMemoryManager: SDK level too low for Vulkan", mapOf("sdk" to Build.VERSION.SDK_INT))
                return false
            }
            
            // Try to load Vulkan library (if available)
            // Note: Actual Vulkan validation would require JNI calls to vulkan.so
            // For now, use heuristics based on device properties
            
            // Check if device is likely to have Vulkan support
            // Most modern Android devices (API 24+) support Vulkan
            val hasVulkan = when {
                // Exclude known problematic devices
                Build.MANUFACTURER.equals("samsung", ignoreCase = true) && 
                Build.VERSION.SDK_INT < Build.VERSION_CODES.O -> {
                    // Some Samsung devices had Vulkan issues on older Android versions
                    false
                }
                // High-end devices likely have Vulkan
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> true // Android 9+
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N -> {
                    // Android 7+ - check device RAM as proxy for GPU capability
                    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                    val memInfo = ActivityManager.MemoryInfo()
                    activityManager?.getMemoryInfo(memInfo)
                    val totalRamGB = memInfo.totalMem / (1024L * 1024 * 1024)
                    totalRamGB >= 3 // Devices with 3GB+ RAM likely have Vulkan-capable GPUs
                }
                else -> false
            }
            
            if (!hasVulkan) {
                Logger.d("GpuMemoryManager: Vulkan not supported", mapOf(
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "sdk" to Build.VERSION.SDK_INT
                ))
            }
            
            hasVulkan
        } catch (e: Exception) {
            Logger.w("GpuMemoryManager: Vulkan detection failed", mapOf("error" to e.message), e)
            false
        }
    }
    
    /**
     * Estimate VRAM bytes based on device RAM and Vulkan support
     */
    private fun estimateVramBytes(totalRamBytes: Long, hasVulkan: Boolean): Long {
        if (!hasVulkan) {
            return 0L
        }
        
        // Estimate VRAM based on total RAM (rough heuristic)
        // Modern mobile GPUs typically share system RAM
        return when {
            // High-end devices (16GB+ RAM) likely have good GPUs
            totalRamBytes >= 16L * 1024 * 1024 * 1024 -> 8L * 1024 * 1024 * 1024 // 8GB VRAM
            totalRamBytes >= 12L * 1024 * 1024 * 1024 -> 6L * 1024 * 1024 * 1024  // 6GB VRAM
            totalRamBytes >= 8L * 1024 * 1024 * 1024 -> 4L * 1024 * 1024 * 1024  // 4GB VRAM
            totalRamBytes >= 6L * 1024 * 1024 * 1024 -> 3L * 1024 * 1024 * 1024  // 3GB VRAM
            totalRamBytes >= 4L * 1024 * 1024 * 1024 -> 2L * 1024 * 1024 * 1024  // 2GB VRAM
            else -> 1L * 1024 * 1024 * 1024 // 1GB VRAM (minimum)
        }
    }
    
    /**
     * Test Vulkan fallback path by attempting to query capabilities
     * Returns true if fallback should be used
     */
    fun shouldUseFallback(manifest: ModelManifest, contextLength: Int): Boolean {
        val capabilities = detectCapabilities()
        
        if (!capabilities.hasVulkan) {
            Logger.d("GpuMemoryManager: using CPU fallback - Vulkan not supported")
            return true
        }
        
        val profile = calculateOptimalLayers(manifest, contextLength)
        
        if (!profile.canUseGpu || profile.recommendedGpuLayers < 5) {
            Logger.d("GpuMemoryManager: using CPU fallback - insufficient GPU layers", mapOf(
                "recommendedLayers" to profile.recommendedGpuLayers,
                "reasoning" to profile.reasoning
            ))
            return true
        }
        
        return false
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

        // Get model family-specific memory profile
        val familyProfile = getModelFamilyProfile(manifest)
        
        // Convert MB to bytes
        val vramPerLayerBytes = familyProfile.vramPerLayerMB * 1024 * 1024
        val baseModelMemoryBytes = familyProfile.baseMemoryMB * 1024 * 1024
        
        // Estimate KV cache memory per layer (scales with context length)
        val kvCachePerLayerBytes = (contextLength / 1000.0f * familyProfile.kvCachePerLayerPer1kTokensMB * 1024 * 1024).toLong()

        // Calculate available VRAM for layers (accounting for base model and KV cache)
        val availableVramBytes = (capabilities.maxVramBytes * targetMemoryUsage).toLong()
        
        // Reserve memory for base model and KV cache (estimate with average layer count)
        val avgLayers = capabilities.recommendedLayers
        val reservedForBaseAndKvBytes = baseModelMemoryBytes + (kvCachePerLayerBytes * avgLayers)

        val availableForLayersBytes = max(0, availableVramBytes - reservedForBaseAndKvBytes)

        // Calculate how many layers we can fit based on VRAM
        val maxLayersByVram = (availableForLayersBytes / vramPerLayerBytes).toInt()

        // Apply model-specific limits based on context length
        val modelMaxLayers = when {
            contextLength <= 4096 -> familyProfile.maxLayersSmallContext
            contextLength <= 8192 -> familyProfile.maxLayersMediumContext
            else -> familyProfile.maxLayersLargeContext
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
            append("Usage: ${estimatedVramUsageBytes / (1024*1024)}MB, ")
            append("Vulkan: ${if (capabilities.hasVulkan) "yes" else "no"}")
        }

        Logger.d("GpuMemoryManager: calculated memory profile", mapOf(
            "model" to manifest.name,
            "recommendedLayers" to recommendedLayers,
            "estimatedVramMB" to (estimatedVramUsageBytes / (1024 * 1024)),
            "canUseGpu" to (recommendedLayers > 0),
            "reasoning" to reasoning
        ))

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
     * Get memory profile for a specific model family
     */
    fun getModelFamilyProfile(manifest: ModelManifest): ModelFamilyProfile {
        val familyLower = manifest.family.lowercase()
        return modelFamilyProfiles.entries.firstOrNull { (key, _) ->
            familyLower.contains(key, ignoreCase = true)
        }?.value ?: ModelFamilyProfile(
            // Default profile for unknown families
            vramPerLayerMB = 130L,
            baseMemoryMB = 650L,
            kvCachePerLayerPer1kTokensMB = 48f,
            maxLayersSmallContext = 35,
            maxLayersMediumContext = 25,
            maxLayersLargeContext = 20
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
