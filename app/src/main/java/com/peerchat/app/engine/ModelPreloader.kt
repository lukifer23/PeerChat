package com.peerchat.app.engine

import android.content.Context
import com.peerchat.app.data.OperationResult
import com.peerchat.app.util.Logger
import com.peerchat.data.db.ModelManifest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow

/**
 * Background model preloader that manages loading frequently used models
 * to reduce user-perceived load times.
 */
class ModelPreloader(
    private val context: Context,
    private val modelRepository: ModelRepository,
    private val maxConcurrentLoads: Int = 2,
    private val maxPreloadedModels: Int = 3,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private val activeLoads = AtomicInteger(0)

    // Preload queue with priority (lower priority number = higher priority)
    private val preloadQueue = PriorityBlockingQueue<PreloadRequest>(16) { a, b ->
        a.priority.compareTo(b.priority)
    }

    // Track currently preloaded models
    private val preloadedModels = ConcurrentHashMap<String, PreloadedModel>()
    private val recentlyUsedModels = ConcurrentHashMap<String, Long>()

    // Progress tracking
    private val _preloadStatus = MutableStateFlow<Map<String, PreloadStatus>>(emptyMap())
    val preloadStatus: StateFlow<Map<String, PreloadStatus>> = _preloadStatus.asStateFlow()

    data class PreloadRequest(
        val manifest: ModelManifest,
        val priority: Int = 0, // Lower = higher priority
        val config: StoredEngineConfig? = null
    )

    data class PreloadedModel(
        val manifest: ModelManifest,
        val config: StoredEngineConfig,
        val preloadedAt: Long,
        val accessCount: Int = 0
    )

    sealed class PreloadStatus {
        data object Queued : PreloadStatus()
        data class Loading(val progress: Float = 0f) : PreloadStatus()
        data class Ready(val config: StoredEngineConfig) : PreloadStatus()
        data class Failed(val error: String) : PreloadStatus()
    }

    /**
     * Start the preloader service
     */
    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            scope.launch {
                processPreloadQueue()
            }
            Logger.i("ModelPreloader: started")
        }
    }

    /**
     * Stop the preloader service and clear preloaded models
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            scope.coroutineContext.cancelChildren()
            preloadQueue.clear()
            preloadedModels.clear()
            _preloadStatus.value = emptyMap()
            Logger.i("ModelPreloader: stopped")
        }
    }

    /**
     * Request preloading of a model with given priority
     */
    fun requestPreload(manifest: ModelManifest, priority: Int = 0, config: StoredEngineConfig? = null) {
        if (!isRunning.get()) return

        val request = PreloadRequest(manifest, priority, config)
        val statusKey = manifest.filePath

        preloadQueue.put(request)
        _preloadStatus.value = _preloadStatus.value + (statusKey to PreloadStatus.Queued)

        Logger.i("ModelPreloader: queued preload", mapOf(
            "model" to manifest.name,
            "priority" to priority
        ))
    }

    /**
     * Mark a model as recently used to influence preload priorities
     */
    fun markRecentlyUsed(manifest: ModelManifest) {
        val now = System.currentTimeMillis()
        recentlyUsedModels[manifest.filePath] = now

        // Update access count for preloaded models
        preloadedModels[manifest.filePath]?.let { preloaded ->
            preloadedModels[manifest.filePath] = preloaded.copy(
                accessCount = preloaded.accessCount + 1
            )
        }

        // Trigger intelligent preloading of related models
        scope.launch {
            scheduleIntelligentPreloads()
        }
    }

    /**
     * Get a preloaded model if available
     */
    fun getPreloadedModel(modelPath: String): PreloadedModel? {
        return preloadedModels[modelPath]?.also { model ->
            markRecentlyUsed(model.manifest)
        }
    }

    /**
     * Check if a model is preloaded
     */
    fun isPreloaded(modelPath: String): Boolean = preloadedModels.containsKey(modelPath)

    /**
     * Get preload status for a specific model
     */
    fun getPreloadStatus(modelPath: String): PreloadStatus? = _preloadStatus.value[modelPath]

    /**
     * Clear all preloaded models and reset state
     */
    fun clearAll() {
        preloadedModels.clear()
        _preloadStatus.value = emptyMap()
        Logger.i("ModelPreloader: cleared all preloaded models")
    }

    /**
     * Get statistics about preloading performance
     */
    fun getStats(): PreloadStats {
        val now = System.currentTimeMillis()
        val recentUsage = recentlyUsedModels.entries
            .filter { now - it.value < RECENT_USAGE_WINDOW_MS }
            .sortedByDescending { it.value }

        return PreloadStats(
            activeLoads = activeLoads.get(),
            queuedRequests = preloadQueue.size,
            preloadedModels = preloadedModels.size,
            maxPreloadedModels = maxPreloadedModels,
            recentModels = recentUsage.take(5).map { it.key },
            preloadStatuses = _preloadStatus.value
        )
    }

    // Private implementation

    private suspend fun processPreloadQueue() {
        while (scope.isActive && isRunning.get()) {
            try {
                // Wait for a preload request
                val request = withContext(Dispatchers.IO) {
                    preloadQueue.take()
                }

                // Check if we should still preload this model
                if (!shouldPreload(request)) {
                    updateStatus(request.manifest.filePath, null)
                    continue
                }

                // Wait for available load slot
                while (activeLoads.get() >= maxConcurrentLoads) {
                    delay(100)
                }

                activeLoads.incrementAndGet()
                preloadModel(request)
                activeLoads.decrementAndGet()

                // Brief pause between loads to prevent overwhelming the system
                delay(500)

            } catch (e: InterruptedException) {
                break // Service is being shut down
            } catch (e: CancellationException) {
                break // Coroutine cancelled
            } catch (e: Exception) {
                Logger.e("ModelPreloader: error processing queue", mapOf("error" to e.message), e)
                delay(1000) // Brief pause on error
            }
        }
    }

    private fun shouldPreload(request: PreloadRequest): Boolean {
        val modelPath = request.manifest.filePath

        // Don't preload if already preloaded
        if (preloadedModels.containsKey(modelPath)) {
            return false
        }

        // Don't preload if we're at capacity and this isn't high priority
        if (preloadedModels.size >= maxPreloadedModels && request.priority > 1) {
            return false
        }

        // Don't preload if the model file doesn't exist
        if (!java.io.File(modelPath).exists()) {
            return false
        }

        return true
    }

    private suspend fun preloadModel(request: PreloadRequest) {
        val modelPath = request.manifest.filePath
        val statusKey = modelPath

        try {
            Logger.i("ModelPreloader: starting preload", mapOf(
                "model" to request.manifest.name,
                "priority" to request.priority
            ))

            updateStatus(statusKey, PreloadStatus.Loading(0f))

            // Create config for preloading (use minimal settings for faster loading)
            val config = request.config ?: createPreloadConfig(request.manifest)

            // Validate first
            val validation = modelRepository.validateModel(modelPath)
            if (validation is OperationResult.Failure) {
                updateStatus(statusKey, PreloadStatus.Failed(validation.error))
                return
            }

            updateStatus(statusKey, PreloadStatus.Loading(0.3f))

            // Attempt to load (but don't actually activate it)
            val loadResult = modelRepository.loadModel(config)

            when (loadResult) {
                is OperationResult.Success -> {
                    // Store as preloaded
                    val preloaded = PreloadedModel(
                        manifest = request.manifest,
                        config = config,
                        preloadedAt = System.currentTimeMillis(),
                        accessCount = 0
                    )

                    preloadedModels[modelPath] = preloaded
                    updateStatus(statusKey, PreloadStatus.Ready(config))

                    // Evict least recently used if over capacity
                    evictIfOverCapacity()

                    Logger.i("ModelPreloader: successfully preloaded", mapOf(
                        "model" to request.manifest.name,
                        "config" to config.toString()
                    ))
                }
                is OperationResult.Failure -> {
                    updateStatus(statusKey, PreloadStatus.Failed(loadResult.error))
                    Logger.w("ModelPreloader: failed to preload", mapOf(
                        "model" to request.manifest.name,
                        "error" to loadResult.error
                    ))
                }
            }

        } catch (e: Exception) {
            val error = "Preload failed: ${e.message}"
            updateStatus(statusKey, PreloadStatus.Failed(error))
            Logger.e("ModelPreloader: exception during preload", mapOf(
                "model" to request.manifest.name,
                "error" to error
            ), e)
        }
    }

    private fun createPreloadConfig(manifest: ModelManifest): StoredEngineConfig {
        // Use conservative settings for preloading to minimize resource usage
        return StoredEngineConfig(
            modelPath = manifest.filePath,
            threads = 2, // Minimal threads
            contextLength = minOf(manifest.contextLength, 2048), // Smaller context
            gpuLayers = 0, // CPU only for preloading
            useVulkan = false // Disable Vulkan for preloading
        )
    }

    private fun evictIfOverCapacity() {
        while (preloadedModels.size > maxPreloadedModels) {
            val oldest = preloadedModels.entries
                .minByOrNull { it.value.preloadedAt }
                ?: break

            val evicted = preloadedModels.remove(oldest.key)
            if (evicted != null) {
                updateStatus(oldest.key, null)
                Logger.i("ModelPreloader: evicted old model", mapOf(
                    "model" to evicted.manifest.name
                ))
            }
        }
    }

    private suspend fun scheduleIntelligentPreloads() {
        // Get recently used manifests
        val recentManifests = withContext(Dispatchers.IO) {
            try {
                val manifests = modelRepository.getManifestsFlow().first()
                manifests
                    .filter { manifest ->
                        val lastUsed = recentlyUsedModels[manifest.filePath]
                        lastUsed != null && System.currentTimeMillis() - lastUsed < RECENT_USAGE_WINDOW_MS
                    }
                    .sortedByDescending { recentlyUsedModels[it.filePath] ?: 0 }
                    .take(3) // Top 3 recent models
            } catch (e: Exception) {
                Logger.w(
                    "ModelPreloader: failed to get manifests for intelligent preloading",
                    mapOf("error" to e.message),
                    e
                )
                emptyList<ModelManifest>()
            }
        }

        // Preload high-priority models that aren't already loaded
        recentManifests.forEachIndexed { index, manifest ->
            if (!preloadedModels.containsKey(manifest.filePath)) {
                val priority = index // 0 = highest priority
                requestPreload(manifest, priority)
            }
        }
    }

    private fun updateStatus(modelPath: String, status: PreloadStatus?) {
        val current = _preloadStatus.value.toMutableMap()
        if (status != null) {
            current[modelPath] = status
        } else {
            current.remove(modelPath)
        }
        _preloadStatus.value = current
    }

    data class PreloadStats(
        val activeLoads: Int,
        val queuedRequests: Int,
        val preloadedModels: Int,
        val maxPreloadedModels: Int,
        val recentModels: List<String>,
        val preloadStatuses: Map<String, PreloadStatus>
    )

    companion object {
        private const val RECENT_USAGE_WINDOW_MS = 24 * 60 * 60 * 1000L // 24 hours
    }
}
