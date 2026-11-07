package dev.twodog.mush.scene

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import dev.twodog.mush.embed.ChildWindowEmbedding
import dev.twodog.mush.host.SpikeHost
import dev.twodog.mush.host.isHostFreezingUi
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
class SceneViewportEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val textEditor: Editor,
) : UserDataHolderBase(), FileEditor {
    private val document = textEditor.document
    private val resPath = GodotProject.resPath(file)!!
    private val projectPath = GodotProject.root(file)!!.path
    private val embedding = ChildWindowEmbedding(detachInput = System.getenv("MUSH_DETACH") == "1")
    @Volatile private var host = newHost()
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
    internal val viewActions = viewportViewActions(viewControls, { host.send("reset") }, ::restartHost)
    private val syncTimer = Timer(100) { update() }
    @Volatile private var uiSeenAt = System.nanoTime()
    private val hostWatch = AppExecutorUtil.getAppScheduledExecutorService()
        .scheduleWithFixedDelay(::watchHost, 1, 1, TimeUnit.SECONDS)
    private var lastLoad = " "
    private var loadInProgress = false
    private var problemCount = 0
    private var firstProblem: String? = null
    private var problemLine: Int? = null
    private var problemHighlighter: RangeHighlighter? = null
    private var sentVisible = true
    private var sentSelection: String? = null
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
            override fun documentChanged(event: DocumentEvent) = scheduleReload(RELOAD_DEBOUNCE_MS)
        }, this)
        textEditor.caretModel.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = sendSelection()
        }, this)
        // Saved sub-scenes and resources are only reread when the scene using them reloads.
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { GodotProject.isSceneDependency(it.path, projectPath, file.path) })
                    scheduleReload(RELOAD_DEBOUNCE_MS)
            }
        })
        syncTimer.start()
    }

    private fun newHost(): SpikeHost {
        lateinit var created: SpikeHost
        created = SpikeHost(
            { onReply(created, it) },
            { state, message -> onHostStateChanged(created, state, message) },
            ::startHostProcess,
        )
        return created
    }

    // On the host's own worker thread, so this file work never touches the EDT.
    private fun startHostProcess(command: List<String>): Process {
        GodotProject.ensureBlankScene(projectPath)
        return ProcessBuilder(command).redirectErrorStream(true).start()
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
            if (host.hwnd != 0L && !embedding.park(host.hwnd))
                endHungHost()
            super.removeNotify()
        }
    }

    // The visible rect also accounts for collapsed ancestors whose children still carry stale sizes.
    private val collapsed: Boolean
        get() = !placeholder.isShowing || placeholder.visibleRect.isEmpty

    private fun update() {
        if (disposed) return
        sendViewState()
        val visible = !collapsed
        if (visible != sentVisible && host.state == SpikeHost.State.RUNNING && host.send("visible ${if (visible) 1 else 0}"))
            sentVisible = visible
        val inTab = canvas.parent === placeholder
        if (!visible) {
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

    /**
     * Runs off the EDT, because the EDT is one of the things it watches. A cross-process child shares its parent's input
     * queue, and a hung host has frozen all of Rider that way until the moment it died; ending it is the one known cure,
     * whichever call got stuck.
     */
    private fun watchHost() {
        val watched = host
        if (disposed || watched.hwnd == 0L) return
        watched.send("ping")
        // These read a whole frame back from the GPU, which costs a visible hitch every second.
        if (DEBUG && placeholder.isShowing) watched.send("stats")
        SwingUtilities.invokeLater { uiSeenAt = System.nanoTime() }

        val now = System.nanoTime()
        if (isHostFreezingUi(now, uiSeenAt, watched.lastOutputAt, UI_FROZEN_NS)) {
            LOG.warn("$resPath: UI stuck and host silent for ${UI_FROZEN_NS / 1_000_000_000} s, stopping the host to free Rider")
            watched.stop(0)
        } else if (now - watched.lastOutputAt > NOT_RESPONDING_NS) {
            SwingUtilities.invokeLater {
                if (!disposed && host === watched && watched.isStarted)
                    status.text = "Viewport not responding: a script or breakpoint is holding it. Use Restart Viewport to start over."
            }
        }
    }

    private fun sendViewState() {
        if (disposed || !viewControls.hasViewState || host.state != SpikeHost.State.RUNNING) return
        pendingView?.let { if (host.send(it.command())) pendingView = null }
    }

    private fun sendSelection() {
        if (disposed || host.state != SpikeHost.State.RUNNING) return
        val path = SceneText.nodePathAt(document.charsSequence, textEditor.caretModel.logicalPosition.line)
        if (path != sentSelection && host.send(if (path == null) "select" else "select $path"))
            sentSelection = path
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

        // Loading a scene runs code from the project.
        if (!TrustedProjects.isProjectTrusted(project)) {
            viewControls.setAvailable(false)
            status.text = "Trust this project to show its scenes, then use Restart Viewport."
            return
        }
        val exe = System.getenv("MUSH_HOST_EXE")
        if (exe.isNullOrBlank()) {
            viewControls.setAvailable(false)
            status.text = "Set MUSH_HOST_EXE to the spike host (spike/MushHost)."
            return
        }
        val size = embedding.clientSize(canvas)
        // The first scene comes from the document once the window is embedded, so a stuck script cannot hold up embedding.
        host.start(listOf(exe) + embedding.hostArguments + listOf("--path", projectPath, "--scene", GodotProject.BLANK_SCENE,
            "--hidden-window", "--resolution", "${size.width.coerceAtLeast(64)}x${size.height.coerceAtLeast(64)}"))
    }

    private fun embedHost() {
        if (disposed || !canvas.isDisplayable || host.hwnd == 0L) return
        embedding.embed(host.hwnd, canvas)
        update()
    }

    /** A child window goes with its parent, so a host that could not be parked is about to lose its window anyway. */
    private fun endHungHost() {
        LOG.warn("$resPath: stopping a hung host before its window's parent is destroyed")
        host.stop(0)
        try {
            host.termination.get(HUNG_HOST_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            LOG.warn("$resPath: hung host did not exit in time")
        }
    }

    /** The document and the view choices live here, so a new host picks up where the old one stopped. */
    private fun restartHost() {
        if (disposed) return
        val old = host
        val fresh = newHost()
        host = fresh
        pendingView = if (viewControls.hasViewState) viewControls.state else null
        sentVisible = true
        sentSelection = null
        viewControls.setAvailable(true)
        status.text = "Restarting viewport…"
        // No grace period: the old host may be hung, and its window would sit over the new one until it exits.
        old.stop(0)
        old.termination.thenRun {
            SwingUtilities.invokeLater { if (host === fresh) attach() }
        }
    }

    private fun onReply(source: SpikeHost, reply: String) {
        if (disposed) return
        SwingUtilities.invokeLater {
            if (disposed || source !== host || source.state != SpikeHost.State.RUNNING) return@invokeLater
            when {
                reply.startsWith("view ") -> ViewState.parse(reply)?.let(viewControls::initialize)
                reply.startsWith("hwnd ") -> {
                    embedHost()
                    // Capture and encode the initial snapshot on the same pool as debounced updates.
                    scheduleReload(0)
                }
                reply.startsWith("loading ") -> {
                    loadInProgress = true
                    problemCount = 0
                    firstProblem = null
                    problemLine = null
                }
                // Godot also prints engine errors at any time, e.g. about its shader cache; those stay in the log.
                reply.startsWith("problem ") -> if (loadInProgress) {
                    val message = reply.removePrefix("problem ")
                    if (problemCount++ == 0)
                        firstProblem = SceneText.problemText(message, resPath)
                    if (problemLine == null)
                        problemLine = SceneText.problemLine(message)
                }
                reply.startsWith("loaded") || reply.startsWith("error") -> {
                    loadInProgress = false
                    lastLoad = if (reply.startsWith("loaded") && !viewControls.hasViewState) {
                        viewControls.setAvailable(false)
                        "Viewport host is missing view controls. Restart using gradlew runIde."
                    } else reply
                    markProblemLine()
                    showLoadResult()
                    sendSelection()
                }
                reply.startsWith("pong") -> if (!DEBUG) showLoadResult()
                reply.startsWith("selected ") -> showNodeInText(reply.removePrefix("selected "))
                reply.startsWith("stats ") -> {
                    val native = if (host.hwnd != 0L) embedding.describe(host.hwnd, canvas) else "no window"
                    status.text = "${loadResult()} | ${reply.removePrefix("stats ")} | $native"
                    status.toolTipText = status.text
                    LOG.info("$resPath: ${status.text}")
                }
            }
        }
    }

    private fun onHostStateChanged(source: SpikeHost, state: SpikeHost.State, message: String?) {
        SwingUtilities.invokeLater {
            if (disposed || source !== host || source.state != state) return@invokeLater
            if (state == SpikeHost.State.FAILED || state == SpikeHost.State.STOPPED)
                viewControls.setAvailable(false)
            when (state) {
                SpikeHost.State.STARTING -> status.text = "Starting viewport…"
                SpikeHost.State.FAILED -> status.text = "${message ?: "Viewport host disconnected."} Use Restart Viewport to start it again."
                SpikeHost.State.STOPPED -> status.text = "Viewport stopped. Use Restart Viewport to start it again."
                else -> Unit
            }
        }
    }

    // Problems come first: a narrow pane clips the end of this line.
    private fun loadResult(): String = when (problemCount) {
        0 -> lastLoad
        1 -> "1 problem: $firstProblem | $lastLoad"
        else -> "$problemCount problems, first: $firstProblem | $lastLoad"
    }

    private fun showLoadResult() {
        status.text = loadResult()
        status.toolTipText = status.text
    }

    // Replaced on every load: a highlighter from an earlier load has moved with the edits since.
    private fun markProblemLine() {
        if (problemHighlighter == null && problemLine == null) return
        val markup = DocumentMarkupModel.forDocument(document, project, true)
        problemHighlighter?.let(markup::removeHighlighter)
        // Godot reports an error at the end of the file one line past the last; an empty document has no lines at all.
        problemHighlighter = problemLine?.coerceAtMost(document.lineCount - 1)?.takeIf { it >= 0 }?.let {
            markup.addLineHighlighter(CodeInsightColors.ERRORS_ATTRIBUTES, it, HighlighterLayer.ERROR)
        }
    }

    /** A click in the viewport lands in the text, ready for typing. */
    private fun showNodeInText(path: String) {
        val line = SceneText.lineOfNode(document.charsSequence, path) ?: return
        // The outline stays on the clicked node, even when the cursor can only reach the section of its ancestor.
        sentSelection = SceneText.nodePathAt(document.charsSequence, line)
        textEditor.caretModel.moveToLogicalPosition(LogicalPosition(line, 0))
        textEditor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        IdeFocusManager.getInstance(project).requestFocus(textEditor.contentComponent, true)
    }

    private fun scheduleReload(delayMs: Int) {
        if (disposed) return
        reloadAlarm.cancelAllRequests()
        reloadAlarm.addRequest(::pushText, delayMs)
    }

    private fun pushText() {
        if (disposed || !host.isStarted)
            return
        val text = runReadActionBlocking { document.text }
        // In-band, so no temp file can race the host or the editor. The path goes last because it can contain spaces.
        host.send("text ${Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))} $resPath")
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
        hostWatch.cancel(false)
        watchTimer.stop()
        pendingView = null
        viewControls.setAvailable(false)
        if (!project.isDisposed) {
            problemLine = null
            markProblemLine()
        }
        embedding.retireCanvas(canvas, host.termination)
        host.stop()
    }

    private companion object {
        const val RELOAD_DEBOUNCE_MS = 120
        const val SPLITTER_GRAB_MARGIN = 4
        const val RESIZE_BEFORE_SHOW_TIMEOUT_MS = 50L
        const val NOT_RESPONDING_NS = 3_000_000_000L
        const val UI_FROZEN_NS = 5_000_000_000L
        const val HUNG_HOST_EXIT_TIMEOUT_MS = 1000L
        const val REMIX_BACKOFF_NS = 1_000_000_000L
        const val WATCH_AFTER_STEP_IN_NS = 600_000_000L
        val DEBUG = System.getenv("MUSH_DEBUG") == "1"
        val LOG = logger<SceneViewportEditor>()

        // Godot's default clear colour (0.3, 0.3, 0.3); later the host should report the project's own.
        val GODOT_CLEAR_COLOR = Color(0x4D, 0x4D, 0x4D)

        // One blue step off, so a screen capture tells the Canvas from Godot's frames.
        val CANVAS_COLOR = Color(0x4D, 0x4D, 0x4E)
    }
}
