package dev.twodog.mush.host

import com.intellij.openapi.diagnostic.logger
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * The spike host process (`spike/MushHost`): line commands on stdin, `mush:`-prefixed replies on stdout.
 * Stands in for the planned pipe protocol until the real host exists.
 */
class SpikeHost(private val onReply: (String) -> Unit) {
    @Volatile var hwnd: Long = 0
        private set

    private var process: Process? = null
    private var input: BufferedWriter? = null

    val isStarted: Boolean get() = process != null

    fun start(command: List<String>) {
        LOG.info("starting ${command.joinToString(" ")}")
        val started = ProcessBuilder(command).redirectErrorStream(true).start()
        process = started
        input = BufferedWriter(OutputStreamWriter(started.outputStream, StandardCharsets.UTF_8))
        Thread({ readReplies(started) }, "mush host stdout").apply { isDaemon = true }.start()
    }

    private fun readReplies(process: Process) {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("mush:")) {
                    if (line.contains("ERROR"))
                        LOG.warn("host: $line")
                    continue
                }
                val reply = line.removePrefix("mush:")
                if (reply.startsWith("hwnd "))
                    hwnd = reply.removePrefix("hwnd ").trim().toLong()
                // The reader must outlive any failure in a reply handler, or the host goes silent.
                try {
                    onReply(reply)
                } catch (e: Exception) {
                    LOG.error("reply handler failed for '$reply'", e)
                }
            }
        }
    }

    @Synchronized
    fun send(command: String) {
        val writer = input ?: return
        try {
            writer.write(command)
            writer.newLine()
            writer.flush()
        } catch (e: IOException) {
            LOG.warn("host is gone: ${e.message}")
        }
    }

    fun stop() {
        val running = process ?: return
        send("quit")
        if (!running.waitFor(5, TimeUnit.SECONDS))
            running.destroy()
        process = null
    }

    private companion object {
        val LOG = logger<SpikeHost>()
    }
}
