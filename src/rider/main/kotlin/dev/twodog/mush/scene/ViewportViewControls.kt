package dev.twodog.mush.scene

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

/** Scratch-local state shared by the editor's native toolbar actions. Access on the EDT. */
internal class ViewportViewControls(private val onChange: (ViewState) -> Unit) {
    private var initialized = false
    private var startupShow2D: Boolean? = null
    private var startupShow3D: Boolean? = null
    private var startupMode: ViewMode? = null
    val hasViewState: Boolean get() = initialized
    var state = ViewState(true, true, ViewMode.TWO_D)
        private set
    // These are editor preferences, so accept them while the engine and scene are still starting.
    var isAvailable = true
        private set

    fun setVisible(mode: ViewMode, visible: Boolean) {
        if (!isAvailable) return
        if (!initialized) {
            if (mode == ViewMode.TWO_D) startupShow2D = visible else startupShow3D = visible
        }
        change(if (mode == ViewMode.TWO_D) state.copy(show2D = visible) else state.copy(show3D = visible))
    }

    fun selectMode(mode: ViewMode) {
        if (!isAvailable) return
        // Clicking the provisional selection is still an explicit choice, even if state does not change.
        if (!initialized) startupMode = mode
        change(state.copy(mode = mode))
    }

    private fun change(next: ViewState) {
        if (next == state) return
        state = next
        if (initialized) onChange(next)
    }

    // Keep automatic scene detection for untouched settings, but apply choices made during startup once ready.
    // Later replies can describe an older queued change and must not overwrite newer user choices.
    fun initialize(hostState: ViewState) {
        if (initialized) return
        initialized = true
        state = ViewState(startupShow2D ?: hostState.show2D, startupShow3D ?: hostState.show3D,
            startupMode ?: hostState.mode)
        if (isAvailable && state != hostState) onChange(state)
    }

    fun setAvailable(available: Boolean) {
        isAvailable = available
    }
}
