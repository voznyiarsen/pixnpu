package com.pixnpu.util

import java.util.Locale

object Fmt {
    fun bytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.ROOT, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.ROOT, "%.2f GB", gb)
    }

    fun speed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "-"
        return "${bytes(bytesPerSecond)}/s"
    }

    fun ms(ms: Long): String = String.format(Locale.ROOT, "%.2fs", ms / 1000.0)

    fun duration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", m, s)
        }
    }

    fun sha(hex: String?): String {
        if (hex.isNullOrBlank()) return "unknown"
        return "${hex.take(8)}…${hex.takeLast(8)}"
    }
}