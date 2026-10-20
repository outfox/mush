package dev.twodog.mush.embed

import com.intellij.openapi.diagnostic.logger
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.IllegalComponentStateException
import java.awt.Rectangle
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities

/**
 * Hosts the foreign process's Core Animation layer inside an AWT heavyweight component (macOS only).
 *
 * macOS cannot reparent a window across processes, so nothing here corresponds to Win32's `SetParent` or X11's
 * `ReparentWindow`. The host instead attaches its Metal layer to a remote `CAContext` and reports that context's
 * id, which arrives as the `child` handle; this side wraps the id in a private `CALayerHost` and hands the layer
 * to AWT through JAWT's `JAWT_SurfaceLayers` protocol, which overlays it on the component's rectangle.
 *
 * Three consequences shape the rest of the class. A hosted layer carries pixels only -- the window server routes
 * no events to it -- so input has to travel as host commands, which is not implemented yet. A layer cannot resize
 * its remote end either, so the size reaches the host through [resizeHost], where the other backends would call
 * `SetWindowPos` or `ConfigureWindow`. And hiding the AWT component leaves the layer composited, so [sync] hides
 * it by hand.
 *
 * One property of the host IDE shapes nearly everything here: Java2D's Metal pipeline re-evaluates what it
 * composites only when a layer tree changes, so attaching, hiding and showing all have to end in a re-insertion
 * on the native side. Without that a correctly placed, correctly sized viewport simply never appears -- or never
 * leaves.
 */
internal class LayerHostEmbedding(private val resizeHost: (Dimension) -> Boolean) : NativeWindowEmbedding {
    private var hosting: Component? = null
    private var shown = false
    private var lastScale: Double? = null
    private var lastHostSize: Dimension? = null
    /** The component size the layer was last reconciled against, so a settled viewport does no work per tick. */
    private var alignedTo: Dimension? = null
    private var lastCutouts = IntArray(0)
    /** The layer size the current mask was built for: a mask does not autoresize, so a resize invalidates it. */
    private var cutoutsBuiltFor: Dimension? = null

    /** [sync] decides whether a layer should exist at all, so embedding is the same work as a plain sync. */
    override fun embed(child: Long, parent: Component) = sync(child, parent)

    override fun sync(child: Long, parent: Component) {
        val visible = parent.isShowing && parent.width > 0 && parent.height > 0
        // Also covers AWT recreating the peer, which drops the layer with it.
        if (hosting !== parent) {
            if (!visible) return
            if (!attachLayer(child, parent)) return
        }
        val scale = scaleOf(parent)
        if (visible) {
            // A return to the screen always reshapes, even when the size matches: stepping out reparents the
            // Canvas and AWT pushes the layered pane's whole-window rectangle onto the layer, which leaves the
            // right size at the wrong origin -- something a size comparison cannot see.
            alignLayer(parent, force = !shown)
            sendHostSize(parent, scale)
            clipOverlappingUi(parent)
        }
        if (visible == shown && scale == lastScale)
            return
        if (!MushNative.setLayerState(parent, visible, scale))
            return
        if (visible != shown)
            LOG.info("sync: ${if (visible) "show" else "hide"}")
        shown = visible
        lastScale = scale
    }

    /** Tells the host what to render at, where the other backends resize the child window themselves. */
    override fun resizeBeforeShow(child: Long, parent: Component, timeoutMs: Long) =
        sendHostSize(parent, scaleOf(parent))

    private fun attachLayer(child: Long, parent: Component): Boolean {
        val id = child.toInt()
        val scale = scaleOf(parent)
        if (!MushNative.attachLayerHost(parent, id, scale)) {
            LOG.warn("could not host CAContext $id")
            hosting = null
            return false
        }
        hosting = parent
        // Attaching applied visibility and scale already, so this tick has no state left to push.
        shown = true
        lastScale = scale
        alignedTo = null
        LOG.info("hosting CAContext $id at scale $scale")
        return true
    }

    /**
     * AWT pushes the component's rectangle onto the hosted layer only when the component is *reshaped*, and
     * attaching a layer is not a reshape -- so a viewport whose layout settled before the host replied keeps a
     * zero-sized layer and renders nothing until the window happens to resize. Two real reshapes make AWT push
     * the rectangle it works out itself.
     *
     * Deliberately not computed here. AWT places the layer at the component's position *within the window*,
     * which neither the component's own bounds nor JAWT's surface info reports directly; computing it here is
     * what previously put the viewport over the whole window at its top-left corner.
     */
    private fun alignLayer(parent: Component, force: Boolean) {
        val size = Dimension(parent.width, parent.height)
        // Nothing has moved since the last reconciliation, so skip the JAWT round trip entirely.
        if (!force && size == alignedTo)
            return
        val packed = MushNative.layerSize(parent)
        if (packed < 0)
            return
        val width = (packed shr 32).toInt()
        val height = (packed and 0xFFFFFFFFL).toInt()
        if (force || width != size.width || height != size.height) {
            LOG.info("align: layer ${width}x$height vs component ${size.width}x${size.height}")
            pushLayerBounds(parent)
        }
        alignedTo = size
    }

    /**
     * Punches the IDE's own popups out of the viewport, which is what AWT does for the other two backends.
     *
     * A heavyweight child on Windows and X11 is subject to AWT's lightweight mixing: its window region is cut
     * wherever a lightweight overlaps it, which is the mechanism `isCutAway` observes. A hosted layer gets none of
     * that -- a sublayer always composites above its parent's content, and everything Swing draws *is* that
     * content -- so notifications, popups and tooltips over the viewport would simply be invisible.
     *
     * Only the layers above the editor's own and the glass pane can be in front of the viewport, so the scan stays
     * to those rather than walking the whole hierarchy.
     */
    private fun clipOverlappingUi(parent: Component) {
        val cutouts = overlappingUi(parent)
        val size = Dimension(parent.width, parent.height)
        // The mask is built in the layer's own coordinates, so it goes stale on a resize as surely as on a change
        // of rectangles -- and a stale one clips the wrong region rather than none.
        if (cutouts.contentEquals(lastCutouts) && size == cutoutsBuiltFor)
            return
        if (!MushNative.setLayerCutouts(parent, cutouts))
            return
        if (!cutouts.contentEquals(lastCutouts))
            LOG.info("clip: ${cutouts.size / 4} cutout(s) ${cutouts.toList().chunked(4)} in ${size.width}x${size.height}")
        lastCutouts = cutouts
        cutoutsBuiltFor = size
    }

    private fun overlappingUi(parent: Component): IntArray {
        val root = SwingUtilities.getRootPane(parent) ?: return IntArray(0)
        val origin = try {
            parent.locationOnScreen
        } catch (_: IllegalComponentStateException) {
            return IntArray(0)
        }
        val viewport = Rectangle(origin.x, origin.y, parent.width, parent.height)
        val cutouts = ArrayList<Int>()

        val layered = root.layeredPane
        for (above in layered.components)
            if (layered.getLayer(above) > JLayeredPane.DEFAULT_LAYER)
                addOverlap(above, viewport, cutouts)
        (root.glassPane as? Container)?.takeIf { it.isVisible }?.let { glass ->
            for (child in glass.components) addOverlap(child, viewport, cutouts)
        }
        return cutouts.toIntArray()
    }

    private fun addOverlap(candidate: Component, viewport: Rectangle, into: MutableList<Int>) {
        if (!candidate.isVisible || candidate.width <= 0 || candidate.height <= 0)
            return
        val origin = try {
            candidate.locationOnScreen
        } catch (_: IllegalComponentStateException) {
            return
        }
        val overlap = Rectangle(origin.x, origin.y, candidate.width, candidate.height).intersection(viewport)
        if (overlap.isEmpty)
            return
        into.add(overlap.x - viewport.x)
        into.add(overlap.y - viewport.y)
        into.add(overlap.width)
        into.add(overlap.height)
    }

    /** Two real reshapes, because AWT coalesces a reshape to the size it already has into nothing. */
    private fun pushLayerBounds(parent: Component) {
        val bounds = parent.bounds
        parent.setBounds(bounds.x, bounds.y, bounds.width, bounds.height + 1)
        parent.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    private fun sendHostSize(parent: Component, scale: Double) {
        val size = pixelSize(parent, scale)
        if (size.width == 0 || size.height == 0 || size == lastHostSize)
            return
        if (resizeHost(size))
            lastHostSize = size
    }

    /**
     * The peer is about to go, taking the layer with it. Dropping it here keeps AWT from compositing a dead
     * context, and the next [sync] re-hosts because [hosting] no longer matches the parent.
     */
    override fun park(child: Long) = detach()

    override fun close() = detach()

    private fun detach() {
        hosting?.let { if (it.isDisplayable) MushNative.detachLayer(it) }
        hosting = null
        shown = false
        lastScale = null
        lastHostSize = null
        alignedTo = null
        lastCutouts = IntArray(0)
        cutoutsBuiltFor = null
    }

    /** Reports the layer tree's real geometry, not just what this class believes, as the Windows backend does. */
    override fun describe(child: Long, parent: Component): String =
        "context=${child.toInt()} hosted=${hosting === parent} shown=$shown " +
            "component=${parent.width}x${parent.height} ${MushNative.layerState(parent)}"

    override fun clientSize(parent: Component): Rectangle =
        pixelSize(parent).let { Rectangle(0, 0, it.width, it.height) }

    private fun scaleOf(component: Component): Double =
        component.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0

    /** Godot renders in pixels while AWT lays out in points, as on X11; sizes crossing to the host are scaled up. */
    private fun pixelSize(component: Component, scale: Double = scaleOf(component)): Dimension =
        Dimension((component.width * scale).toInt(), (component.height * scale).toInt())

    private companion object {
        val LOG = logger<LayerHostEmbedding>()
    }
}
