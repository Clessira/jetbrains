package com.clessira.jetbrains.app

import com.clessira.jetbrains.core.Capability
import com.clessira.jetbrains.core.CurrentActivityResult
import com.clessira.jetbrains.core.ClessiraApi
import com.clessira.jetbrains.settings.ClessiraSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application-wide coordinator: connection state machine, `/current` polling
 * and the elapsed-time render tick — the JetBrains counterpart of the state
 * kept in `extension.ts ClessiraExtension`.
 */
@Service(Service.Level.APP)
class ClessiraAppService(val scope: CoroutineScope) {

    val api = ClessiraApi()

    @Volatile
    var status: ConnectionStatus = if (SystemInfo.isMac) ConnectionStatus.CHECKING else ConnectionStatus.UNSUPPORTED_OS
        private set

    @Volatile
    var currentActivity: CurrentActivityResult? = null
        private set

    private val started = AtomicBoolean(false)
    private var pollJob: Job? = null

    /** Idempotent bootstrap, called from the first opened project. */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        if (!SystemInfo.isMac) {
            log.info("Clessira plugin is inactive: requires macOS")
            return
        }
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(ClessiraSettings.TOPIC, ClessiraSettings.Listener { restartPolling() })
        scope.launch(Dispatchers.IO) { checkConnection(notify = false) }
        startPolling()
        scope.launch {
            while (isActive) {
                delay(RENDER_TICK_MS)
                publishStateChanged()
            }
        }
    }

    fun reconnect() {
        setStatus(ConnectionStatus.CHECKING)
        scope.launch(Dispatchers.IO) { checkConnection(notify = true) }
    }

    /** Port of `extension.ts runTestConnection` (fire-and-forget). */
    fun testConnection() {
        scope.launch(Dispatchers.IO) {
            try {
                val httpStatus = api.healthcheck()
                setStatus(ConnectionStatus.CONNECTED)
                ClessiraNotifier.info("Clessira reachable (HTTP $httpStatus).")
            } catch (e: Exception) {
                setStatus(ConnectionStatus.DISCONNECTED)
                ClessiraNotifier.error("Could not reach Clessira: ${e.message ?: e}")
            }
        }
    }

    fun checkConnectionAsync(notify: Boolean) {
        scope.launch(Dispatchers.IO) { checkConnection(notify) }
    }

    /** Port of `extension.ts checkConnection`. Blocking; call on IO. */
    fun checkConnection(notify: Boolean) {
        if (!SystemInfo.isMac) {
            setStatus(ConnectionStatus.UNSUPPORTED_OS)
            if (notify) ClessiraNotifier.warning("Clessira requires macOS.")
            return
        }
        try {
            Capability.read()
        } catch (e: Exception) {
            setStatus(ConnectionStatus.NEEDS_APP)
            if (notify) {
                ClessiraNotifier.warnAppNotReachable()
            } else {
                log.info("Capability file unavailable: ${e.message}")
            }
            return
        }
        try {
            api.healthcheck()
            setStatus(ConnectionStatus.CONNECTED)
            if (notify) ClessiraNotifier.info("Clessira: Connected.")
        } catch (e: Exception) {
            setStatus(ConnectionStatus.DISCONNECTED)
            if (notify) {
                ClessiraNotifier.errorConnectionFailed(e.message ?: e.toString())
            } else {
                log.info("Connection check failed: ${e.message}")
            }
        }
    }

    /** Port of `extension.ts setStatus`, incl. the connection-lost balloon. */
    fun setStatus(newStatus: ConnectionStatus) {
        val previous = status
        status = newStatus
        if (previous != newStatus) {
            log.info("Connection status: $previous -> $newStatus")
        }
        if (previous == ConnectionStatus.CONNECTED &&
            (newStatus == ConnectionStatus.DISCONNECTED || newStatus == ConnectionStatus.NEEDS_APP)
        ) {
            ClessiraNotifier.warnConnectionLost()
        }
        publishStateChanged()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                refreshCurrentActivity()
                delay(ClessiraSettings.instance.currentPollSecondsClamped * 1000L)
            }
        }
    }

    private fun restartPolling() {
        if (!started.get() || !SystemInfo.isMac) {
            publishStateChanged()
            return
        }
        startPolling()
        publishStateChanged()
    }

    /** Port of `extension.ts refreshCurrentActivity`. Blocking; runs on IO. */
    private fun refreshCurrentActivity() {
        if (status == ConnectionStatus.NEEDS_APP || status == ConnectionStatus.DISCONNECTED) {
            currentActivity = null
            publishStateChanged()
            return
        }
        currentActivity = try {
            api.current()
        } catch (e: Exception) {
            log.info("Current activity poll failed: ${e.message}")
            null
        }
        publishStateChanged()
    }

    private fun publishStateChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(STATE_TOPIC).stateChanged()
    }

    fun interface StateListener {
        fun stateChanged()
    }

    companion object {
        @JvmField
        val STATE_TOPIC: Topic<StateListener> = Topic.create("Clessira state changed", StateListener::class.java)

        private const val RENDER_TICK_MS = 30_000L

        val log: Logger = Logger.getInstance(ClessiraAppService::class.java)

        val instance: ClessiraAppService
            get() = service()
    }
}
