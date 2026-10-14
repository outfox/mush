package dev.twodog.mush.scene

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadActionBlocking
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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyBoundsAdapter
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.swing.JComponent
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JRootPane
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The viewport half of the scene editor: a spike host's Godot window embedded as a child of a heavyweight Canvas.
 * Unsaved document text is pushed to the host on a short debounce.
 *
 * The Canvas does not live inside the editor tab. Editor tabs remove hidden tab components from the hierarchy, which
 * would destroy the Canvas HWND on every tab switch. Instead the tab holds a lightweight placeholder, and the Canvas
 * sits in the window's layered pane over the placeholder's visible area, hidden while the placeholder is not shown.
 */
class SceneViewportEditor(private val file: VirtualFile, private val document: Document) : UserDataHolderBase(), FileEditor {
    private val resPath = GodotProject.resPath(file)!!
    private val embedding = ChildWindowEmbedding(detachInput = System.getenv("MUSH_DETACH") == "1")
    private val host = SpikeHost(::onReply)
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val canvas = HostCanvas()
    private val placeholder = JPanel()
    private val status = JBLabel(" ")
    private val panel = JPanel(BorderLayout())
    private val syncTimer = Timer(100) { updateOverlay() }
    private var overlayRoot: JRootPane? = null

    init {
        canvas.background = Color.DARK_GRAY
        canvas.ignoreRepaint = true
        canvas.isVisible = false

        placeholder.background = Color.DARK_GRAY
        placeholder.addHierarchyListener { e ->
            if (e.changeFlags and (HierarchyEvent.SHOWING_CHANGED or HierarchyEvent.PARENT_CHANGED).toLong() != 0L)
                updateOverlay()
        }
        placeholder.addHierarchyBoundsListener(object : HierarchyBoundsAdapter() {
            override fun ancestorMoved(e: HierarchyEvent) = updateOverlay()
            override fun ancestorResized(e: HierarchyEvent) = updateOverlay()
        })
        placeholder.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = updateOverlay()
            override fun componentMoved(e: ComponentEvent) = updateOverlay()
        })

        panel.add(placeholder, BorderLayout.CENTER)
        // The splitter's grab zone extends over its neighbours through the glass pane, which never sees events over
        // the native child; a lightweight margin keeps that zone on the viewport side.
        panel.border = JBUI.Borders.emptyLeft(SPLITTER_GRAB_MARGIN)
        panel.add(status, BorderLayout.SOUTH)

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                reloadAlarm.cancelAllRequests()
                reloadAlarm.addRequest(::pushText, RELOAD_DEBOUNCE_MS)
            }
        }, this)
        syncTimer.start()
    }

    /** The Canvas HWND is recreated only when the editor moves to another IDE window; the Godot window steps out first. */
    private inner class HostCanvas : Canvas() {
        override fun addNotify() {
            super.addNotify()
            SwingUtilities.invokeLater { attach() }
        }

        override fun removeNotify() {
            if (host.hwnd != 0L)
                embedding.park(host.hwnd)
            super.removeNotify()
        }
    }

    /** Keeps the Canvas over the placeholder's visible area; hiding it is immediate and keeps its HWND alive. */
    private fun updateOverlay() {
        val root = if (placeholder.isShowing) SwingUtilities.getRootPane(placeholder) else null
        // Only a different window moves the Canvas; a hidden tab keeps it, hidden, in the current one.
        if (root != null && root !== overlayRoot) {
            overlayRoot?.layeredPane?.remove(canvas)
            overlayRoot = root
            root.layeredPane.add(canvas, OVERLAY_LAYER)
        }

        val area = root?.let { SwingUtilities.convertRectangle(placeholder, placeholder.visibleRect, it.layeredPane) }
        if (area == null || area.isEmpty) {
            if (canvas.isVisible)
                canvas.isVisible = false
            return
        }
        if (canvas.bounds != area) {
            canvas.bounds = area
            canvas.validate()
        }
        if (!canvas.isVisible)
            canvas.isVisible = true
        if (host.hwnd != 0L && canvas.isDisplayable)
            embedding.sync(host.hwnd, canvas)
    }

    private fun attach() {
        if (!canvas.isDisplayable)
            return
        if (host.isStarted) {
            if (host.hwnd != 0L)
                embedHost()
            return
        }

        val exe = System.getenv("MUSH_HOST_EXE")
        if (exe.isNullOrBlank() || !Files.isRegularFile(Path.of(exe))) {
            status.text = "Set MUSH_HOST_EXE to the spike host (spike/MushHost)."
            return
        }
        val size = embedding.clientSize(canvas)
        host.start(listOf(exe, "--mush-scene", resPath, "--hidden-window",
            "--resolution", "${size.width.coerceAtLeast(64)}x${size.height.coerceAtLeast(64)}"))
    }

    private fun embedHost() {
        embedding.embed(host.hwnd, canvas)
        updateOverlay()
    }

    private fun onReply(reply: String) {
        when {
            reply.startsWith("hwnd ") -> SwingUtilities.invokeLater {
                embedHost()
                // The editor may hold unsaved text from before the host was up.
                pushText()
            }
            reply.startsWith("loaded") || reply.startsWith("error") -> {
                SwingUtilities.invokeLater { status.text = reply }
            }
        }
    }

    private fun pushText() {
        if (!host.isStarted)
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
        syncTimer.stop()
        overlayRoot?.layeredPane?.remove(canvas)
        overlayRoot = null
        ApplicationManager.getApplication().executeOnPooledThread { host.stop() }
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 120
        const val SPLITTER_GRAB_MARGIN = 4

        // Above the content pane, below IntelliJ's palette and popup layers, so those cut out of the Canvas.
        val OVERLAY_LAYER: Int = JLayeredPane.DEFAULT_LAYER
    }
}
