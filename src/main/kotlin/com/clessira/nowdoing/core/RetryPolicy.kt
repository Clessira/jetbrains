package com.clessira.nowdoing.core

/** Port of the retry helpers in `vscode/src/util.ts`. */
object RetryPolicy {
    const val MAX_RETRY_ATTEMPTS = 4

    /** 1s · 2^(attempt−1), capped at 30s. */
    fun getRetryDelayMs(attempt: Int): Long {
        val safeAttempt = maxOf(1, attempt)
        if (safeAttempt >= 6) return 30_000L
        return minOf(1000L shl (safeAttempt - 1), 30_000L)
    }

    fun isRetryableNotifyError(error: Throwable): Boolean =
        isRetryableNotifyError(error.toString())

    /**
     * The VS Code extension classifies by Node error strings (econnrefused…);
     * the JVM equivalents say "Connection refused" / "Connection reset".
     */
    fun isRetryableNotifyError(message: String): Boolean {
        val m = message.lowercase()
        return "http 429" in m ||
            "http 503" in m ||
            "timeout" in m ||
            "connection refused" in m ||
            "connection reset" in m ||
            "host unreachable" in m ||
            "network unreachable" in m ||
            "econnrefused" in m ||
            "econnreset" in m ||
            "ehostunreach" in m ||
            "enetunreach" in m
    }
}
