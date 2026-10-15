package dev.twodog.mush.host

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

class SpikeHostTest {
    @Test fun `a full pipe cannot block EDT submissions or shutdown`() {
        Fixture("non-reading").use { fixture ->
            fixture.start()
            assertTrue(fixture.host.send("text res://scene.tscn " + "A".repeat(2_000_000)))
            await { fixture.host.pendingCommandCount == 0 }
            onEdt {
                repeat(10_000) {
                    assertTrue(fixture.host.send("text res://scene.tscn $it"))
                    assertTrue(fixture.host.send("stats"))
                }
                assertTrue(fixture.host.pendingCommandCount <= 2)
                fixture.host.stop()
                assertFalse(fixture.host.send("stats"))
            }
            assertTrue("shutdown did not terminate the blocked child", fixture.process.get().waitFor(3, TimeUnit.SECONDS))
            await { fixture.host.state == SpikeHost.State.STOPPED }
            assertEquals(0, fixture.host.hwnd)
            assertEquals(0, fixture.host.pendingCommandCount)
        }
    }

    @Test fun `unexpected exit clears the window and rejects further sends`() {
        Fixture("echo").use { fixture ->
            fixture.start()
            assertEquals(12345, fixture.host.hwnd)
            assertTrue(fixture.host.send("exit"))
            await { fixture.host.state == SpikeHost.State.FAILED }
            assertFalse(fixture.host.isStarted)
            assertEquals(0, fixture.host.hwnd)
            assertFalse(fixture.host.send("stats"))
        }
    }

    @Test fun `graceful quit exits without forced termination`() {
        Fixture("echo").use { fixture ->
            fixture.start()
            onEdt { fixture.host.stop() }
            assertTrue(fixture.process.get().waitFor(3, TimeUnit.SECONDS))
            assertEquals(0, fixture.process.get().exitValue())
            await { fixture.host.state == SpikeHost.State.STOPPED }
        }
    }

    @Test fun `a closed reply stream terminates a still running child`() {
        Fixture("closed-output").use { fixture ->
            fixture.start()
            await { fixture.host.state == SpikeHost.State.FAILED }
            fixture.host.termination.get(3, TimeUnit.SECONDS)
            assertFalse(fixture.process.get().isAlive)
            assertEquals(0, fixture.host.hwnd)
        }
    }

    @Test fun `closing during slow startup kills a late process without delivering replies`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Fixture("echo", beforeStart = {
            assertFalse("process startup ran on the EDT", SwingUtilities.isEventDispatchThread())
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }).use { fixture ->
            try {
                onEdt { fixture.host.start(fixture.command) }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                onEdt { fixture.host.stop() }
                await { fixture.host.state == SpikeHost.State.STOPPED }
                release.countDown()
                await { fixture.process.get() != null }
                assertTrue(fixture.process.get().waitFor(3, TimeUnit.SECONDS))
                assertEquals(0, fixture.host.hwnd)
                assertEquals(1, fixture.ready.count)
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun `closing before startup prevents launching`() {
        Fixture("echo").use { fixture ->
            onEdt {
                fixture.host.stop()
                fixture.host.start(fixture.command)
            }
            assertEquals(SpikeHost.State.STOPPED, fixture.host.state)
            assertNull(fixture.process.get())
        }
    }

    @Test fun `startup failures are reported asynchronously`() {
        val failed = CountDownLatch(1)
        val message = AtomicReference<String>()
        val host = SpikeHost({}, { state, detail ->
            if (state == SpikeHost.State.FAILED) {
                message.set(detail)
                failed.countDown()
            }
        }, { throw IOException("fixture startup failure") })
        onEdt { host.start(listOf("fixture")) }
        assertTrue(failed.await(3, TimeUnit.SECONDS))
        assertTrue(message.get().contains("fixture startup failure"))
        assertFalse(host.isStarted)
    }

    @Test fun `a malformed HWND cannot kill the reader`() {
        Fixture("malformed").use { fixture ->
            fixture.start()
            val reply = CountDownLatch(1)
            fixture.onReply = { if (it == "reply second") reply.countDown() }
            assertTrue(fixture.host.send("second"))
            assertTrue(reply.await(3, TimeUnit.SECONDS))
            assertEquals(12345, fixture.host.hwnd)
        }
    }

    private class Fixture(mode: String, beforeStart: () -> Unit = {}) : AutoCloseable {
        val process = AtomicReference<Process>()
        val ready = CountDownLatch(1)
        @Volatile var onReply: (String) -> Unit = {}
        val command = listOf(
            Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java").toString(),
            "-cp", System.getProperty("mush.test.fixtureClasspath"),
            HostProcessFixture::class.java.name, mode,
        )
        val host = SpikeHost({ reply ->
            if (reply == "hwnd 12345") ready.countDown()
            onReply(reply)
        }, startProcess = {
            beforeStart()
            ProcessBuilder(it).redirectErrorStream(true).start().also(process::set)
        }, shutdownTimeoutMs = 250)

        fun start() {
            host.start(command)
            assertTrue("fixture did not report its window", ready.await(5, TimeUnit.SECONDS))
        }

        override fun close() {
            process.get()?.let { it.destroyForcibly().waitFor(3, TimeUnit.SECONDS) }
            host.stop()
        }
    }

    private companion object {
        fun onEdt(action: () -> Unit) {
            CompletableFuture.runAsync { SwingUtilities.invokeAndWait(action) }.get(3, TimeUnit.SECONDS)
        }

        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition()) {
                assertTrue("condition did not become true", System.nanoTime() < deadline)
                Thread.sleep(5)
            }
        }
    }
}
