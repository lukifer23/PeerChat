package com.peerchat.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

object EngineRuntime {
    private val initOnce = AtomicBoolean(false)
    private val mutex = Mutex()

    private val _status = MutableStateFlow<EngineStatus>(EngineStatus.Uninitialized)
    val status: StateFlow<EngineStatus> = _status

    private val _metrics = MutableStateFlow(EngineMetrics.empty())
    val metrics: StateFlow<EngineMetrics> = _metrics

    private val _modelMeta = MutableStateFlow<String?>(null)
    val modelMeta: StateFlow<String?> = _modelMeta

    fun ensureInitialized() {
        if (initOnce.compareAndSet(false, true)) {
            EngineNative.init()
            _status.value = EngineStatus.Idle
        }
    }

    suspend fun load(config: EngineConfig): Boolean = mutex.withLock {
        ensureInitialized()
        _status.value = EngineStatus.Loading(config)
        val success = withContext(Dispatchers.IO) {
            EngineNative.loadModel(
                config.modelPath,
                config.threads,
                config.contextLength,
                config.gpuLayers,
                config.useVulkan
            )
        }
        if (success) {
            _status.value = EngineStatus.Loaded(config)
            updateMetricsFromNative()
            // detect and cache model metadata for template autodetection
            val meta = withContext(Dispatchers.IO) {
                runCatching { EngineNative.detectModel(config.modelPath) }.getOrNull()
            }
            _modelMeta.value = meta
        } else {
            _status.value = EngineStatus.Error("Failed to load ${config.modelPath}")
        }
        success
    }

    suspend fun unload() = mutex.withLock {
        if (_status.value is EngineStatus.Uninitialized) return@withLock
        withContext(Dispatchers.IO) {
            EngineNative.unload()
        }
        _status.value = EngineStatus.Idle
        _metrics.value = EngineMetrics.empty()
    }

    fun updateMetrics(metrics: EngineMetrics) {
        _metrics.value = metrics
    }

    fun updateMetricsFromNative(): EngineMetrics {
        val metrics = EngineMetrics.fromJson(EngineNative.metrics())
        updateMetrics(metrics)
        return metrics
    }

    fun currentModelMeta(): String? = _modelMeta.value

    suspend fun captureState(): ByteArray? = mutex.withLock {
        ensureInitialized()
        val snapshot = withContext(Dispatchers.IO) { EngineNative.stateCapture() }
        if (snapshot.isEmpty()) null else snapshot
    }

    suspend fun restoreState(snapshot: ByteArray): Boolean = mutex.withLock {
        if (snapshot.isEmpty()) return@withLock false
        ensureInitialized()
        val restored = withContext(Dispatchers.IO) { EngineNative.stateRestore(snapshot) }
        if (restored) {
            updateMetrics(EngineMetrics.empty())
        }
        restored
    }

    suspend fun clearState(clearData: Boolean = false) = mutex.withLock {
        ensureInitialized()
        withContext(Dispatchers.IO) { EngineNative.stateClear(clearData) }
        updateMetrics(EngineMetrics.empty())
    }

    data class EngineConfig(
        val modelPath: String,
        val threads: Int,
        val contextLength: Int,
        val gpuLayers: Int,
        val useVulkan: Boolean = true,
    )

    sealed interface EngineStatus {
        data object Uninitialized : EngineStatus
        data object Idle : EngineStatus
        data class Loading(val config: EngineConfig) : EngineStatus
        data class Loaded(val config: EngineConfig) : EngineStatus
        data class Error(val message: String) : EngineStatus
    }
}
