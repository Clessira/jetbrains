package com.clessira.jetbrains.settings

import com.clessira.jetbrains.core.IgnorePattern
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel

class ClessiraConfigurable : BoundConfigurable("Clessira") {

    private val state get() = ClessiraSettings.instance.state

    override fun createPanel(): DialogPanel = panel {
        group("Branch Notifications") {
            row {
                checkBox("Notify Clessira when the Git branch changes")
                    .bindSelected(state::enabled)
            }
            row("Debounce (ms):") {
                intTextField(range = 0..10_000)
                    .bindIntText(state::debounceMs)
                    .columns(8)
                    .comment("Quiet window after a branch change before Clessira is notified")
            }
            row("Ignore branches matching:") {
                textField()
                    .bindText(state::watchIgnorePattern)
                    .columns(28)
                    .comment("Regular expression, e.g. ^(main|master|develop)\$")
                    .validationOnInput {
                        if (IgnorePattern.evaluate(it.text, "validation").invalidPattern) {
                            warning("Not a valid regular expression — it will be ignored")
                        } else {
                            null
                        }
                    }
            }
        }
        group("Status Bar") {
            row {
                checkBox("Show current activity")
                    .bindSelected(state::showCurrentActivity)
            }
            row {
                checkBox("Show elapsed time")
                    .bindSelected(state::showElapsedTime)
            }
            row("Poll interval (seconds):") {
                intTextField(range = 2..120)
                    .bindIntText(state::currentPollSeconds)
                    .columns(8)
            }
        }
    }

    override fun apply() {
        super.apply()
        ClessiraSettings.instance.notifyChanged()
    }
}
