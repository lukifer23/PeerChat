package com.peerchat.app.util

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class PerformanceMonitorTest {

    @Test
    fun `recordDuration - stores duration correctly`() {
        val operation = "test_operation"
        val duration = 150L

        PerformanceMonitor.recordDuration(operation, duration)

        val stats = PerformanceMonitor.getStats(operation)
        assertNotNull("Should have stats", stats)
        assertEquals("Count should be 1", 1, stats?.count)
        assertEquals("Average should match", 150.0, stats?.average ?: 0.0, 0.1)
        assertEquals("Min should match", 150L, stats?.min)
        assertEquals("Max should match", 150L, stats?.max)

        // Cleanup
        PerformanceMonitor.clearMetrics(operation)
    }

    @Test
    fun `recordDuration - calculates statistics correctly with multiple samples`() {
        val operation = "multi_sample_operation"
        val durations = listOf(100L, 200L, 150L, 300L, 50L)

        durations.forEach { PerformanceMonitor.recordDuration(operation, it) }

        val stats = PerformanceMonitor.getStats(operation)
        assertNotNull("Should have stats", stats)
        assertEquals("Count should be 5", 5, stats?.count)
        assertEquals("Average should be 160", 160.0, stats?.average ?: 0.0, 0.1)
        assertEquals("Min should be 50", 50L, stats?.min)
        assertEquals("Max should be 300", 300L, stats?.max)

        // Test P95 (95th percentile)
        val expectedP95 = 300.0 // Should be close to max for small sample
        assertTrue("P95 should be reasonable", stats?.p95 ?: 0.0 >= 200.0)

        // Cleanup
        PerformanceMonitor.clearMetrics(operation)
    }

    @Test
    fun `getLastDuration - returns most recent duration`() {
        val operation = "last_duration_test"
        val durations = listOf(100L, 200L, 150L)

        durations.forEach { PerformanceMonitor.recordDuration(operation, it) }

        val lastDuration = PerformanceMonitor.getLastDuration(operation)
        assertEquals("Last duration should be 150", 150L, lastDuration)

        // Cleanup
        PerformanceMonitor.clearMetrics(operation)
    }

    @Test
    fun `clearMetrics - removes specific operation metrics`() {
        val operation1 = "operation1"
        val operation2 = "operation2"

        PerformanceMonitor.recordDuration(operation1, 100L)
        PerformanceMonitor.recordDuration(operation2, 200L)

        // Clear specific operation
        PerformanceMonitor.clearMetrics(operation1)

        assertNull("Operation1 should be cleared", PerformanceMonitor.getStats(operation1))
        assertNotNull("Operation2 should remain", PerformanceMonitor.getStats(operation2))

        // Cleanup
        PerformanceMonitor.clearMetrics()
    }

    @Test
    fun `clearMetrics - clears all metrics when no operation specified`() {
        val operation1 = "operation1"
        val operation2 = "operation2"

        PerformanceMonitor.recordDuration(operation1, 100L)
        PerformanceMonitor.recordDuration(operation2, 200L)

        // Clear all
        PerformanceMonitor.clearMetrics()

        assertNull("Operation1 should be cleared", PerformanceMonitor.getStats(operation1))
        assertNull("Operation2 should be cleared", PerformanceMonitor.getStats(operation2))
    }

    @Test
    fun `measureDuration - measures execution time correctly`() = runBlocking {
        val operation = "measure_test"
        var executionCount = 0

        val result = measureDuration(operation) {
            executionCount++
            delay(50) // Simulate some work
            "test_result"
        }

        assertEquals("Function should execute", 1, executionCount)
        assertEquals("Result should be returned", "test_result", result)

        val duration = PerformanceMonitor.getLastDuration(operation)
        assertNotNull("Duration should be recorded", duration)
        assertTrue("Duration should be at least 50ms", duration!! >= 50L)

        // Cleanup
        PerformanceMonitor.clearMetrics(operation)
    }

    @Test
    fun `measureDuration - records duration even on exception`() = runBlocking {
        val operation = "exception_test"

        try {
            measureDuration(operation) {
                delay(25)
                throw RuntimeException("Test exception")
            }
            fail("Should have thrown exception")
        } catch (e: RuntimeException) {
            assertEquals("Should be test exception", "Test exception", e.message)
        }

        val duration = PerformanceMonitor.getLastDuration(operation)
        assertNotNull("Duration should be recorded even on exception", duration)
        assertTrue("Duration should be at least 25ms", duration!! >= 25L)

        // Cleanup
        PerformanceMonitor.clearMetrics(operation)
    }

    @Test
    fun `formatDuration - formats durations correctly`() {
        assertEquals("50ms", formatDuration(50))
        assertEquals("1.5s", formatDuration(1500))
        assertEquals("2m 30s", formatDuration(150000))
        assertEquals("1m 0s", formatDuration(60000))
    }

    @Test
    fun `formatFileSize - formats file sizes correctly`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.5 KB", formatFileSize(1536))
        assertEquals("2.5 MB", formatFileSize(2621440))
        assertEquals("1.2 GB", formatFileSize(1288490189))
    }

    @Test
    fun `performance monitoring handles concurrent access`() = runBlocking {
        val operation = "concurrent_test"
        val jobs = List(10) {
            kotlinx.coroutines.async {
                repeat(100) {
                    PerformanceMonitor.recordDuration(operation, (Math.random() * 1000).toLong())
                    delay(1) // Small delay to simulate concurrent access
                }
            }
        }

        jobs.forEach { it.await() }

        val stats = PerformanceMonitor.getStats(operation)
        assertNotNull("Should have stats", stats)
        assertEquals("Should have 1000 samples", 1000, stats?.count)

        // Cleanup
        PerformanceMonitor.clearMetrics(operation)
    }
}
