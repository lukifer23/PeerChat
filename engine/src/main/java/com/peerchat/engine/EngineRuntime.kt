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
