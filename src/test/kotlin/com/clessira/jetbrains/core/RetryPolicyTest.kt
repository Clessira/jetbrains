package com.clessira.jetbrains.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Port of the retry cases in vscode/src/test/util.test.ts. */
class RetryPolicyTest {

    @Test
    fun `delays grow exponentially from one second`() {
        assertEquals(1000L, RetryPolicy.getRetryDelayMs(1))
        assertEquals(2000L, RetryPolicy.getRetryDelayMs(2))
        assertEquals(4000L, RetryPolicy.getRetryDelayMs(3))
        assertEquals(8000L, RetryPolicy.getRetryDelayMs(4))
        assertEquals(16000L, RetryPolicy.getRetryDelayMs(5))
    }

    @Test
    fun `delays are capped at thirty seconds`() {
        assertEquals(30_000L, RetryPolicy.getRetryDelayMs(6))
        assertEquals(30_000L, RetryPolicy.getRetryDelayMs(10))
        assertEquals(30_000L, RetryPolicy.getRetryDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun `attempts below one clamp to the first delay`() {
        assertEquals(1000L, RetryPolicy.getRetryDelayMs(0))
        assertEquals(1000L, RetryPolicy.getRetryDelayMs(-5))
    }

    @Test
    fun `transient HTTP statuses and transport errors are retryable`() {
        assertTrue(RetryPolicy.isRetryableNotifyError("HTTP 429: rate limited"))
        assertTrue(RetryPolicy.isRetryableNotifyError("HTTP 503: handler unavailable"))
        assertTrue(RetryPolicy.isRetryableNotifyError(ClessiraTimeoutException("read")))
        assertTrue(RetryPolicy.isRetryableNotifyError(ClessiraConnectException("connection refused: Connection refused")))
        assertTrue(RetryPolicy.isRetryableNotifyError(IOException("Connection reset by peer")))
    }

    @Test
    fun `client errors and unknown failures are not retryable`() {
        assertFalse(RetryPolicy.isRetryableNotifyError("HTTP 400: invalid body"))
        assertFalse(RetryPolicy.isRetryableNotifyError("HTTP 401: bad signature"))
        assertFalse(RetryPolicy.isRetryableNotifyError("HTTP 423: license locked"))
        assertFalse(RetryPolicy.isRetryableNotifyError(IOException("cannot reach socket /x: No such file or directory")))
        assertFalse(RetryPolicy.isRetryableNotifyError(IllegalStateException("boom")))
    }
}
