package dev.twodog.mush.embed.gpu

import java.awt.Component
import java.awt.Toolkit
import java.awt.Window

/**
 * Spike-only adapter for Rider 2026.2's JBR 25.0.4/b508.27 private Wayland APIs. AWT owns the display/parent surface;
 * this adapter owns only the child. The scene editor does not select this backend yet: clipping against Swing
 * overlays and popup lifecycle must be proved before a subsurface is usable as an editor viewport.
 * Requires --add-opens java.desktop/{java.awt,sun.awt,sun.awt.wl}=ALL-UNNAMED.
 */
class JbrWaylandSurface(private val bridge: LinuxGpuBridge, window: Window, x: Int, y: Int) : AutoCloseable {
    private val toolkit = Class.forName("sun.awt.SunToolkit")
    private val lock = toolkit.getMethod("awtLock")
    private val unlock = toolkit.getMethod("awtUnlock")
    private val surfaceClass = Class.forName("sun.awt.wl.WLSurface")
    private val child: Any
    val display: Long
    val surface: Long

    init {
        check(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit") { "Requires native JBR Wayland toolkit" }
        val displayClass = Class.forName("sun.awt.wl.WLDisplay")
        display = displayClass.getMethod("getDisplayPtr").invoke(displayClass.getMethod("getInstance").invoke(null)) as Long
        val peerField = Component::class.java.getDeclaredField("peer").apply { isAccessible = true }
        val peer = peerField.get(window) ?: error("Window has no peer")
        val componentPeer = Class.forName("sun.awt.wl.WLComponentPeer")
        val mainSurface = componentPeer.getMethod("getSurface").invoke(peer) ?: error("Window is not mapped")
        val mainSurfaceClass = Class.forName("sun.awt.wl.WLMainSurface")
        val childClass = Class.forName("sun.awt.wl.WLSubSurface")
        var created: Any? = null
        var pointer = 0L
        withAwtLock {
            created = childClass.getConstructor(mainSurfaceClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .newInstance(mainSurface, x, y)
            pointer = surfaceClass.getMethod("getWlSurfacePtr").invoke(created) as Long
            val subsurface = childClass.getDeclaredField("wlSubSurfacePtr").apply { isAccessible = true }.getLong(created)
            val parent = surfaceClass.getMethod("getWlSurfacePtr").invoke(mainSurface) as Long
            bridge.placeAbove(subsurface, parent)
            surfaceClass.getMethod("commit").invoke(mainSurface)
        }
        child = created!!
        surface = pointer
    }

    /** Never hold the AWT lock while waiting for producer I/O. */
    fun withAwtLock(action: Runnable) {
        lock.invoke(null)
        try { action.run() } finally { unlock.invoke(null) }
    }

    override fun close() = withAwtLock { surfaceClass.getMethod("dispose").invoke(child) }
}
