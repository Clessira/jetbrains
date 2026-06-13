package com.clessira.jetbrains.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Branch-change delivery with the VS Code extension's retry semantics
 * (`extension.ts notifyBranchChange` / `flushRetryQueue`): transient failures
 * are queued in memory, deduplicated by (repoPath, branch), and retried with
 * exponential backoff (1s, 2s, 4s, 8s — max [RetryPolicy.MAX_RETRY_ATTEMPTS]
 * attempts); non-retryable failures are dropped after the listener is told.
 */
class BranchNotifyQueue(
    private val scope: CoroutineScope,
    private val post: suspend (BranchChangeBody) -> Int,
    private val isRetryable: (Throwable) -> Boolean = RetryPolicy::isRetryableNotifyError,
    private val listener: Listener,
) {
    interface Listener {
        /** Delivery succeeded (initial send or retry). */
        fun onDelivered(body: BranchChangeBody, httpStatus: Int)

        /** Any delivery failure — used to flip connection status. */
        fun onFailed(body: BranchChangeBody, error: Throwable)

        /** A retry was queued for [attempt] (1-based) firing in [delayMs]. */
        fun onRetryScheduled(body: BranchChangeBody, attempt: Int, delayMs: Long, error: Throwable)

        /** Gave up on this notification (non-retryable or attempts exhausted). */
        fun onDropped(body: BranchChangeBody, error: Throwable)
    }

    private data class Queued(val body: BranchChangeBody, val attempts: Int)

    private val lock = Any()
    private val queue = mutableListOf<Queued>()
    private var flushJob: Job? = null

    /** Initial delivery; enqueues a retry on transient failure. */
    suspend fun deliver(body: BranchChangeBody) {
        try {
            val status = post(body)
            listener.onDelivered(body, status)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            listener.onFailed(body, e)
            if (isRetryable(e)) enqueue(body, e)
        }
    }

    fun dispose() {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            queue.clear()
        }
    }

    private fun enqueue(body: BranchChangeBody, error: Throwable) {
        synchronized(lock) {
            if (queue.any { it.body.repoPath == body.repoPath && it.body.branch == body.branch }) return
            val queued = Queued(body, attempts = 1)
            queue.add(queued)
            val delayMs = RetryPolicy.getRetryDelayMs(queued.attempts)
            listener.onRetryScheduled(body, queued.attempts, delayMs, error)
            scheduleFlush(delayMs)
        }
    }

    /** Must be called while holding [lock]. */
    private fun scheduleFlush(delayMs: Long) {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(delayMs)
            flush()
        }
    }

    private suspend fun flush() {
        val pending = synchronized(lock) {
            flushJob = null
            val drained = queue.toList()
            queue.clear()
            drained
        }
        if (pending.isEmpty()) return

        var nextDelay: Long? = null
        for (item in pending) {
            try {
                val status = post(item.body)
                listener.onDelivered(item.body, status)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                listener.onFailed(item.body, e)
                val nextAttempt = item.attempts + 1
                if (isRetryable(e) && nextAttempt <= RetryPolicy.MAX_RETRY_ATTEMPTS) {
                    val delayMs = RetryPolicy.getRetryDelayMs(nextAttempt)
                    synchronized(lock) { queue.add(Queued(item.body, nextAttempt)) }
                    nextDelay = if (nextDelay == null) delayMs else minOf(nextDelay, delayMs)
                    listener.onRetryScheduled(item.body, nextAttempt, delayMs, e)
                } else {
                    listener.onDropped(item.body, e)
                }
            }
        }

        synchronized(lock) {
            if (queue.isNotEmpty()) scheduleFlush(nextDelay ?: RetryPolicy.getRetryDelayMs(2))
        }
    }
}
