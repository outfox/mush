package dev.twodog.mush.host

import com.intellij.openapi.diagnostic.logger
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Whether a hung host is the likely reason the UI thread is stuck. Both must have gone quiet: the UI alone stalls during
 * ordinary IDE work, and a host alone stalls at a breakpoint or in a slow load, and neither of those should end a host.
 */
internal fun isHostFreezingUi(now: Long, uiSeenAt: Long, hostSeenAt: Long, limit: Long): Boolean =
    now - uiSeenAt > limit && now - hostSeenAt > limit

/**
 * The spike host process (`spike/MushHost`): line commands on stdin, `mush:`-prefixed replies on stdout.
 * Stands in for the planned pipe protocol until the real host exists.
 */
class SpikeHost(
    private val onReply: (String) -> Unit,
    private val onStateChanged: (State, String?) -> Unit = { _, _ -> },
    private val startProcess: (List<String>) -> Process = { ProcessBuilder(it).redirectErrorStream(true).start() },
    private val shutdownTimeoutMs: Long = 5000,
) {
    enum class State { NEW, STARTING, RUNNING, STOPPING, STOPPED, FAILED }

    @Volatile var state = State.NEW
        private set
    @Volatile var hwnd: Long = 0
        private set
    /** Completes after exit, or after a cancelled startup that has not published any window. */
    val termination = CompletableFuture<Unit>()
    /** When the host last wrote anything; a silent host is either idle with nothing to say or hung, so ping it to tell. */
    @Volatile var lastOutputAt = System.nanoTime()
        private set

    private val gate = ReentrantLock()
    private val changed = gate.newCondition()
    private val pending = PendingCommands()
    private var process: Process? = null

    val isStarted: Boolean get() = state == State.STARTING || state == State.RUNNING
    internal val pendingCommandCount: Int get() = gate.withLock { pending.size }

    /** One launcher per process lifetime. No caller waits for process startup or I/O. */
    fun start(command: List<String>) {
        gate.withLock {
            if (state != State.NEW) return
            state = State.STARTING
        }
        notifyState(State.STARTING)
        background("mush host writer") { launchAndWrite(command.toList()) }
    }

    /** Returns false when the host is stopped or the bounded control queue is full. */
    fun send(command: String): Boolean = gate.withLock {
        if (!isStarted) return false
        val accepted = pending.offer(command)
        if (accepted) changed.signalAll()
        accepted
    }

    /** The shutdown deadline is independent of the writer, even when its pipe is full. */
    @JvmOverloads
    fun stop(timeoutMs: Long = shutdownTimeoutMs) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val stopping = gate.withLock {
            when (state) {
                State.NEW -> state = State.STOPPED
                State.STARTING, State.RUNNING -> state = State.STOPPING
                else -> return
            }
            hwnd = 0
            pending.clear()
            changed.signalAll()
            state
        }
        if (stopping == State.STOPPING) background("mush host shutdown") { enforceShutdown(deadline) }
        else termination.complete(Unit)
        notifyState(stopping)
    }

    private fun launchAndWrite(command: List<String>) {
        gate.withLock {
            if (state != State.STARTING) return
        }
        val started = try {
            LOG.info("starting ${command.joinToString(" ")}")
            startProcess(command)
        } catch (e: Exception) {
            gate.withLock {
                if (state == State.STOPPING || state == State.STOPPED) {
                    state = State.STOPPED
                    changed.signalAll()
                    return
                }
            }
            fail("Cannot start host: ${e.message}")
            return
        }

        val launched = gate.withLock {
            process = started
            if (state == State.STARTING) state = State.RUNNING
            changed.signalAll()
            state
        }
        started.onExit().thenRun { onExit(started) }
        if (launched == State.STOPPED || launched == State.FAILED) {
            // Startup finished after the shutdown deadline. Never leave that late process alive.
            started.destroyForcibly()
            return
        }
        background("mush host stdout") { readReplies(started) }
        if (launched == State.RUNNING) notifyState(State.RUNNING)

        try {
            started.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                while (true) {
                    val next = gate.withLock {
                        while (state == State.RUNNING && pending.size == 0) changed.await()
                        when (state) {
                            State.RUNNING -> pending.poll()
                            State.STOPPING -> "quit"
                            else -> null
                        }
                    } ?: break
                    writer.write(next)
                    writer.newLine()
                    writer.flush()
                    if (next == "quit") break
                }
            }
        } catch (e: IOException) {
            fail("Host input closed: ${e.message}")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            fail("Host writer interrupted")
        }
    }

    private fun readReplies(started: Process) {
        try {
            started.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    lastOutputAt = System.nanoTime()
                    if (state != State.RUNNING) continue
                    if (!line.startsWith("mush:")) {
                        if (line.contains("ERROR")) LOG.warn("host: $line")
                        GODOT_ERROR.matchEntire(line)?.let { deliver("problem ${it.groupValues[1]}") }
                        continue
                    }
                    val reply = line.removePrefix("mush:")
                    if (reply.startsWith("hwnd ")) {
                        val handle = reply.removePrefix("hwnd ").trim().toLongOrNull()
                        if (handle == null) {
                            LOG.warn("invalid host window handle")
                            continue
                        }
                        gate.withLock { if (state == State.RUNNING) hwnd = handle }
                    }
                    deliver(reply)
                }
            }
            if (state == State.RUNNING && started.isAlive) fail("Host output closed before exit")
        } catch (e: IOException) {
            fail("Host output closed: ${e.message}")
        }
    }

    private fun deliver(reply: String) {
        if (state != State.RUNNING) return
        try {
            onReply(reply)
        } catch (e: Exception) {
            LOG.warn("host reply handler failed", e)
        }
    }

    private fun onExit(exited: Process) {
        val finished = gate.withLock {
            if (process !== exited) return
            process = null
            hwnd = 0
            pending.clear()
            if (state != State.FAILED) state = if (state == State.STOPPING || state == State.STOPPED) State.STOPPED else State.FAILED
            changed.signalAll()
            state
        }
        termination.complete(Unit)
        notifyState(finished, if (finished == State.FAILED) "Host exited (code ${exited.exitValue()})." else null)
    }

    private fun fail(message: String) {
        val running = gate.withLock {
            if (!isStarted) return
            state = State.FAILED
            hwnd = 0
            pending.clear()
            changed.signalAll()
            process
        }
        notifyState(State.FAILED, message)
        if (running == null) termination.complete(Unit) else running.destroyForcibly()
    }

    private fun enforceShutdown(deadline: Long) {
        val running = gate.withLock {
            while (process == null && state == State.STOPPING) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    state = State.STOPPED
                    changed.signalAll()
                    break
                }
                changed.awaitNanos(remaining)
            }
            process
        }
        if (running == null) {
            termination.complete(Unit)
            if (state == State.STOPPED) notifyState(State.STOPPED)
            return
        }
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(0)
        if (!running.waitFor(remaining, TimeUnit.NANOSECONDS)) running.destroyForcibly()
    }

    private fun notifyState(value: State, message: String? = null) {
        try {
            onStateChanged(value, message)
        } catch (e: Exception) {
            LOG.warn("host state handler failed", e)
        }
    }

    private companion object {
        val LOG = logger<SpikeHost>()
        val GODOT_ERROR = Regex("""(?:USER |SCRIPT |SHADER )?ERROR: (.+)""")
        fun background(name: String, action: () -> Unit) = Thread(action, name).apply { isDaemon = true }.start()
    }
}
