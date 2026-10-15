package dev.twodog.mush.scene

import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.ButtonGroup
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JToggleButton

internal enum class ViewMode(val wire: String) { TWO_D("2d"), THREE_D("3d") }

internal data class ViewState(val show2D: Boolean, val show3D: Boolean, val mode: ViewMode) {
    fun command() = "view ${if (show2D) 1 else 0} ${if (show3D) 1 else 0} ${mode.wire}"

    companion object {
        fun parse(reply: String): ViewState? {
            val parts = reply.split(' ')
            if (parts.size != 4 || parts[0] != "view" || parts[1] !in listOf("0", "1") || parts[2] !in listOf("0", "1"))
                return null
            val mode = ViewMode.entries.find { it.wire == parts[3] } ?: return null
            return ViewState(parts[1] == "1", parts[2] == "1", mode)
        }
    }
}

/** Visibility toggles and navigation radios deliberately do not change each other's selection. */
internal class ViewportViewControls(gap: Int, private val onChange: (ViewState) -> Unit) : JPanel(GridLayout(2, 1, 0, gap)) {
    internal val show2D = JToggleButton("2D (UI)", true)
    internal val show3D = JToggleButton("3D", true)
    internal val move2D = JRadioButton("2D (UI)", true)
    internal val move3D = JRadioButton("3D")
    private val buttons = listOf(show2D, show3D, move2D, move3D)
    private var initialized = false
    val hasViewState: Boolean get() = initialized

    init {
        show2D.toolTipText = "Show 2D and Controls, including CanvasLayer UI"
        show3D.toolTipText = "Show 3D"
        move2D.toolTipText = "Move 2D/UI: middle-drag to pan, wheel to zoom"
        move3D.toolTipText = "Move 3D: middle-drag to orbit, Shift+middle-drag to pan, wheel to zoom"
        ButtonGroup().apply { add(move2D); add(move3D) }
        add(JPanel(FlowLayout(FlowLayout.LEADING, gap, 0)).apply {
            add(JLabel("Show")); add(show2D); add(show3D)
        })
        add(JPanel(FlowLayout(FlowLayout.LEADING, gap, 0)).apply {
            add(JLabel("Move")); add(move2D); add(move3D)
        })
        buttons.forEach { button ->
            button.isEnabled = false
            button.addActionListener {
                onChange(ViewState(show2D.isSelected, show3D.isSelected,
                    if (move3D.isSelected) ViewMode.THREE_D else ViewMode.TWO_D))
            }
        }
    }

    // The first successful scene load chooses the initial navigation mode. Later host replies can describe an
    // older queued change, so they must not overwrite the user's newer selection.
    fun initialize(state: ViewState) {
        if (initialized) return
        initialized = true
        show2D.isSelected = state.show2D
        show3D.isSelected = state.show3D
        move2D.isSelected = state.mode == ViewMode.TWO_D
        move3D.isSelected = state.mode == ViewMode.THREE_D
        setAvailable(true)
    }

    fun setAvailable(available: Boolean) {
        buttons.forEach { it.isEnabled = available && initialized }
    }

    override fun getMinimumSize() = Dimension(0, super.getMinimumSize().height)
}
