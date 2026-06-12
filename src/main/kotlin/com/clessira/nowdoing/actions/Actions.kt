package com.clessira.nowdoing.actions

import com.clessira.nowdoing.app.NowDoingAppService
import com.clessira.nowdoing.app.NowDoingNotifier
import com.clessira.nowdoing.settings.NowDoingConfigurable
import com.clessira.nowdoing.settings.NowDoingSettings
import com.clessira.nowdoing.ui.ActivityPickerPopup
import com.clessira.nowdoing.ui.StatusActionPopup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction

// The eight commands of the VS Code extension, registered in plugin.xml under
// Tools | NowDoing and reachable via Find Action.

class TestConnectionAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        NowDoingAppService.instance.testConnection()
    }
}

class ReconnectAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        NowDoingAppService.instance.reconnect()
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
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, NowDoingConfigurable::class.java)
    }
}

class ShowLogAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        NowDoingNotifier.revealIdeLog()
    }
}

class ToggleCurrentActivityAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        NowDoingSettings.instance.state.showCurrentActivity

    override fun setSelected(e: AnActionEvent, selected: Boolean) {
        NowDoingSettings.instance.state.showCurrentActivity = selected
        NowDoingSettings.instance.notifyChanged()
    }
}

class ToggleElapsedTimeAction : DumbAwareToggleAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun isSelected(e: AnActionEvent): Boolean =
        NowDoingSettings.instance.state.showElapsedTime

    override fun setSelected(e: AnActionEvent, selected: Boolean) {
        NowDoingSettings.instance.state.showElapsedTime = selected
        NowDoingSettings.instance.notifyChanged()
    }
}
