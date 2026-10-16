package dev.twodog.mush.embed

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.awt.Component
import java.awt.Rectangle
import javax.swing.JWindow

/**
 * X11 child windows, also used when Rider runs under XWayland. Requests go to the X server, never to the host's
 * event loop. A private XCB connection keeps a dying host's BadWindow errors out of AWT's global Xlib handler.
 * All operations, including close, run on the EDT.
 */
internal class X11WindowEmbedding : NativeWindowEmbedding {
    private val xcb = Native.load("xcb", Xcb::class.java)
    private val connection: Pointer = xcb.xcb_connect(null, null).also {
        if (xcb.xcb_connection_has_error(it) != 0) {
            xcb.xcb_disconnect(it)
            error("Cannot connect to the X11 display. Run Rider and MushHost on the same X11/XWayland display.")
        }
    }
    private var lastSize: Rectangle? = null
    private var shown = false

    override fun embed(child: Long, parent: Component) {
        checked(xcb.xcb_unmap_window_checked(connection, child.toInt()))
        checked(xcb.xcb_reparent_window_checked(connection, child.toInt(), Native.getComponentID(parent).toInt(), 0, 0))
        lastSize = null
        shown = false
    }

    override fun park(child: Long) {
        checked(xcb.xcb_unmap_window_checked(connection, child.toInt()))
        checked(xcb.xcb_reparent_window_checked(connection, child.toInt(), ParkingWindow.id, 0, 0))
        lastSize = null
        shown = false
    }

    override fun sync(child: Long, parent: Component) {
        val size = clientSize(parent)
        val visible = parent.isShowing && !size.isEmpty
        if (visible && (!shown || size != lastSize)) {
            if (!resize(child, size)) return
            if (!shown && !checked(xcb.xcb_map_window_checked(connection, child.toInt()))) return
            lastSize = size
            shown = true
        } else if (!visible && shown) {
            checked(xcb.xcb_unmap_window_checked(connection, child.toInt()))
            shown = false
        }
    }

    override fun resizeBeforeShow(child: Long, parent: Component, timeoutMs: Long) {
        // XCB's acknowledgement is from the X server; a debugger-suspended host need not process anything.
        val size = clientSize(parent)
        if (!size.isEmpty && resize(child, size)) lastSize = size
    }

    private fun resize(child: Long, size: Rectangle): Boolean = checked(xcb.xcb_configure_window_checked(
        connection, child.toInt(), CONFIGURE_X_Y_WIDTH_HEIGHT,
        intArrayOf(0, 0, size.width, size.height),
    ))

    override fun clientSize(parent: Component): Rectangle = geometry(Native.getComponentID(parent))

    override fun describe(child: Long, parent: Component): String {
        val size = geometry(child)
        val attributesError = PointerByReference()
        val attributes = xcb.xcb_get_window_attributes_reply(connection, xcb.xcb_get_window_attributes(connection, child.toInt()), attributesError)
        val visible = try {
            // xcb_get_window_attributes_reply_t.map_state is byte 26; IsViewable is 2.
            attributes?.getByte(26)?.toInt() == 2
        } finally {
            free(attributes)
            free(attributesError.value)
        }
        val error = PointerByReference()
        val reply = xcb.xcb_query_tree_reply(connection, xcb.xcb_query_tree(connection, child.toInt()), error)
        val inParent = try {
            // xcb_query_tree_reply_t: response header (8), root (4), parent (4).
            parent.isDisplayable && reply?.getInt(12) == Native.getComponentID(parent).toInt()
        } finally {
            free(reply)
            free(error.value)
        }
        return "visible=$visible inParent=$inParent size=${size.width}x${size.height} shown=$shown"
    }

    private fun geometry(window: Long): Rectangle {
        val error = PointerByReference()
        val reply = xcb.xcb_get_geometry_reply(connection, xcb.xcb_get_geometry(connection, window.toInt()), error)
        try {
            if (reply == null) return Rectangle()
            val geometry = Geometry(reply)
            // X11 coordinates and dimensions are physical pixels, unlike AWT's scaled component bounds.
            return Rectangle(0, 0, geometry.width.toInt() and 0xffff, geometry.height.toInt() and 0xffff)
        } finally {
            free(reply)
            free(error.value)
        }
    }

    private fun checked(cookie: Cookie): Boolean {
        val error = xcb.xcb_request_check(connection, cookie)
        if (error == null) {
            check(xcb.xcb_connection_has_error(connection) == 0) { "The X11 display connection closed." }
            return true
        }
        try {
            // The host can exit between receiving its window ID and any native request.
            val code = error.getByte(1).toInt() and 0xff
            if (code == BAD_WINDOW || code == BAD_DRAWABLE) return false
            error("X11 window operation failed (error $code).")
        } finally {
            free(error)
        }
    }

    override fun close() = xcb.xcb_disconnect(connection)

    private object ParkingWindow {
        val id: Int by lazy {
            val window = JWindow()
            window.addNotify()
            Native.getComponentID(window).toInt()
        }
    }

    // All XCB request cookies have the same one-uint32_t ABI, passed and returned by value.
    @Structure.FieldOrder("sequence")
    class Cookie : Structure(), Structure.ByValue {
        @JvmField var sequence: Int = 0
    }

    @Structure.FieldOrder("responseType", "depth", "sequence", "length", "root", "x", "y", "width", "height", "borderWidth", "padding")
    class Geometry(pointer: Pointer) : Structure(pointer) {
        @JvmField var responseType: Byte = 0
        @JvmField var depth: Byte = 0
        @JvmField var sequence: Short = 0
        @JvmField var length: Int = 0
        @JvmField var root: Int = 0
        @JvmField var x: Short = 0
        @JvmField var y: Short = 0
        @JvmField var width: Short = 0
        @JvmField var height: Short = 0
        @JvmField var borderWidth: Short = 0
        @JvmField var padding: Short = 0
        init { read() }
    }

    @Suppress("FunctionName")
    private interface Xcb : Library {
        fun xcb_connect(display: String?, screen: Pointer?): Pointer
        fun xcb_connection_has_error(connection: Pointer): Int
        fun xcb_disconnect(connection: Pointer)
        fun xcb_request_check(connection: Pointer, cookie: Cookie): Pointer?
        fun xcb_reparent_window_checked(connection: Pointer, window: Int, parent: Int, x: Short, y: Short): Cookie
        fun xcb_map_window_checked(connection: Pointer, window: Int): Cookie
        fun xcb_unmap_window_checked(connection: Pointer, window: Int): Cookie
        fun xcb_configure_window_checked(connection: Pointer, window: Int, mask: Short, values: IntArray): Cookie
        fun xcb_get_geometry(connection: Pointer, drawable: Int): Cookie
        fun xcb_get_geometry_reply(connection: Pointer, cookie: Cookie, error: PointerByReference): Pointer?
        fun xcb_get_window_attributes(connection: Pointer, window: Int): Cookie
        fun xcb_get_window_attributes_reply(connection: Pointer, cookie: Cookie, error: PointerByReference): Pointer?
        fun xcb_query_tree(connection: Pointer, window: Int): Cookie
        fun xcb_query_tree_reply(connection: Pointer, cookie: Cookie, error: PointerByReference): Pointer?
    }

    private companion object {
        const val CONFIGURE_X_Y_WIDTH_HEIGHT: Short = 0xf
        const val BAD_WINDOW = 3
        const val BAD_DRAWABLE = 9
        fun free(pointer: Pointer?) { if (pointer != null) Native.free(Pointer.nativeValue(pointer)) }
    }
}
