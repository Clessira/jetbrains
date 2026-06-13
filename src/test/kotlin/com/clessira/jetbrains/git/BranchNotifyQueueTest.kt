package com.clessira.jetbrains.git

import com.clessira.jetbrains.core.BranchChangeBody
import com.clessira.jetbrains.core.BranchNotifyQueue
import com.clessira.jetbrains.core.ClessiraHttpException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/** Mirrors the retry-queue semantics of extension.ts (enqueue/flush/drop). */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BranchNotifyQueueTest {

    private val body = BranchChangeBody(
        repo = "ClessiraMac",
        repoPath = "/tmp/ClessiraMac",
        branch = "feature/x",
        previousBranch = "main",
    )

    private class RecordingListener : BranchNotifyQueue.Listener {
        val delivered = mutableListOf<Int>()
        val failed = mutableListOf<Throwable>()
        val scheduled = mutableListOf<Pair<Int, Long>>()
        val dropped = mutableListOf<Throwable>()

        override fun onDelivered(body: BranchChangeBody, httpStatus: Int) { delivered += httpStatus }
        override fun onFailed(body: BranchChangeBody, error: Throwable) { failed += error }
        override fun onRetryScheduled(body: BranchChangeBody, attempt: Int, delayMs: Long, error: Throwable) {
            scheduled += attempt to delayMs
        }
        override fun onDropped(body: BranchChangeBody, error: Throwable) { dropped += error }
    }

    @Test
    fun `a successful delivery does not enqueue anything`() = runTest {
        val listener = RecordingListener()
        var posts = 0
        val queue = BranchNotifyQueue(this, post = { posts++; 200 }, listener = listener)
        queue.deliver(body)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, posts)
        assertEquals(listOf(200), listener.delivered)
        assertTrue(listener.scheduled.isEmpty())
        queue.dispose()
    }

    @Test
    fun `a transient failure retries after one second and succeeds`() = runTest {
        val listener = RecordingListener()
        var posts = 0
        val queue = BranchNotifyQueue(
            this,
            post = { posts++; if (posts == 1) throw IOException("timeout: read") else 200 },
            listener = listener,
        )
        queue.deliver(body)
        assertEquals(listOf(1 to 1000L), listener.scheduled)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, posts)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, posts)
        assertEquals(listOf(200), listener.delivered)
        assertTrue(listener.dropped.isEmpty())
        queue.dispose()
    }

    @Test
    fun `retries back off exponentially and drop after the fourth attempt`() = runTest {
        val listener = RecordingListener()
        var posts = 0
        val queue = BranchNotifyQueue(
            this,
            post = { posts++; throw IOException("timeout: read") },
            listener = listener,
        )
        queue.deliver(body)
        // initial post + 4 retries at 1s, 2s, 4s, 8s
        advanceTimeBy(1000 + 2000 + 4000 + 8000 + 1000)
        runCurrent()
        assertEquals(5, posts)
        assertEquals(listOf(1 to 1000L, 2 to 2000L, 3 to 4000L, 4 to 8000L), listener.scheduled)
        assertEquals(1, listener.dropped.size)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(5, posts)
        queue.dispose()
    }

    @Test
    fun `a non-retryable failure is not enqueued`() = runTest {
        val listener = RecordingListener()
        var posts = 0
        val queue = BranchNotifyQueue(
            this,
            post = { posts++; throw ClessiraHttpException(400, "HTTP 400: invalid body") },
            listener = listener,
        )
        queue.deliver(body)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, posts)
        assertEquals(1, listener.failed.size)
        assertTrue(listener.scheduled.isEmpty())
        queue.dispose()
    }

    @Test
    fun `pending retries are deduplicated by repoPath and branch`() = runTest {
        val listener = RecordingListener()
        var posts = 0
        val queue = BranchNotifyQueue(
            this,
            post = { posts++; if (posts <= 2) throw IOException("timeout: read") else 200 },
            listener = listener,
        )
        queue.deliver(body)
        queue.deliver(body.copy()) // same repoPath + branch while a retry is pending
        assertEquals(2, posts)
        assertEquals(1, listener.scheduled.size)
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(3, posts) // a single queued retry flushed
        assertEquals(listOf(200), listener.delivered)
        queue.dispose()
    }

    @Test
    fun `a different branch is queued separately`() = runTest {
        val listener = RecordingListener()
        var failures = 0
        val queue = BranchNotifyQueue(
            this,
            post = { if (failures < 2) { failures++; throw IOException("timeout: read") } else 200 },
            listener = listener,
        )
        queue.deliver(body)
        queue.deliver(body.copy(branch = "feature/y"))
        advanceTimeBy(1001)
        runCurrent()
        assertEquals(listOf(200, 200), listener.delivered)
        queue.dispose()
    }
}
