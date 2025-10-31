package com.peerchat.app.util

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

