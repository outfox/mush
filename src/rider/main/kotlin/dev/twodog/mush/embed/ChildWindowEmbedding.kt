package dev.twodog.mush.embed

import com.intellij.openapi.diagnostic.logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Component
import java.awt.Rectangle
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Hosts a foreign process's top-level window as a `WS_CHILD` of an AWT heavyweight component (Windows only).
 * A child window dies with its parent HWND, and AWT recreates peers whenever a component is removed from a
 * displayable hierarchy rather than moved with `setComponentZOrder`, so callers must [park] before `removeNotify`
 * and [embed] again after `addNotify`.
 */
class ChildWindowEmbedding(private val detachInput: Boolean) {
    private var lastSize: Rectangle? = null
    private var shown = false

    fun embed(child: Long, parent: Component) {
        val parentHwnd = Native.getComponentID(parent)
        val style = user32.GetWindowLongPtrW(child, GWL_STYLE)
        if (style and WS_CHILD == 0L) {
            val childStyle = (style and (WS_POPUP or WS_CAPTION or WS_THICKFRAME or WS_SYSMENU or WS_MINIMIZEBOX or WS_MAXIMIZEBOX).inv()) or
                WS_CHILD or WS_CLIPSIBLINGS or WS_CLIPCHILDREN
            user32.SetWindowLongPtrW(child, GWL_STYLE, childStyle)
        }
        user32.SetParent(child, parentHwnd)
        // SetParent across threads joins the input queues; detaching keeps a stalled host from stalling the IDE.
        if (detachInput)
            user32.AttachThreadInput(user32.GetWindowThreadProcessId(child, null), user32.GetWindowThreadProcessId(parentHwnd, null), false)
        lastSize = null
        shown = false
    }

    /**
     * Moves the child under a hidden window that lives as long as the IDE. It stays a `WS_CHILD` and is never
     * top-level, so it cannot flash at the screen origin while its old parent is torn down.
     */
    fun park(child: Long) {
        user32.SetParent(child, ParkingWindow.hwnd)
        user32.ShowWindowAsync(child, SW_HIDE)
        lastSize = null
        shown = false
    }

    /**
     * Keeps the local Canvas peer alive until its child process exits. Call on the EDT before requesting shutdown;
     * synchronously parking the foreign child first would wait for a debugger-suspended process in SetParent.
     */
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
                    released.complete(Unit)
                } catch (e: Exception) {
                    released.completeExceptionally(e)
                }
            }
        }
        return released
    }

    /**
     * Fills the parent's client area. Size and visibility change in one async call, so the child never shows at a
     * stale size and a host stuck on a breakpoint cannot block the caller.
     */
    fun sync(child: Long, parent: Component) {
        val rect = clientRect(Native.getComponentID(parent))
        val visible = parent.isShowing && rect.width > 0 && rect.height > 0
        if (visible && (!shown || rect != lastSize)) {
            LOG.info("sync: ${if (shown) "resize" else "show"} $rect")
            val show = if (shown) 0 else SWP_SHOWWINDOW or SWP_FRAMECHANGED
            user32.SetWindowPos(child, 0, 0, 0, rect.width, rect.height, SWP_NOZORDER or SWP_NOACTIVATE or SWP_ASYNCWINDOWPOS or show)
            lastSize = rect
            shown = true
        } else if (!visible && shown) {
            LOG.info("sync: hide")
            user32.ShowWindowAsync(child, SW_HIDE)
            shown = false
        }
    }

    /**
     * Resizes the child to the parent's client area before the parent is shown, so no stale strip is exposed.
     * Waits at most [timeoutMs] for the host to apply it; a host stuck on a breakpoint must not freeze the IDE.
     */
    fun resizeBeforeShow(child: Long, parent: Component, timeoutMs: Long) {
        val rect = clientRect(Native.getComponentID(parent))
        if (rect.isEmpty)
            return
        val resize = CompletableFuture.runAsync {
            user32.SetWindowPos(child, 0, 0, 0, rect.width, rect.height, SWP_NOZORDER or SWP_NOACTIVATE)
        }
        try {
            resize.get(timeoutMs, TimeUnit.MILLISECONDS)
            lastSize = rect
            LOG.info("resizeBeforeShow: $rect")
        } catch (_: TimeoutException) {
            lastSize = null
            LOG.info("resizeBeforeShow: timed out at $rect")
        }
    }

    /** The child's actual native state next to what this class believes, for diagnostics. */
    fun describe(child: Long, parent: Component): String {
        val r = IntArray(4)
        user32.GetWindowRect(child, r)
        val parentHwnd = if (parent.isDisplayable) Native.getComponentID(parent) else 0L
        return "visible=${user32.IsWindowVisible(child)} inParent=${user32.GetParent(child) == parentHwnd} " +
            "size=${r[2] - r[0]}x${r[3] - r[1]} shown=$shown lastSize=${lastSize?.let { "${it.width}x${it.height}" }}"
    }

    /** True when the component's window region is empty, i.e. AWT's lightweight mixing has cut all of it away. */
    fun isCutAway(component: Component): Boolean {
        val region = gdi32.CreateRectRgn(0, 0, 0, 0)
        try {
            return user32.GetWindowRgn(Native.getComponentID(component), region) == NULLREGION
        } finally {
            gdi32.DeleteObject(region)
        }
    }

    fun clientSize(parent: Component): Rectangle = clientRect(Native.getComponentID(parent))

    private fun clientRect(hwnd: Long): Rectangle {
        val r = IntArray(4)
        user32.GetClientRect(hwnd, r)
        return Rectangle(r[0], r[1], r[2] - r[0], r[3] - r[1])
    }

    /** A never-shown AWT window; its HWND belongs to the AWT toolkit thread, which pumps messages. */
    private object ParkingWindow {
        val hwnd: Long by lazy {
            val window = JWindow()
            window.addNotify()
            Native.getComponentID(window)
        }
    }

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun SetParent(child: Long, parent: Long): Long
        fun GetWindowLongPtrW(hwnd: Long, index: Int): Long
        fun SetWindowLongPtrW(hwnd: Long, index: Int, value: Long): Long
        fun SetWindowPos(hwnd: Long, after: Long, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun ShowWindowAsync(hwnd: Long, command: Int): Boolean
        fun GetClientRect(hwnd: Long, rect: IntArray): Boolean
        fun GetWindowRect(hwnd: Long, rect: IntArray): Boolean
        fun GetParent(hwnd: Long): Long
        fun IsWindowVisible(hwnd: Long): Boolean
        fun GetWindowThreadProcessId(hwnd: Long, processId: Pointer?): Int
        fun AttachThreadInput(from: Int, to: Int, attach: Boolean): Boolean
        fun GetWindowRgn(hwnd: Long, region: Long): Int
    }

    @Suppress("FunctionName")
    private interface Gdi32 : Library {
        fun CreateRectRgn(left: Int, top: Int, right: Int, bottom: Int): Long
        fun DeleteObject(handle: Long): Boolean
    }

    private companion object {
        val user32: User32 by lazy { Native.load("user32", User32::class.java) }
        val gdi32: Gdi32 by lazy { Native.load("gdi32", Gdi32::class.java) }
        val LOG = logger<ChildWindowEmbedding>()

        const val GWL_STYLE = -16
        const val WS_CHILD = 0x40000000L
        const val WS_POPUP = 0x80000000L
        const val WS_CAPTION = 0x00C00000L
        const val WS_THICKFRAME = 0x00040000L
        const val WS_SYSMENU = 0x00080000L
        const val WS_MINIMIZEBOX = 0x00020000L
        const val WS_MAXIMIZEBOX = 0x00010000L
        const val WS_CLIPSIBLINGS = 0x04000000L
        const val WS_CLIPCHILDREN = 0x02000000L
        const val SWP_NOZORDER = 0x4
        const val SWP_NOACTIVATE = 0x10
        const val SWP_FRAMECHANGED = 0x20
        const val SWP_SHOWWINDOW = 0x40
        const val SWP_ASYNCWINDOWPOS = 0x4000
        const val SW_HIDE = 0
        const val NULLREGION = 1
    }
}
