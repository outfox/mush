package dev.twodog.mush.scene

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** Bound to this editor's state, including when it is in a split or detached window. */
internal fun viewportViewActions(controls: ViewportViewControls): Array<AnAction> = arrayOf(
    VisibilityAction(controls, ViewMode.TWO_D, "2D / UI", "show2d"),
    VisibilityAction(controls, ViewMode.THREE_D, "3D", "show3d"),
    Separator.create(),
    NavigationAction(controls, ViewMode.TWO_D, "2D / UI", "move2d", "Middle-drag to pan; wheel to zoom"),
    NavigationAction(controls, ViewMode.THREE_D, "3D", "move3d", "Middle-drag to orbit; Shift+middle-drag to pan; wheel to zoom")
)

private abstract class ViewAction(
    protected val controls: ViewportViewControls,
    text: String,
    description: String,
    icon: Icon
) : ToggleAction(text, description, icon), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        super.update(e)
        e.presentation.isEnabled = controls.isAvailable
    }
}

private class VisibilityAction(controls: ViewportViewControls, private val mode: ViewMode, label: String, icon: String) :
    ViewAction(controls, "Show $label", "Show or hide $label", viewIcon(icon)) {
    override fun isSelected(e: AnActionEvent) =
        if (mode == ViewMode.TWO_D) controls.state.show2D else controls.state.show3D

    override fun setSelected(e: AnActionEvent, state: Boolean) = controls.setVisible(mode, state)
}

private class NavigationAction(controls: ViewportViewControls, private val mode: ViewMode, label: String, icon: String, gesture: String) :
    ViewAction(controls, "Move $label", "Navigate $label: $gesture", viewIcon(icon)) {
    override fun isSelected(e: AnActionEvent) = controls.state.mode == mode
    override fun actionPerformed(e: AnActionEvent) {
        controls.selectMode(mode)
        Toggleable.setSelected(e.presentation, isSelected(e))
    }
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (state) controls.selectMode(mode)
    }
}

private fun viewIcon(name: String): Icon = IconLoader.getIcon("/icons/$name.svg", ViewportViewControls::class.java)
