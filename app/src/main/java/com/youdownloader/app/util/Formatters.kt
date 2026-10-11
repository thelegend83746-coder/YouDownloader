package com.youdownloader.app.util

import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {

    /**
     * Formats milliseconds into human-readable duration:
     * e.g., 03:45 or 01:23:45
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0) return "00:00"
        val hours = TimeUnit.MILLISECONDS.toHours(durationMs)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs) % 60

        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats bytes into human-readable string: B, KB, MB, GB
     */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }

    /**
     * Formats download speed in bytes/sec
     */
    fun formatSpeed(bytesPerSec: Long): String {
        return "${formatFileSize(bytesPerSec)}/s"
    }
}
