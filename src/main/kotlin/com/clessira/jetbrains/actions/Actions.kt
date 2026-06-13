package com.clessira.jetbrains.actions

import com.clessira.jetbrains.app.ClessiraAppService
import com.clessira.jetbrains.app.ClessiraNotifier
import com.clessira.jetbrains.settings.ClessiraConfigurable
import com.clessira.jetbrains.settings.ClessiraSettings
import com.clessira.jetbrains.ui.ActivityPickerPopup
import com.clessira.jetbrains.ui.StatusActionPopup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction

// The eight commands of the VS Code extension, registered in plugin.xml under
// Tools | Clessira and reachable via Find Action.

class TestConnectionAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ClessiraAppService.instance.testConnection()
    }
}

class ReconnectAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ClessiraAppService.instance.reconnect()
    }
}

class StartActivityAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ActivityPickerPopup.show(e.project)
    }
}

class ShowStatusMenuAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        StatusActionPopup.createPopup(e.project).showInBestPositionFor(e.dataContext)
    }
}

class OpenSettingsAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ClessiraConfigurable::class.java)
    }
}

class ShowLogAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        ClessiraNotifier.revealIdeLog()
    }
}

class ToggleCurrentActivityAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        ClessiraSettings.instance.state.showCurrentActivity

    override fun setSelected(e: AnActionEvent, selected: Boolean) {
        ClessiraSettings.instance.state.showCurrentActivity = selected
        ClessiraSettings.instance.notifyChanged()
    }
}

class ToggleElapsedTimeAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        ClessiraSettings.instance.state.showElapsedTime

    override fun setSelected(e: AnActionEvent, selected: Boolean) {
        ClessiraSettings.instance.state.showElapsedTime = selected
        ClessiraSettings.instance.notifyChanged()
    }
}
