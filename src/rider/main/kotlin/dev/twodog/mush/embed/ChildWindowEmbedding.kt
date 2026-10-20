package dev.twodog.mush.embed

import java.awt.Component
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.Toolkit
import java.util.concurrent.CompletableFuture
import javax.swing.SwingUtilities

/** Native child-window operations are selected lazily, so Linux never loads Win32 libraries. */
/**
 * [resizeHost] is how a backend that cannot resize the child window itself gets the size to the host process.
 * Win32 and X11 reshape the child directly and never call it.
 */
class ChildWindowEmbedding @JvmOverloads constructor(
    detachInput: Boolean,
    resizeHost: (Dimension) -> Boolean = { false },
) {
    private val platform = WindowPlatform.detect(System.getProperty("os.name"), Toolkit.getDefaultToolkit().javaClass.name)
    private val native = lazy {
        when (platform) {
            WindowPlatform.WINDOWS -> WindowsWindowEmbedding(detachInput)
            WindowPlatform.X11 -> X11WindowEmbedding()
            WindowPlatform.MACOS -> LayerHostEmbedding(resizeHost)
            WindowPlatform.UNSUPPORTED -> error(unsupportedReason!!)
        }
    }

    val unsupportedReason: String?
        get() = when {
            platform == WindowPlatform.UNSUPPORTED ->
                "Viewport embedding requires Windows, macOS, or Linux with Rider using X11/XWayland. " +
                    "Restart Rider with its X11 toolkit."
            platform == WindowPlatform.MACOS && !MushNative.isAvailable -> MushNative.failure
            else -> null
        }

    val hostArguments: List<String> get() = platform.hostArguments

    fun embed(child: Long, parent: Component) = native.value.embed(child, parent)
    fun park(child: Long) = native.value.park(child)
    fun sync(child: Long, parent: Component) = native.value.sync(child, parent)
    fun resizeBeforeShow(child: Long, parent: Component, timeoutMs: Long) = native.value.resizeBeforeShow(child, parent, timeoutMs)
    fun describe(child: Long, parent: Component): String = native.value.describe(child, parent)
    fun isCutAway(component: Component): Boolean = platform == WindowPlatform.WINDOWS && native.value.isCutAway(component)
    fun clientSize(parent: Component): Rectangle = native.value.clientSize(parent)

    /** Retain the Canvas peer until the foreign process exits, including while it is debugger-suspended. */
    fun retireCanvas(canvas: Component, termination: CompletableFuture<*>): CompletableFuture<Unit> {
        val released = CompletableFuture<Unit>()
        val layeredPane = SwingUtilities.getRootPane(canvas)?.layeredPane
        if (canvas.isDisplayable && layeredPane != null) {
            canvas.isVisible = false
            layeredPane.setComponentZOrder(canvas, 0)
        }
        termination.thenRun {
            SwingUtilities.invokeLater {
                try {
                    canvas.parent?.remove(canvas)
                    if (native.isInitialized()) native.value.close()
                    released.complete(Unit)
                } catch (e: Exception) {
                    released.completeExceptionally(e)
                }
            }
        }
        return released
    }
}

internal interface NativeWindowEmbedding : AutoCloseable {
    fun embed(child: Long, parent: Component)
    fun park(child: Long)
    fun sync(child: Long, parent: Component)
    fun resizeBeforeShow(child: Long, parent: Component, timeoutMs: Long)
    fun describe(child: Long, parent: Component): String
    fun isCutAway(component: Component): Boolean = false
    fun clientSize(parent: Component): Rectangle
    override fun close() {}
}

internal enum class WindowPlatform {
    WINDOWS, X11, MACOS, UNSUPPORTED;

    val hostArguments: List<String>
        get() = if (this == X11) listOf("--display-driver", "x11") else emptyList()

    companion object {
        fun detect(osName: String, toolkitName: String): WindowPlatform = when {
            osName.startsWith("Windows") -> WINDOWS
            osName == "Linux" && toolkitName == "sun.awt.X11.XToolkit" -> X11
            osName.startsWith("Mac") && toolkitName == "sun.lwawt.macosx.LWCToolkit" -> MACOS
            else -> UNSUPPORTED
        }
    }
}
