package dev.twodog.mush.scene

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.Alarm
import dev.twodog.mush.embed.ChildWindowEmbedding
import dev.twodog.mush.host.SpikeHost
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import java.util.Base64
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The viewport half of the scene editor: a spike host's Godot window embedded as a child of a heavyweight Canvas.
 * Unsaved document text is pushed to the host on a short debounce.
 *
 * Editor tabs collapse a tab being hidden to (0, 0, 0, 0) and remove it from the hierarchy once focus settles,
 * which would destroy the Canvas native window on every tab switch. On collapse, the Canvas steps out into the window's
 * layered pane through `setComponentZOrder`, which moves a heavyweight without recreating its peer, and steps back
 * in the same way when the tab shows again.
 */
class SceneViewportEditor(private val file: VirtualFile, private val document: Document) : UserDataHolderBase(), FileEditor {
    private val resPath = GodotProject.resPath(file)!!
    private val embedding = ChildWindowEmbedding(
        detachInput = System.getenv("MUSH_DETACH") == "1",
        resizeHost = ::sendHostSize,
    )
    private val host = SpikeHost(::onReply, ::onHostStateChanged)
    @Volatile private var disposed = false
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val canvas = HostCanvas()
    private val placeholder = JPanel(BorderLayout())
    // A label's text width would become the panel's minimum width and pin the editor splitter.
    private val status = object : JBLabel(" ") {
        override fun getMinimumSize() = Dimension(0, super.getMinimumSize().height)
        override fun getPreferredSize() = Dimension(0, super.getPreferredSize().height)
    }
    private val panel = JPanel(BorderLayout())
    private var pendingView: ViewState? = null
    private val viewControls = ViewportViewControls { state ->
        pendingView = state
        sendViewState()
    }
    internal val viewActions = viewportViewActions(viewControls)
    private val syncTimer = Timer(100) { update(); pollStats() }
    private var ticks = 0
    private var lastLoad = " "
    private var remixFailedAt = 0L
    // The region empties when the outgoing tab leaves, shortly after a step in; watch it every frame until then.
    private var watchUntil = 0L
    private val watchTimer = Timer(16) {
        if (System.nanoTime() > watchUntil)
            (it.source as Timer).stop()
        else if (canvas.parent === placeholder)
            remixIfCutAway()
    }

    init {
        LOG.info("$resPath: editor created")
        // A heavyweight's minimum size defaults to its current size, which pins the editor splitter.
        canvas.minimumSize = Dimension(0, 0)
        canvas.preferredSize = JBUI.size(320, 240)
        canvas.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = update()
        })

        placeholder.background = GODOT_CLEAR_COLOR
        placeholder.add(canvas, BorderLayout.CENTER)
        placeholder.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L)
                update()
        }
        placeholder.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = update()
        })

        panel.add(placeholder, BorderLayout.CENTER)
        // The splitter's grab zone extends over its neighbours through the glass pane, which never sees events over
        // the native child; a lightweight margin keeps that zone on the viewport side.
        panel.border = JBUI.Borders.emptyLeft(SPLITTER_GRAB_MARGIN)
        panel.add(status, BorderLayout.SOUTH)

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (disposed) return
                reloadAlarm.cancelAllRequests()
                reloadAlarm.addRequest(::pushText, RELOAD_DEBOUNCE_MS)
            }
        }, this)
        syncTimer.start()
    }

    /** Paints Godot's default clear colour, so a frame where the child lags behind a resize does not flash. */
    private inner class HostCanvas : Canvas() {
        override fun paint(g: Graphics) {
            g.color = CANVAS_COLOR
            g.fillRect(0, 0, width, height)
        }

        override fun addNotify() {
            super.addNotify()
            SwingUtilities.invokeLater { attach() }
        }

        // Only reached when the Canvas could not step out first, e.g. when the editor closes.
        override fun removeNotify() {
            if (host.hwnd != 0L)
                embedding.park(host.hwnd)
            super.removeNotify()
        }
    }

    // The visible rect also accounts for collapsed ancestors whose children still carry stale sizes.
    private val collapsed: Boolean
        get() = !placeholder.isShowing || placeholder.visibleRect.isEmpty

    private fun update() {
        if (disposed) return
        sendViewState()
        val inTab = canvas.parent === placeholder
        if (collapsed) {
            if (inTab)
                stepOut()
        } else if (!inTab) {
            stepIn()
        } else {
            remixIfCutAway()
        }
        if (host.hwnd != 0L && canvas.isDisplayable)
            embedding.sync(host.hwnd, canvas)
    }

    /** The channel a backend uses when it cannot resize the child window itself; see [ChildWindowEmbedding]. */
    private fun sendHostSize(size: Dimension): Boolean =
        host.state == SpikeHost.State.RUNNING && host.send("resolution ${size.width}x${size.height}")

    private fun stepOut() {
        val layeredPane = SwingUtilities.getRootPane(placeholder)?.layeredPane ?: return
        LOG.info("$resPath: step out")
        canvas.isVisible = false
        layeredPane.setComponentZOrder(canvas, 0)
    }

    private fun stepIn() {
        LOG.info("$resPath: step in at ${placeholder.width}x${placeholder.height}")
        placeholder.setComponentZOrder(canvas, 0)
        placeholder.validate()
        if (host.hwnd != 0L && canvas.isDisplayable)
            embedding.resizeBeforeShow(host.hwnd, canvas, RESIZE_BEFORE_SHOW_TIMEOUT_MS)
        canvas.isVisible = true
        remixFailedAt = 0L
        watchUntil = System.nanoTime() + WATCH_AFTER_STEP_IN_NS
        watchTimer.restart()
    }

    /**
     * AWT clips a heavyweight's window region to its lightweight ancestors and cuts out lightweights above it. A shape
     * computed while an ancestor was still collapsed can stay empty, hiding the viewport; showing the Canvas again
     * makes AWT recompute it, which needs a valid parent.
     */
    private fun remixIfCutAway() {
        if (!canvas.isShowing || !embedding.isCutAway(canvas))
            return
        // A lightweight covering the whole viewport empties the region legitimately; back off when a remix didn't help.
        if (System.nanoTime() - remixFailedAt < REMIX_BACKOFF_NS)
            return
        LOG.info("$resPath: canvas region was empty, remixing")
        canvas.isVisible = false
        placeholder.validate()
        canvas.isVisible = true
        remixFailedAt = if (embedding.isCutAway(canvas)) System.nanoTime() else 0L
    }

    // Diagnostics for the viewport that stays grey after tab switches.
    private fun pollStats() {
        if (++ticks % STATS_TICKS == 0 && host.isStarted && placeholder.isShowing)
            host.send("stats")
    }

    private fun sendViewState() {
        if (disposed || !viewControls.hasViewState || host.state != SpikeHost.State.RUNNING) return
        pendingView?.let { if (host.send(it.command())) pendingView = null }
    }

    private fun attach() {
        if (disposed || !canvas.isDisplayable)
            return
        embedding.unsupportedReason?.let {
            viewControls.setAvailable(false)
            status.text = it
            status.toolTipText = it
            return
        }
        if (host.isStarted) {
            if (host.hwnd != 0L)
                embedHost()
            return
        }

        val exe = System.getenv("MUSH_HOST_EXE")
        if (exe.isNullOrBlank()) {
            viewControls.setAvailable(false)
            status.text = "Set MUSH_HOST_EXE to the spike host (spike/MushHost)."
            return
        }
        val size = embedding.clientSize(canvas)
        host.start(listOf(exe) + embedding.hostArguments + listOf("--mush-scene", resPath, "--hidden-window",
            "--resolution", "${size.width.coerceAtLeast(64)}x${size.height.coerceAtLeast(64)}"))
    }

    private fun embedHost() {
        if (disposed || !canvas.isDisplayable || host.hwnd == 0L) return
        embedding.embed(host.hwnd, canvas)
        update()
    }

    private fun onReply(reply: String) {
        if (disposed) return
        SwingUtilities.invokeLater {
            if (disposed || host.state != SpikeHost.State.RUNNING) return@invokeLater
            when {
                reply.startsWith("view ") -> ViewState.parse(reply)?.let(viewControls::initialize)
                // What the host actually published, which decides whether the viewport can show anything at all.
                reply.startsWith("layer ") -> LOG.info("$resPath: published ${reply.removePrefix("layer ")}")
                reply.startsWith("hwnd ") -> {
                    embedHost()
                    // Capture and encode the initial snapshot on the same pool as debounced updates.
                    reloadAlarm.cancelAllRequests()
                    reloadAlarm.addRequest(::pushText, 0)
                }
                reply.startsWith("loaded") || reply.startsWith("error") -> {
                    lastLoad = if (reply.startsWith("loaded") && !viewControls.hasViewState) {
                        viewControls.setAvailable(false)
                        "Viewport host is missing view controls. Restart using gradlew runIde."
                    } else reply
                    status.text = lastLoad
                }
                reply.startsWith("stats ") -> {
                    val native = if (host.hwnd != 0L) embedding.describe(host.hwnd, canvas) else "no window"
                    status.text = "$lastLoad | ${reply.removePrefix("stats ")} | $native"
                    status.toolTipText = status.text
                    LOG.info("$resPath: ${status.text}")
                }
            }
        }
    }

    private fun onHostStateChanged(state: SpikeHost.State, message: String?) {
        SwingUtilities.invokeLater {
            if (disposed || host.state != state) return@invokeLater
            if (state == SpikeHost.State.FAILED || state == SpikeHost.State.STOPPED)
                viewControls.setAvailable(false)
            when (state) {
                SpikeHost.State.STARTING -> status.text = "Starting viewport…"
                SpikeHost.State.FAILED -> status.text = message ?: "Viewport host disconnected."
                SpikeHost.State.STOPPED -> status.text = "Viewport stopped."
                else -> Unit
            }
        }
    }

    private fun pushText() {
        if (disposed || !host.isStarted)
            return
        val text = runReadActionBlocking { document.text }
        // In-band, so no temp file can race the host or the editor.
        host.send("text $resPath ${Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))}")
    }

    override fun getComponent(): JComponent = panel

    override fun getPreferredFocusedComponent(): JComponent? = null

    override fun getName(): String = "Viewport"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = file.isValid

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        LOG.info("$resPath: editor disposed")
        disposed = true
        reloadAlarm.cancelAllRequests()
        syncTimer.stop()
        watchTimer.stop()
        pendingView = null
        viewControls.setAvailable(false)
        embedding.retireCanvas(canvas, host.termination)
        host.stop()
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 120
        const val SPLITTER_GRAB_MARGIN = 4
        const val RESIZE_BEFORE_SHOW_TIMEOUT_MS = 50L
        const val STATS_TICKS = 10
        const val REMIX_BACKOFF_NS = 1_000_000_000L
        const val WATCH_AFTER_STEP_IN_NS = 600_000_000L
        val LOG = logger<SceneViewportEditor>()

        // Godot's default clear colour (0.3, 0.3, 0.3); later the host should report the project's own.
        val GODOT_CLEAR_COLOR = Color(0x4D, 0x4D, 0x4D)

        // One blue step off, so a screen capture tells the Canvas from Godot's frames.
        val CANVAS_COLOR = Color(0x4D, 0x4D, 0x4E)
    }
}
