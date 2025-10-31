package com.peerchat.app.util

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Performance monitoring utilities
 */
object PerformanceMonitor {
    private val metrics = mutableMapOf<String, MutableList<Long>>()
    private const val MAX_SAMPLES = 100

    fun recordDuration(operation: String, durationMs: Long) {
        synchronized(metrics) {
            val samples = metrics.getOrPut(operation) { mutableListOf() }
            samples.add(durationMs)
            if (samples.size > MAX_SAMPLES) {
                samples.removeAt(0) // Remove oldest
            }
        }
    }

    fun getAverageDuration(operation: String): Double? {
        return synchronized(metrics) {
            metrics[operation]?.takeIf { it.isNotEmpty() }?.average()
        }
    }

    fun getLastDuration(operation: String): Long? {
        return synchronized(metrics) {
            metrics[operation]?.lastOrNull()
        }
    }

    fun getStats(operation: String): PerformanceStats? {
        return synchronized(metrics) {
            metrics[operation]?.takeIf { it.isNotEmpty() }?.let { samples ->
                PerformanceStats(
                    count = samples.size,
                    average = samples.average(),
                    min = samples.minOrNull() ?: 0L,
                    max = samples.maxOrNull() ?: 0L,
                    p95 = samples.sorted()[maxOf(0, (samples.size * 0.95).toInt() - 1)].toDouble()
                )
            }
        }
    }

    fun clearMetrics(operation: String? = null) {
        synchronized(metrics) {
            if (operation != null) {
                metrics.remove(operation)
            } else {
                metrics.clear()
            }
        }
    }
}

data class PerformanceStats(
    val count: Int,
    val average: Double,
    val min: Long,
    val max: Long,
    val p95: Double
)

/**
 * Measure execution time of a suspend block
 */
suspend inline fun <T> measureDuration(operation: String, block: suspend () -> T): T {
    val start = System.nanoTime()
    return try {
        block()
    } finally {
        val durationMs = (System.nanoTime() - start) / 1_000_000
        PerformanceMonitor.recordDuration(operation, durationMs)
    }
}

/**
 * Format duration for display
 */
fun formatDuration(ms: Long): String {
    return when {
        ms < 1000 -> "${ms}ms"
        ms < 60000 -> "${ms / 1000}.${ms % 1000 / 100}s"
        else -> "${ms / 60000}m ${ms % 60000 / 1000}s"
    }
}

/**
 * Format file size for display
 */
fun formatFileSize(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var size = bytes.toDouble()
    var unitIndex = 0

    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }

    return if (unitIndex == 0) {
        "$bytes B"
    } else {
        "${String.format("%.1f", size)} ${units[unitIndex]}"
    }
}

/**
 * Input sanitization utilities for security
 */
object InputSanitizer {

    private val DANGEROUS_PATTERNS = listOf(
        Regex("<script[^>]*>.*?</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("javascript:", setOf(RegexOption.IGNORE_CASE)),
        Regex("data:", setOf(RegexOption.IGNORE_CASE)),
        Regex("vbscript:", setOf(RegexOption.IGNORE_CASE)),
        Regex("on\\w+\\s*=", setOf(RegexOption.IGNORE_CASE)),
        Regex("<iframe[^>]*>.*?</iframe>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("<object[^>]*>.*?</object>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("<embed[^>]*>.*?</embed>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("<img[^>]*>", setOf(RegexOption.IGNORE_CASE)),
        Regex("<form[^>]*>.*?</form>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
        Regex("<input[^>]*>", setOf(RegexOption.IGNORE_CASE)),
        Regex("<meta[^>]*>", setOf(RegexOption.IGNORE_CASE)),
        Regex("<link[^>]*>", setOf(RegexOption.IGNORE_CASE)),
        Regex("eval\\(", setOf(RegexOption.IGNORE_CASE)),
        Regex("expression\\(", setOf(RegexOption.IGNORE_CASE)),
        Regex("vbscript:", setOf(RegexOption.IGNORE_CASE)),
        Regex("mocha:", setOf(RegexOption.IGNORE_CASE)),
        Regex("livescript:", setOf(RegexOption.IGNORE_CASE))
    )

    private const val MAX_INPUT_LENGTH = 50000 // Reasonable limit for chat messages
    private const val MAX_FILENAME_LENGTH = 255

    /**
     * Sanitize user input text for chat messages
     */
    fun sanitizeChatInput(input: String): String {
        if (input.length > MAX_INPUT_LENGTH) {
            throw SecurityException("Input too long: maximum $MAX_INPUT_LENGTH characters allowed")
        }

        var sanitized = input.trim()

        // Remove or neutralize dangerous patterns
        for (pattern in DANGEROUS_PATTERNS) {
            sanitized = sanitized.replace(pattern, "[REMOVED]")
        }

        // Normalize whitespace
        sanitized = sanitized.replace(Regex("\\s+"), " ")

        // Remove control characters except common whitespace
        sanitized = sanitized.replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]"), "")

        return sanitized
    }

    /**
     * Sanitize filename input
     */
    fun sanitizeFilename(filename: String): String {
        if (filename.length > MAX_FILENAME_LENGTH) {
            throw SecurityException("Filename too long: maximum $MAX_FILENAME_LENGTH characters allowed")
        }

        val sanitized = filename
            .trim()
            .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
            .replace(Regex("^\\.+"), "")
            .replace(Regex("\\.+$"), "")
            .replace(Regex("\\.{2,}"), ".")

        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            throw SecurityException("Invalid filename: ${filename}")
        }

        return sanitized
    }

    /**
     * Validate URL for document imports
     */
    fun isValidDocumentUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            uri.scheme in listOf("https", "http") &&
            uri.host?.isNotBlank() == true &&
            uri.path?.let { path ->
                !path.contains("..") && !path.contains("\\") &&
                path.length <= 2048 // Prevent extremely long paths
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if input contains potentially dangerous content
     */
    fun containsDangerousContent(input: String): Boolean {
        return DANGEROUS_PATTERNS.any { pattern ->
            pattern.containsMatchIn(input)
        }
    }
}

/**
 * Utility functions for formatting values for display.
 */
object FormatUtils {
    /**
     * Formats a byte count into a human-readable string (e.g., "1.5 MB", "512 KB").
     *
     * @param bytes The number of bytes to format.
     * @return A formatted string with appropriate unit (B, KB, MB, GB, TB).
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return when {
            unitIndex == 0 -> "%d %s".format(bytes.toInt(), units[unitIndex])
            size < 10 -> "%.2f %s".format(size, units[unitIndex])
            size < 100 -> "%.1f %s".format(size, units[unitIndex])
            else -> "%.0f %s".format(size, units[unitIndex])
        }
    }
}
