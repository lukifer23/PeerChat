package com.peerchat.app.testutil

import com.peerchat.engine.EngineConfig
import com.peerchat.engine.EngineMetrics
import com.peerchat.engine.EngineRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fake EngineRuntime for testing.
 * Provides controllable state and behavior without requiring actual native engine.
 */
class FakeEngineRuntime {
    private val _status = MutableStateFlow<EngineRuntime.EngineStatus>(EngineRuntime.EngineStatus.Uninitialized)
    val status: StateFlow<EngineRuntime.EngineStatus> = _status

    private val _metrics = MutableStateFlow(EngineMetrics.empty())
    val metrics: StateFlow<EngineMetrics> = _metrics

    private val _modelMeta = MutableStateFlow<String?>(null)
    val modelMeta: StateFlow<String?> = _modelMeta

    private var capturedState: ByteArray? = null

    suspend fun load(config: EngineConfig): Boolean {
        _status.value = EngineRuntime.EngineStatus.Loading(config)
        // Simulate successful load
        _status.value = EngineRuntime.EngineStatus.Loaded(config)
        _modelMeta.value = """{"arch":"llama","nCtxTrain":4096}"""
        return true
    }

    suspend fun unload() {
        _status.value = EngineRuntime.EngineStatus.Idle
        _modelMeta.value = null
        capturedState = null
    }

    fun captureState(): ByteArray? = capturedState

    suspend fun restoreState(state: ByteArray): Boolean {
        capturedState = state
        return state.isNotEmpty()
    }

    suspend fun clearState(clearData: Boolean) {
        if (clearData) {
            capturedState = null
        }
    }

    fun updateMetricsFromNative() {
        // No-op for testing
    }

    fun currentModelMeta(): String? = _modelMeta.value

    fun setMetrics(metrics: EngineMetrics) {
        _metrics.value = metrics
    }

    fun setStatus(status: EngineRuntime.EngineStatus) {
        _status.value = status
    }
}

