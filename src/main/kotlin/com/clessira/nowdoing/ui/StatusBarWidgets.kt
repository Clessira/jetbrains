package com.clessira.nowdoing.ui

import com.clessira.nowdoing.app.ConnectionStatus
import com.clessira.nowdoing.app.NowDoingAppService
import com.clessira.nowdoing.core.ElapsedFormat
import com.clessira.nowdoing.settings.NowDoingSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import java.awt.Component
import java.awt.Point
import java.awt.event.MouseEvent
import java.time.Instant

// Two status bar widgets mirroring the VS Code extension's three status bar
// items: a connection widget (click -> quick action menu) and an activity
// widget combining current activity + elapsed time (click -> picker).

internal abstract class NowDoingWidgetBase(protected val project: Project) :
    StatusBarWidget, StatusBarWidget.TextPresentation {

    protected var statusBar: StatusBar? = null

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(
            NowDoingAppService.STATE_TOPIC,
            NowDoingAppService.StateListener { refresh() },
        )
        connection.subscribe(
            NowDoingSettings.TOPIC,
            NowDoingSettings.Listener { refresh() },
        )
    }

    protected fun refresh() {
        val bar = statusBar ?: return
        ApplicationManager.getApplication().invokeLater { bar.updateWidget(ID()) }
    }

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun dispose() {
        statusBar = null
    }

    /** Shows a popup just above the clicked status bar widget. */
    protected fun showAbove(popup: JBPopup, event: MouseEvent) {
        val component = event.component
        val height = popup.content.preferredSize.height
        popup.show(RelativePoint(component, Point(0, -height)))
    }
}

internal class ConnectionWidget(project: Project) : NowDoingWidgetBase(project) {
    override fun ID(): String = WIDGET_ID

    override fun getText(): String = when (NowDoingAppService.instance.status) {
        ConnectionStatus.CONNECTED -> "✓ NowDoing"
        ConnectionStatus.DISCONNECTED, ConnectionStatus.NEEDS_APP -> "⚠ NowDoing"
        ConnectionStatus.CHECKING -> "… NowDoing"
        ConnectionStatus.UNSUPPORTED_OS -> "NowDoing"
    }

    override fun getTooltipText(): String = when (NowDoingAppService.instance.status) {
        ConnectionStatus.CONNECTED -> "NowDoing: Connected, click for actions"
        ConnectionStatus.DISCONNECTED -> "NowDoing: Disconnected, click for actions"
        ConnectionStatus.CHECKING -> "NowDoing: Checking connection…, click for actions"
        ConnectionStatus.NEEDS_APP -> "NowDoing: App not reachable, click for actions"
        ConnectionStatus.UNSUPPORTED_OS -> "NowDoing requires macOS"
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        showAbove(StatusActionPopup.createPopup(project), event)
    }

    companion object {
        const val WIDGET_ID = "NowDoingConnection"
    }
}

internal class ActivityWidget(project: Project) : NowDoingWidgetBase(project) {
    override fun ID(): String = WIDGET_ID

    override fun getText(): String {
        val current = NowDoingAppService.instance.currentActivity ?: return ""
        val settings = NowDoingSettings.instance.state
        val parts = mutableListOf<String>()
        if (settings.showCurrentActivity) {
            val breakSuffix = if (current.isOnBreak) " (Break)" else ""
            parts += "⏱ ${current.activityName}$breakSuffix"
        }
        if (settings.showElapsedTime) {
            parts += ElapsedFormat.formatElapsed(elapsedMs(current.startedAt))
        }
        return parts.joinToString(" · ")
    }

    override fun getTooltipText(): String? {
        val current = NowDoingAppService.instance.currentActivity ?: return null
        return "NowDoing: ${current.activityName} — click to track new activity"
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        ActivityPickerPopup.show(project)
    }

    private fun elapsedMs(startedAt: String): Long = try {
        (System.currentTimeMillis() - Instant.parse(startedAt).toEpochMilli()).coerceAtLeast(0)
    } catch (_: Exception) {
        0L
    }

    companion object {
        const val WIDGET_ID = "NowDoingActivity"
    }
}

internal class ConnectionWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ConnectionWidget.WIDGET_ID
    override fun getDisplayName(): String = "NowDoing Connection Status"
    override fun isAvailable(project: Project): Boolean = SystemInfo.isMac
    override fun createWidget(project: Project): StatusBarWidget = ConnectionWidget(project)
}

internal class ActivityWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = ActivityWidget.WIDGET_ID
    override fun getDisplayName(): String = "NowDoing Current Activity"
    override fun isAvailable(project: Project): Boolean = SystemInfo.isMac
    override fun createWidget(project: Project): StatusBarWidget = ActivityWidget(project)
}
