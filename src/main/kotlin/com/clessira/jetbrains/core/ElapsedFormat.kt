package com.clessira.jetbrains.core

object ElapsedFormat {
    /** Port of `extension.ts formatElapsed`: "<1m", "37m", "2h", "1h 5m". */
    fun formatElapsed(ms: Long): String {
        val totalMinutes = ms / 60_000
        if (totalMinutes < 1) return "<1m"
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        if (hours == 0L) return "${minutes}m"
        if (minutes == 0L) return "${hours}h"
        return "${hours}h ${minutes}m"
    }
}
