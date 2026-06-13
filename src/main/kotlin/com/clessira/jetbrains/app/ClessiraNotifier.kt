package com.clessira.jetbrains.app

import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.PathManager
import java.nio.file.Path

/** Balloon notifications; strings mirror the VS Code extension's messages. */
object ClessiraNotifier {

    private fun group() = NotificationGroupManager.getInstance().getNotificationGroup("Clessira")

    fun info(message: String) {
        group().createNotification(message, NotificationType.INFORMATION).notify(null)
    }

    fun warning(message: String) {
        group().createNotification(message, NotificationType.WARNING).notify(null)
    }

    fun error(message: String) {
        group().createNotification(message, NotificationType.ERROR).notify(null)
    }

    fun warnAppNotReachable() {
        group().createNotification(
            "Clessira: App not reachable. Open the Clessira app and enable the editor integration.",
            NotificationType.WARNING,
        ).addAction(retryAction()).notify(null)
    }

    fun errorConnectionFailed(detail: String) {
        group().createNotification("Clessira: Connection failed: $detail", NotificationType.ERROR)
            .addAction(retryAction())
            .addAction(showLogAction())
            .notify(null)
    }

    fun warnConnectionLost() {
        group().createNotification(
            "Clessira: Connection lost. Branch changes are not being sent.",
            NotificationType.WARNING,
        ).addAction(retryAction()).addAction(showLogAction()).notify(null)
    }

    private fun retryAction() = NotificationAction.createSimpleExpiring("Retry") {
        ClessiraAppService.instance.reconnect()
    }

    private fun showLogAction() = NotificationAction.createSimple("Show Log") {
        revealIdeLog()
    }

    fun revealIdeLog() {
        RevealFileAction.openFile(Path.of(PathManager.getLogPath(), "idea.log"))
    }
}
