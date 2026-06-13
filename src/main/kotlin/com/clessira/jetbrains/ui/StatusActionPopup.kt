package com.clessira.jetbrains.ui

import com.clessira.jetbrains.app.ClessiraAppService
import com.clessira.jetbrains.app.ClessiraNotifier
import com.clessira.jetbrains.settings.ClessiraConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep

/** The VS Code status-bar quick menu: track / test / reconnect / settings / log. */
object StatusActionPopup {

    private class Item(val label: String, val run: () -> Unit)

    fun createPopup(project: Project?): ListPopup {
        val items = listOf(
            Item("Track New Activity") { ActivityPickerPopup.show(project) },
            Item("Test Connection") { ClessiraAppService.instance.testConnection() },
            Item("Reconnect") { ClessiraAppService.instance.reconnect() },
            Item("Open Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, ClessiraConfigurable::class.java)
            },
            Item("Show Log") { ClessiraNotifier.revealIdeLog() },
        )
        val step = object : BaseListPopupStep<Item>("Clessira", items) {
            override fun getTextFor(value: Item): String = value.label
            override fun onChosen(selectedValue: Item, finalChoice: Boolean): PopupStep<*>? {
                return doFinalStep { selectedValue.run() }
            }
        }
        return JBPopupFactory.getInstance().createListPopup(step)
    }
}
