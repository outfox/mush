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
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.HierarchyEvent
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
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
 * which would destroy the Canvas HWND on every tab switch. On collapse, the Canvas steps out into the window's
 * layered pane through `setComponentZOrder`, which moves a heavyweight without recreating its peer, and steps back
 * in the same way when the tab shows again.
 */
class SceneViewportEditor(private val file: VirtualFile, private val document: Document) : UserDataHolderBase(), FileEditor {
    private val resPath = GodotProject.resPath(file)!!
    private val embedding = ChildWindowEmbedding(detachInput = System.getenv("MUSH_DETACH") == "1")
    private val host = SpikeHost(::onReply)
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val canvas = HostCanvas()
    private val placeholder = JPanel(BorderLayout())
    private val status = JBLabel(" ")
    private val panel = JPanel(BorderLayout())
    private val syncTimer = Timer(100) { update() }

    init {
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
                reloadAlarm.cancelAllRequests()
                reloadAlarm.addRequest(::pushText, RELOAD_DEBOUNCE_MS)
            }
        }, this)
        syncTimer.start()
    }

    /** Paints Godot's default clear colour, so a frame where the child lags behind a resize does not flash. */
    private inner class HostCanvas : Canvas() {
        override fun paint(g: Graphics) {
            g.color = GODOT_CLEAR_COLOR
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

    private val collapsed: Boolean
        get() = !placeholder.isShowing || placeholder.width == 0 || placeholder.height == 0

    private fun update() {
        val inTab = canvas.parent === placeholder
        if (collapsed) {
            if (inTab)
                stepOut()
        } else if (!inTab) {
            stepIn()
        }
        if (host.hwnd != 0L && canvas.isDisplayable)
            embedding.sync(host.hwnd, canvas)
    }

    private fun stepOut() {
        val layeredPane = SwingUtilities.getRootPane(placeholder)?.layeredPane ?: return
        canvas.isVisible = false
        layeredPane.setComponentZOrder(canvas, 0)
    }

    private fun stepIn() {
        placeholder.setComponentZOrder(canvas, 0)
        placeholder.validate()
        if (host.hwnd != 0L && canvas.isDisplayable)
            embedding.resizeBeforeShow(host.hwnd, canvas, RESIZE_BEFORE_SHOW_TIMEOUT_MS)
        canvas.isVisible = true
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
        update()
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
        canvas.parent?.remove(canvas)
        ApplicationManager.getApplication().executeOnPooledThread { host.stop() }
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 120
        const val SPLITTER_GRAB_MARGIN = 4
        const val RESIZE_BEFORE_SHOW_TIMEOUT_MS = 50L

        // Godot's default clear colour (0.3, 0.3, 0.3); later the host should report the project's own.
        val GODOT_CLEAR_COLOR = Color(0x4D, 0x4D, 0x4D)
    }
}
