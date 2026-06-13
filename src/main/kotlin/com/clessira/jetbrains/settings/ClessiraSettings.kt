package com.clessira.jetbrains.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SettingsCategory
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

/**
 * Application-level settings, mirroring the VS Code extension's
 * `clessira.*` configuration keys (same names, defaults and ranges).
 */
@Service(Service.Level.APP)
@State(
    name = "ClessiraSettings",
    storages = [Storage("clessira.xml")],
    category = SettingsCategory.TOOLS,
)
class ClessiraSettings : PersistentStateComponent<ClessiraSettings.State> {

    class State {
        /** Master switch for branch-change notifications. */
        var enabled: Boolean = true
        var debounceMs: Int = 1500
        var watchIgnorePattern: String = ""
        var showCurrentActivity: Boolean = true
        var showElapsedTime: Boolean = true
        var currentPollSeconds: Int = 10
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    val debounceMsClamped: Long get() = state.debounceMs.coerceIn(0, 10_000).toLong()
    val currentPollSecondsClamped: Int get() = state.currentPollSeconds.coerceIn(2, 120)

    fun notifyChanged() {
        ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).settingsChanged()
    }

    fun interface Listener {
        fun settingsChanged()
    }

    companion object {
        @JvmField
        val TOPIC: Topic<Listener> = Topic.create("Clessira settings changed", Listener::class.java)

        val instance: ClessiraSettings
            get() = service()
    }
}
