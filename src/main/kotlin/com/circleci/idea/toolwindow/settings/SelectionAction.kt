package com.circleci.idea.toolwindow.settings

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import javax.swing.Icon

/**
 * An action on what's selected in a list or tree, shown and enabled only
 * where [target] finds something in the [selection] to act on, with the
 * shortcut of the IDE's own [shortcutFrom] action. It's off while
 * [searching], so the speed search field gets the keys.
 */
class SelectionAction<S : Any, T : Any>(
    text: String,
    icon: Icon,
    shortcutFrom: String?,
    private val selection: () -> S?,
    private val searching: () -> Boolean,
    private val target: (S?) -> T?,
) : DumbAwareAction(text, null, icon) {
    private var perform: (T) -> Unit = {}

    init {
        shortcutFrom?.let { ActionManager.getInstance().getAction(it) }?.let(::copyShortcutFrom)
    }

    fun performing(perform: (T) -> Unit): SelectionAction<S, T> = apply { this.perform = perform }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val enabled = !searching() && target(selection()) != null
        e.presentation.isEnabled = enabled
        // A toolbar keeps its buttons in place, disabled.
        e.presentation.isVisible = enabled || e.isFromActionToolbar
    }

    override fun actionPerformed(e: AnActionEvent) {
        target(selection())?.let(perform)
    }
}
