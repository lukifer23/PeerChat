package com.peerchat.app.engine

import android.content.Context
import android.os.Debug
import android.os.Process
import com.peerchat.app.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Performance monitoring service for tracking system metrics and detecting performance issues.
 */
class PerformanceMonitor(private val context: Context) {

    data class PerformanceSnapshot(
        val timestamp: Long,
        val heapAllocatedMB: Long,
        val heapFreeMB: Long,
        val nativeAllocatedKB: Int,
        val dalvikAllocatedKB: Int,
        val totalAllocatedKB: Int,
        val gcCount: Long,
        val gcTime: Long,
        val cpuTimeNs: Long,
        val threadCount: Int,
        val activeThreads: Int
    )

    data class PerformanceAlert(
        val type: AlertType,
        val severity: Severity,
        val message: String,
        val timestamp: Long,
        val context: Map<String, Any?>
    )

    enum class AlertType {
        MEMORY_PRESSURE,
        GC_SPIKE,
        THREAD_LEAK,
        CPU_SPIKE,
        MEMORY_LEAK
    }

    enum class Severity {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val snapshots = ConcurrentHashMap<Long, PerformanceSnapshot>()
    private val alerts = mutableListOf<PerformanceAlert>()
    private val monitoringJob: Job? = null

    private var baselineSnapshot: PerformanceSnapshot? = null
    private var lastGcCount: Long = 0
    private var lastGcTime: Long = 0

    /**
     * Start continuous performance monitoring
     */
    fun startMonitoring(intervalMs: Long = 5000) {
        if (monitoringJob?.isActive == true) return

        Logger.i("PerformanceMonitor: starting monitoring", mapOf("intervalMs" to intervalMs))

        scope.launch {
            // Take baseline snapshot
            baselineSnapshot = takeSnapshot()
            lastGcCount = baselineSnapshot?.gcCount ?: 0
            lastGcTime = baselineSnapshot?.gcTime ?: 0

            Logger.perf("PerformanceMonitor: baseline established", getSnapshotFields(baselineSnapshot))

            while (isActive) {
                try {
                    val snapshot = takeSnapshot()
                    snapshots[snapshot.timestamp] = snapshot

                    // Analyze for issues
                    analyzeSnapshot(snapshot)

                    // Keep only recent snapshots (last 5 minutes)
                    val cutoffTime = System.currentTimeMillis() - 300_000
                    snapshots.entries.removeIf { it.key < cutoffTime }

                    delay(intervalMs)
                } catch (e: Exception) {
                    Logger.errorContext("PerformanceMonitor: monitoring error", e)
                    delay(intervalMs)
                }
            }
        }
    }

    /**
     * Stop performance monitoring
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        Logger.i("PerformanceMonitor: monitoring stopped")
    }

    /**
     * Take a performance snapshot
     */
    private fun takeSnapshot(): PerformanceSnapshot {
        val timestamp = System.currentTimeMillis()

        val runtime = Runtime.getRuntime()
        val memoryInfo = Debug.MemoryInfo().apply { Debug.getMemoryInfo(this) }

        val gcCount = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: 0
        val gcTime = Debug.getRuntimeStat("art.gc.gc-time")?.toLongOrNull() ?: 0

        val threadGroup = Thread.currentThread().threadGroup
        val activeThreads = threadGroup?.activeCount() ?: 0

        return PerformanceSnapshot(
            timestamp = timestamp,
            heapAllocatedMB = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024,
            heapFreeMB = runtime.freeMemory() / 1024 / 1024,
            nativeAllocatedKB = memoryInfo.nativePss,
            dalvikAllocatedKB = memoryInfo.dalvikPss,
            totalAllocatedKB = memoryInfo.totalPss,
            gcCount = gcCount,
            gcTime = gcTime,
            cpuTimeNs = Debug.threadCpuTimeNanos(),
            threadCount = Thread.activeCount(),
            activeThreads = activeThreads
        )
    }

    /**
     * Analyze snapshot for performance issues
     */
    private fun analyzeSnapshot(snapshot: PerformanceSnapshot) {
        baselineSnapshot?.let { baseline ->

            // Memory pressure detection
            val memoryIncreaseMB = snapshot.heapAllocatedMB - baseline.heapAllocatedMB
            if (memoryIncreaseMB > 100) { // 100MB increase
                addAlert(AlertType.MEMORY_PRESSURE, Severity.MEDIUM,
                    "Significant memory increase detected: ${memoryIncreaseMB}MB",
                    snapshot.timestamp, getSnapshotFields(snapshot))
            }

            // GC spike detection
            val gcIncrease = snapshot.gcCount - lastGcCount
            val gcTimeIncrease = snapshot.gcTime - lastGcTime
            if (gcIncrease > 5 || gcTimeIncrease > 1000) { // 5+ GCs or 1+ second GC time
                addAlert(AlertType.GC_SPIKE, Severity.HIGH,
                    "GC spike detected: $gcIncrease events, ${gcTimeIncrease}ms time",
                    snapshot.timestamp, getSnapshotFields(snapshot))
            }

            // Thread leak detection
            if (snapshot.threadCount > baseline.threadCount + 10) {
                addAlert(AlertType.THREAD_LEAK, Severity.HIGH,
                    "Potential thread leak: ${snapshot.threadCount} active threads (${baseline.threadCount} baseline)",
                    snapshot.timestamp, getSnapshotFields(snapshot))
            }

            // Memory leak detection (simple heuristic)
            if (snapshot.totalAllocatedKB > baseline.totalAllocatedKB * 1.5) {
                addAlert(AlertType.MEMORY_LEAK, Severity.CRITICAL,
                    "Potential memory leak: ${snapshot.totalAllocatedKB}KB allocated (${baseline.totalAllocatedKB}KB baseline)",
                    snapshot.timestamp, getSnapshotFields(snapshot))
            }

            lastGcCount = snapshot.gcCount
            lastGcTime = snapshot.gcTime
        }
    }

    /**
     * Add a performance alert
     */
    private fun addAlert(type: AlertType, severity: Severity, message: String, timestamp: Long, context: Map<String, Any?>) {
        val alert = PerformanceAlert(type, severity, message, timestamp, context)
        alerts.add(alert)

        // Keep only recent alerts (last 100)
        if (alerts.size > 100) {
            alerts.removeAt(0)
        }

        Logger.w("PerformanceMonitor: ALERT ${severity.name} - ${type.name}", mapOf(
            "message" to message,
            "timestamp" to timestamp
        ) + context)
    }

    /**
     * Get recent performance snapshots
     */
    fun getRecentSnapshots(limit: Int = 10): List<PerformanceSnapshot> {
        return snapshots.values.sortedByDescending { it.timestamp }.take(limit)
    }

    /**
     * Get recent performance alerts
     */
    fun getRecentAlerts(limit: Int = 10): List<PerformanceAlert> {
        return alerts.sortedByDescending { it.timestamp }.take(limit)
    }

    /**
     * Get performance summary
     */
    fun getPerformanceSummary(): Map<String, Any?> {
        val recentSnapshots = getRecentSnapshots(20)
        if (recentSnapshots.isEmpty()) return emptyMap()

        val latest = recentSnapshots.first()
        val oldest = recentSnapshots.last()

        val avgHeapMB = recentSnapshots.map { it.heapAllocatedMB }.average()
        val avgMemoryKB = recentSnapshots.map { it.totalAllocatedKB }.average()
        val maxMemoryKB = recentSnapshots.maxOf { it.totalAllocatedKB }

        return mapOf(
            "latestSnapshot" to getSnapshotFields(latest),
            "timeSpanMinutes" to ((latest.timestamp - oldest.timestamp) / 60000.0),
            "averageHeapMB" to avgHeapMB,
            "averageMemoryKB" to avgMemoryKB,
            "peakMemoryKB" to maxMemoryKB,
            "totalGcEvents" to (latest.gcCount - (baselineSnapshot?.gcCount ?: 0)),
            "totalGcTimeMs" to (latest.gcTime - (baselineSnapshot?.gcTime ?: 0)),
            "alertCount" to alerts.size,
            "criticalAlerts" to alerts.count { it.severity == Severity.CRITICAL }
        )
    }

    /**
     * Export performance data for debugging
     */
    fun exportPerformanceData(): Map<String, Any?> {
        return mapOf(
            "snapshots" to snapshots.values.sortedBy { it.timestamp }.map { getSnapshotFields(it) },
            "alerts" to alerts.map { mapOf(
                "type" to it.type.name,
                "severity" to it.severity.name,
                "message" to it.message,
                "timestamp" to it.timestamp,
                "context" to it.context
            ) },
            "summary" to getPerformanceSummary(),
            "baseline" to baselineSnapshot?.let { getSnapshotFields(it) }
        )
    }

    /**
     * Convert snapshot to field map for logging
     */
    private fun getSnapshotFields(snapshot: PerformanceSnapshot?): Map<String, Any?> {
        return snapshot?.let {
            mapOf(
                "timestamp" to it.timestamp,
                "heapAllocatedMB" to it.heapAllocatedMB,
                "heapFreeMB" to it.heapFreeMB,
                "nativeAllocatedKB" to it.nativeAllocatedKB,
                "dalvikAllocatedKB" to it.dalvikAllocatedKB,
                "totalAllocatedKB" to it.totalAllocatedKB,
                "gcCount" to it.gcCount,
                "gcTime" to it.gcTime,
                "cpuTimeNs" to it.cpuTimeNs,
                "threadCount" to it.threadCount,
                "activeThreads" to it.activeThreads
            )
        } ?: emptyMap()
    }

    /**
     * Clean up resources
     */
    fun shutdown() {
        stopMonitoring()
        snapshots.clear()
        alerts.clear()
        Logger.i("PerformanceMonitor: shutdown complete")
    }

    companion object {
        private const val TAG = "PerformanceMonitor"
    }
}
