package dev.twodog.mush.embed

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Component
import java.awt.Rectangle

/**
 * Hosts a foreign process's top-level window as a `WS_CHILD` of an AWT heavyweight component (Windows only).
 * A child window dies with its parent HWND, and AWT recreates peers whenever a component leaves and re-enters a
 * displayable hierarchy, so callers must [park] before `removeNotify` and [embed] again after `addNotify`.
 */
class ChildWindowEmbedding(private val detachInput: Boolean) {
    private var lastSize: Rectangle? = null
    private var shown = false

    fun embed(child: Long, parent: Component) {
        val parentHwnd = Native.getComponentID(parent)
        var style = user32.GetWindowLongPtrW(child, GWL_STYLE)
        style = (style and (WS_POPUP or WS_CAPTION or WS_THICKFRAME or WS_SYSMENU or WS_MINIMIZEBOX or WS_MAXIMIZEBOX).inv()) or
            WS_CHILD or WS_CLIPSIBLINGS or WS_CLIPCHILDREN
        user32.SetWindowLongPtrW(child, GWL_STYLE, style)
        user32.SetParent(child, parentHwnd)
        // SetParent across threads joins the input queues; detaching keeps a stalled host from stalling the IDE.
        if (detachInput)
            user32.AttachThreadInput(user32.GetWindowThreadProcessId(child, null), user32.GetWindowThreadProcessId(parentHwnd, null), false)
        lastSize = null
        shown = false
    }

    fun park(child: Long) {
        user32.ShowWindow(child, SW_HIDE)
        val style = user32.GetWindowLongPtrW(child, GWL_STYLE)
        user32.SetWindowLongPtrW(child, GWL_STYLE, (style and WS_CHILD.inv()) or WS_POPUP)
        user32.SetParent(child, 0)
        lastSize = null
        shown = false
    }

    /** Fills the parent's client area; async so a host stuck on a breakpoint cannot block the caller. */
    fun sync(child: Long, parent: Component) {
        val rect = clientRect(Native.getComponentID(parent))
        val visible = parent.isShowing && rect.width > 0 && rect.height > 0
        if (visible && rect != lastSize) {
            user32.SetWindowPos(child, 0, 0, 0, rect.width, rect.height, SWP_NOZORDER or SWP_NOACTIVATE or SWP_FRAMECHANGED or SWP_ASYNCWINDOWPOS)
            lastSize = rect
        }
        if (visible != shown) {
            user32.ShowWindow(child, if (visible) SW_SHOWNA else SW_HIDE)
            shown = visible
        }
    }

    fun clientSize(parent: Component): Rectangle = clientRect(Native.getComponentID(parent))

    private fun clientRect(hwnd: Long): Rectangle {
        val r = IntArray(4)
        user32.GetClientRect(hwnd, r)
        return Rectangle(r[0], r[1], r[2] - r[0], r[3] - r[1])
    }

    @Suppress("FunctionName")
    private interface User32 : Library {
        fun SetParent(child: Long, parent: Long): Long
        fun GetWindowLongPtrW(hwnd: Long, index: Int): Long
        fun SetWindowLongPtrW(hwnd: Long, index: Int, value: Long): Long
        fun SetWindowPos(hwnd: Long, after: Long, x: Int, y: Int, cx: Int, cy: Int, flags: Int): Boolean
        fun ShowWindow(hwnd: Long, command: Int): Boolean
        fun GetClientRect(hwnd: Long, rect: IntArray): Boolean
        fun GetWindowThreadProcessId(hwnd: Long, processId: Pointer?): Int
        fun AttachThreadInput(from: Int, to: Int, attach: Boolean): Boolean
    }

    private companion object {
        val user32: User32 by lazy { Native.load("user32", User32::class.java) }

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
        const val SWP_ASYNCWINDOWPOS = 0x4000
        const val SW_HIDE = 0
        const val SW_SHOWNA = 8
    }
}
