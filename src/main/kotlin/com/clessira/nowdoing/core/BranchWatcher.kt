package com.clessira.nowdoing.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Debounced per-repository branch tracker. Port of `vscode/src/repoWatcher.ts`:
 *
 * - the initial branch is recorded but never announced
 * - a null branch (detached HEAD, mid-rebase) never fires but still updates
 *   the last-known branch
 * - rapid switches are coalesced; a burst A→B→C fires once as (C, previous=B)
 * - the debounce duration is re-read on every change (live config)
 *
 * Git listeners may fire on any thread, so state changes are synchronized.
 */
class BranchWatcher(
    initialBranch: String?,
    private val scope: CoroutineScope,
    private val getDebounceMs: () -> Long,
    private val onChange: suspend (branch: String, previousBranch: String?) -> Unit,
) {
    private val lock = Any()
    private var lastBranch: String? = initialBranch
    private var debounceJob: Job? = null

    fun onStateChange(nextBranch: String?) {
        synchronized(lock) {
            if (nextBranch == lastBranch) return
            val previous = lastBranch
            lastBranch = nextBranch
            if (nextBranch == null) return

            debounceJob?.cancel()
            val debounceMs = getDebounceMs()
            debounceJob = scope.launch {
                delay(debounceMs)
                onChange(nextBranch, previous)
            }
        }
    }

    val currentBranch: String?
        get() = synchronized(lock) { lastBranch }

    fun dispose() {
        synchronized(lock) {
            debounceJob?.cancel()
            debounceJob = null
        }
    }
}
