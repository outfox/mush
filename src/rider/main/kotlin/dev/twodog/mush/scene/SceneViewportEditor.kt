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
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.beans.PropertyChangeListener
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The viewport half of the scene editor: a spike host's Godot window embedded as a child of a heavyweight Canvas.
 * Unsaved document text is pushed to the host on a short debounce.
 */
class SceneViewportEditor(private val file: VirtualFile, private val document: Document) : UserDataHolderBase(), FileEditor {
    private val resPath = GodotProject.resPath(file)!!
    private val embedding = ChildWindowEmbedding(detachInput = System.getenv("MUSH_DETACH") == "1")
    private val pendingTexts = ConcurrentLinkedQueue<Path>()
    private val host = SpikeHost(::onReply)
    private val reloadAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val canvas = HostCanvas()
    private val status = JBLabel(" ")
    private val panel = JPanel(BorderLayout())
    private val syncTimer = Timer(100) { sync() }

    init {
        canvas.background = Color.DARK_GRAY
        // A heavyweight's minimum size defaults to its current size, which pins the editor splitter.
        canvas.minimumSize = Dimension(0, 0)
        canvas.preferredSize = JBUI.size(320, 240)
        canvas.ignoreRepaint = true
        canvas.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = sync()
            override fun componentShown(e: ComponentEvent) = sync()
            override fun componentHidden(e: ComponentEvent) = sync()
        })
        panel.add(canvas, BorderLayout.CENTER)
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

    /** AWT recreates the Canvas HWND when the editor moves between windows; the Godot window must step out first. */
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
        sync()
    }

    private fun onReply(reply: String) {
        when {
            reply.startsWith("hwnd ") -> SwingUtilities.invokeLater {
                embedHost()
                // The editor may hold unsaved text from before the host was up.
                pushText()
            }
            reply.startsWith("loaded") || reply.startsWith("error") -> {
                pendingTexts.poll()?.let { Files.deleteIfExists(it) }
                SwingUtilities.invokeLater { status.text = reply }
            }
        }
    }

    private fun pushText() {
        if (!host.isStarted)
            return
        val text = runReadActionBlocking { document.text }
        val temp = Files.createTempFile("mush-scene", ".tscn")
        Files.writeString(temp, text)
        pendingTexts.add(temp)
        host.send("text $resPath $temp")
    }

    private fun sync() {
        if (host.hwnd != 0L && canvas.isDisplayable)
            embedding.sync(host.hwnd, canvas)
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
        if (host.hwnd != 0L)
            embedding.park(host.hwnd)
        ApplicationManager.getApplication().executeOnPooledThread {
            host.stop()
            generateSequence { pendingTexts.poll() }.forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 120
        const val SPLITTER_GRAB_MARGIN = 4
    }
}
