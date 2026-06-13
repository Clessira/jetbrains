package com.clessira.jetbrains.ui

import com.clessira.jetbrains.app.ClessiraAppService
import com.clessira.jetbrains.app.ClessiraNotifier
import com.clessira.jetbrains.core.ActivitySearchItem
import com.clessira.jetbrains.core.ActivityStartBody
import com.clessira.jetbrains.core.Capability
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.Collator
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Search-as-you-type activity picker with a create-on-demand row — the
 * JetBrains counterpart of the VS Code QuickPick in
 * `extension.ts runStartActivityCommand` (140ms debounce, stale-response
 * guard, accent-insensitive exact-match check).
 */
object ActivityPickerPopup {

    private const val SEARCH_DEBOUNCE_MS = 140L
    private const val SEARCH_LIMIT = 20

    sealed class PickerItem {
        data class Existing(val item: ActivitySearchItem) : PickerItem()
        data class Create(val name: String) : PickerItem()
    }

    fun show(project: Project?) {
        val app = ClessiraAppService.instance
        app.scope.launch(Dispatchers.IO) {
            val reachable = try {
                Capability.read()
                true
            } catch (_: Exception) {
                false
            }
            withContext(Dispatchers.EDT) {
                if (reachable) {
                    buildAndShow(project)
                } else {
                    ClessiraNotifier.warnAppNotReachable()
                }
            }
        }
    }

    private fun buildAndShow(project: Project?) {
        val app = ClessiraAppService.instance
        val model = CollectionListModel<PickerItem>()
        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            setEmptyText("No matching activities")
            cellRenderer = SimpleListCellRenderer.create { label, value: PickerItem, _ ->
                when (value) {
                    is PickerItem.Existing -> {
                        val group = value.item.groupName?.let { "  —  Group: $it" } ?: ""
                        label.text = value.item.name + group
                    }
                    is PickerItem.Create -> label.text = "Create new activity: ${value.name}"
                }
            }
        }
        val search = SearchTextField(false)
        val panel = JPanel(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            preferredSize = JBUI.size(440, 320)
        }

        val popup: JBPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, search.textEditor)
            .setTitle("Clessira: Start Activity")
            .setFocusable(true)
            .setRequestFocus(true)
            .setResizable(true)
            .setMovable(true)
            .setCancelOnClickOutside(true)
            .createPopup()

        var requestVersion = 0
        var debounceJob: Job? = null
        var busy = false
        val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }

        fun updateItems(query: String) {
            val version = ++requestVersion
            app.scope.launch(Dispatchers.IO) {
                val result = try {
                    Result.success(app.api.searchActivities(query, SEARCH_LIMIT))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                withContext(Dispatchers.EDT) {
                    if (version != requestVersion || popup.isDisposed) return@withContext
                    result.fold(
                        onSuccess = { items ->
                            val options = mutableListOf<PickerItem>()
                            items.mapTo(options) { PickerItem.Existing(it) }
                            val trimmed = query.trim()
                            val exactMatch = trimmed.isNotEmpty() &&
                                items.any { collator.compare(it.name, trimmed) == 0 }
                            if (trimmed.isNotEmpty() && !exactMatch) {
                                options.add(0, PickerItem.Create(trimmed))
                            }
                            model.replaceAll(options)
                            if (options.isNotEmpty()) list.selectedIndex = 0
                        },
                        onFailure = { e ->
                            model.replaceAll(emptyList())
                            val message = "Clessira search failed: ${e.message ?: e}"
                            ClessiraAppService.log.info(message)
                            ClessiraNotifier.error(message)
                        },
                    )
                }
            }
        }

        fun accept() {
            if (busy) return
            val selected = list.selectedValue ?: return
            busy = true
            search.textEditor.isEnabled = false
            list.isEnabled = false
            val body = when (selected) {
                is PickerItem.Existing -> ActivityStartBody(activityID = selected.item.id)
                is PickerItem.Create -> ActivityStartBody(name = selected.name, createIfMissing = true)
            }
            app.scope.launch(Dispatchers.IO) {
                val result = try {
                    Result.success(app.api.startActivity(body))
                } catch (e: Exception) {
                    Result.failure(e)
                }
                withContext(Dispatchers.EDT) {
                    result.fold(
                        onSuccess = { started ->
                            ClessiraNotifier.info(
                                if (started.created) {
                                    "Clessira: Activity created and started: ${started.activityName}"
                                } else {
                                    "Clessira: Activity started: ${started.activityName}"
                                },
                            )
                            popup.closeOk(null)
                        },
                        onFailure = { e ->
                            val message = "Clessira start failed: ${e.message ?: e}"
                            ClessiraAppService.log.info(message)
                            ClessiraNotifier.error(message)
                            busy = false
                            search.textEditor.isEnabled = true
                            list.isEnabled = true
                        },
                    )
                }
            }
        }

        search.addDocumentListener(object : DocumentListener {
            private fun changed() {
                debounceJob?.cancel()
                debounceJob = app.scope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    updateItems(search.text)
                }
            }

            override fun insertUpdate(e: DocumentEvent) = changed()
            override fun removeUpdate(e: DocumentEvent) = changed()
            override fun changedUpdate(e: DocumentEvent) = changed()
        })

        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DOWN -> {
                        if (list.selectedIndex < model.size - 1) list.selectedIndex++
                        list.ensureIndexIsVisible(list.selectedIndex)
                        e.consume()
                    }
                    KeyEvent.VK_UP -> {
                        if (list.selectedIndex > 0) list.selectedIndex--
                        list.ensureIndexIsVisible(list.selectedIndex)
                        e.consume()
                    }
                    KeyEvent.VK_ENTER -> {
                        accept()
                        e.consume()
                    }
                }
            }
        })

        list.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    accept()
                    e.consume()
                }
            }
        })

        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) accept()
            }
        })

        if (project != null) {
            popup.showCenteredInCurrentWindow(project)
        } else {
            popup.showInFocusCenter()
        }
        updateItems("")
    }
}
