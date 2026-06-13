package com.clessira.jetbrains.git

import com.clessira.jetbrains.app.ConnectionStatus
import com.clessira.jetbrains.app.ClessiraAppService
import com.clessira.jetbrains.core.BranchChangeBody
import com.clessira.jetbrains.core.BranchNotifyQueue
import com.clessira.jetbrains.core.BranchWatcher
import com.clessira.jetbrains.core.IgnorePattern
import com.clessira.jetbrains.settings.ClessiraSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import git4idea.repo.GitRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches the project's Git repositories and notifies the Mac app about
 * branch switches — the counterpart of `extension.ts attachRepo` /
 * `notifyBranchChange` with the same debounce, ignore-pattern, enabled-gate
 * and retry semantics.
 */
@Service(Service.Level.PROJECT)
class RepoWatchService(private val project: Project, private val scope: CoroutineScope) : Disposable {

    private val watchers = ConcurrentHashMap<String, BranchWatcher>()

    @Volatile
    private var loggedInvalidPattern: String? = null

    private val queue = BranchNotifyQueue(
        scope = scope,
        post = { body ->
            withContext(Dispatchers.IO) { ClessiraAppService.instance.api.postBranchChange(body) }
        },
        listener = object : BranchNotifyQueue.Listener {
            override fun onDelivered(body: BranchChangeBody, httpStatus: Int) {
                log.info("Notified Clessira: ${body.repo} -> ${body.branch} (HTTP $httpStatus)")
                ClessiraAppService.instance.setStatus(ConnectionStatus.CONNECTED)
            }

            override fun onFailed(body: BranchChangeBody, error: Throwable) {
                log.info("Failed to notify Clessira for ${body.repo} -> ${body.branch}: ${error.message}")
                ClessiraAppService.instance.setStatus(ConnectionStatus.DISCONNECTED)
            }

            override fun onRetryScheduled(body: BranchChangeBody, attempt: Int, delayMs: Long, error: Throwable) {
                log.info("Retry $attempt/${com.clessira.jetbrains.core.RetryPolicy.MAX_RETRY_ATTEMPTS} scheduled for ${body.repo} -> ${body.branch} in ${delayMs}ms: ${error.message}")
            }

            override fun onDropped(body: BranchChangeBody, error: Throwable) {
                log.info("Dropping queued notification for ${body.repo} -> ${body.branch}: ${error.message}")
            }
        },
    )

    /**
     * Single entry point for repository events. The first event for a repo
     * seeds its current branch without announcing it (like VS Code's
     * `attachRepo`); later events go through the debounced watcher.
     */
    fun repositoryChanged(repository: GitRepository) {
        if (!SystemInfo.isMac) return
        val key = repository.root.path
        val watcher = watchers.computeIfAbsent(key) {
            log.info("Watching repository $key")
            BranchWatcher(
                initialBranch = repository.currentBranchName,
                scope = scope,
                getDebounceMs = { ClessiraSettings.instance.debounceMsClamped },
                onChange = { branch, previousBranch ->
                    notifyBranchChange(repository, branch, previousBranch)
                },
            )
        }
        watcher.onStateChange(repository.currentBranchName)
    }

    /** Drops watchers whose repository root no longer exists in [roots]. */
    fun retainRepositories(roots: Collection<String>) {
        val keep = roots.toSet()
        val iterator = watchers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in keep) {
                entry.value.dispose()
                iterator.remove()
                log.info("Stopped watching repository ${entry.key}")
            }
        }
    }

    private suspend fun notifyBranchChange(repository: GitRepository, branch: String, previousBranch: String?) {
        if (shouldIgnoreBranch(branch)) {
            log.info("Ignoring branch change due to watchIgnorePattern: $branch")
            return
        }
        val settings = ClessiraSettings.instance.state
        if (!settings.enabled) return

        val body = BranchChangeBody(
            repo = repository.root.name,
            repoPath = repository.root.path,
            branch = branch,
            previousBranch = previousBranch,
        )
        queue.deliver(body)
    }

    /** Port of `extension.ts shouldIgnoreBranch` incl. the log-once guard. */
    private fun shouldIgnoreBranch(branch: String): Boolean {
        val pattern = ClessiraSettings.instance.state.watchIgnorePattern.trim()
        val result = IgnorePattern.evaluate(pattern, branch)
        if (result.invalidPattern && loggedInvalidPattern != pattern) {
            loggedInvalidPattern = pattern
            log.warn("Invalid watchIgnorePattern regex: $pattern. Ignoring this setting.")
        }
        if (!result.invalidPattern) {
            loggedInvalidPattern = null
        }
        return result.isIgnored
    }

    override fun dispose() {
        watchers.values.forEach { it.dispose() }
        watchers.clear()
        queue.dispose()
    }

    companion object {
        private val log = ClessiraAppService.log
    }
}
