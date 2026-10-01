package com.circleci.idea.toolwindow

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.util.ui.FilterComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.function.Supplier
import javax.swing.JComponent

/**
 * A filter drop-down that always has a value, drawn as the Pull Requests
 * list's other filters are ("Name: value ▾") but with no clear button: there
 * is no "unset" to clear back to.
 *
 * Built on the platform's FilterComponent (as those filters are), which only
 * offers a clear button while it reports a value as selected, so this never does.
 *
 * @param choices What to offer, read each time the popup opens
 * @param choose What picking an item does; by default, sets the state to it
 */
class RequiredFilterChip<T>(
    name: String,
    private val state: MutableStateFlow<T>,
    private val scope: CoroutineScope,
    private val choices: () -> List<T>,
    private val text: ChipText<T>,
    private val choose: (T) -> Unit = { state.value = it },
    // The platform adds the ": " after the name only while a value is
    // selected, which this never reports (to have no clear button), so add it here.
) : FilterComponent(Supplier { "$name: " }) {
    /** The component to add to the filter row. */
    val component: JComponent = initUi()

    init {
        setShowPopupAction { showChoices() }
    }

    override fun getCurrentText(): String = text.chip(state.value)

    override fun getEmptyFilterValue(): String = ""

    // Never "selected", so never a clear button.
    override fun isValueSelected(): Boolean = false

    override fun installChangeListener(onChange: Runnable) {
        scope.launch { state.collect { onChange.run() } }
    }

    override fun createResetAction(): Runnable = Runnable {}

    override fun shouldDrawLabel(): DrawLabelMode = DrawLabelMode.ALWAYS

    private fun showChoices() {
        val step =
            object : BaseListPopupStep<T>(null, choices()) {
                override fun getTextFor(value: T): String = text.popup(value)

                override fun getDefaultOptionIndex(): Int = values.indexOf(state.value).coerceAtLeast(0)

                override fun onChosen(
                    selectedValue: T,
                    finalChoice: Boolean,
                ): PopupStep<*>? = doFinalStep { choose(selectedValue) }
            }
        JBPopupFactory.getInstance().createListPopup(step).showUnderneathOf(component)
    }
}

/** How a [RequiredFilterChip]'s value reads on the chip, and in its popup. */
class ChipText<T>(
    val chip: (T) -> String,
    val popup: (T) -> String = chip,
)
