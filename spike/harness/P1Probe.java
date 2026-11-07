import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import dev.twodog.mush.embed.ChildWindowEmbedding;
import dev.twodog.mush.host.SpikeHost;
import kotlin.Unit;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Canvas;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Windows/JBR integration probe; requires the real host and a renderer. Run through test-p1.ps1. */
public class P1Probe {
    interface NtDll extends Library {
        int NtSuspendProcess(Pointer process);
        int NtResumeProcess(Pointer process);
    }

    interface Kernel32 extends Library {
        Pointer OpenProcess(int access, boolean inherit, int pid);
        boolean CloseHandle(Pointer process);
    }

    // Embedding runs off the EDT; the probe needs it finished, not merely requested.
    private static void awaitEmbedded(ChildWindowEmbedding embedding, long hwnd, Canvas canvas) throws InterruptedException {
        for (long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); !embedding.describe(hwnd, canvas).contains("inParent=true"); Thread.sleep(10))
            if (System.nanoTime() > deadline) throw new AssertionError("The host window never reached the Canvas");
    }

    public static void main(String[] args) throws Exception {
        var nt = Native.load("ntdll", NtDll.class);
        var kernel = Native.load("kernel32", Kernel32.class);
        var child = new AtomicReference<Process>();
        var ready = new CompletableFuture<Long>();
        var host = new SpikeHost(reply -> {
            if (reply.startsWith("hwnd ")) ready.complete(Long.parseLong(reply.substring(5).trim()));
            return Unit.INSTANCE;
        }, (state, message) -> Unit.INSTANCE, command -> {
            try {
                var process = new ProcessBuilder(command).redirectErrorStream(true).start();
                child.set(process);
                return process;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, 250);
        var frame = new JFrame();
        var canvas = new Canvas();
        var embedding = new ChildWindowEmbedding(false);
        var released = new AtomicReference<CompletableFuture<Unit>>();
        var watchdog = Executors.newSingleThreadScheduledExecutor();
        Pointer processHandle = null;
        int exitCode = 1;
        try {
            host.start(List.of(args[0], "--hidden-window", "--resolution", "320x240"));
            long hwnd = ready.get(15, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                frame.add(canvas);
                frame.setSize(320, 240);
                frame.addNotify();
                embedding.embed(hwnd, canvas);
            });
            awaitEmbedded(embedding, hwnd, canvas);

            // Parking must report what really happened: destroying the parent of a child still inside loses the child.
            var healthyPark = new AtomicBoolean();
            var deadPark = new AtomicBoolean(true);
            SwingUtilities.invokeAndWait(() -> {
                healthyPark.set(embedding.park(hwnd));
                deadPark.set(embedding.park(0));
                embedding.embed(hwnd, canvas);
            });
            System.out.printf("Parking: healthy host=%s; no such window=%s%n", healthyPark.get(), deadPark.get());
            if (!healthyPark.get() || deadPark.get())
                throw new AssertionError("Parking misreported whether the window moved");
            awaitEmbedded(embedding, hwnd, canvas);

            processHandle = kernel.OpenProcess(0x800, false, (int) child.get().pid()); // PROCESS_SUSPEND_RESUME
            if (processHandle == null || nt.NtSuspendProcess(processHandle) != 0)
                throw new IllegalStateException("Could not suspend the probe's child process");
            Pointer suspended = processHandle;
            var rescued = new AtomicBoolean();
            var rescue = watchdog.schedule(() -> {
                rescued.set(true);
                nt.NtResumeProcess(suspended);
                child.get().destroyForcibly();
            }, 7, TimeUnit.SECONDS);

            // These wait for the host's message pump, so a suspended host must not get to hold the EDT behind them.
            long nativeStart = System.nanoTime();
            var parked = new AtomicBoolean(true);
            SwingUtilities.invokeAndWait(() -> {
                embedding.embed(hwnd, canvas);
                embedding.resizeBeforeShow(hwnd, canvas, 50);
                embedding.sync(hwnd, canvas);
                parked.set(embedding.park(hwnd));
                embedding.resizeBeforeShow(hwnd, canvas, 50);
            });
            double nativeMs = (System.nanoTime() - nativeStart) / 1e6;
            System.out.printf("Suspended host: EDT embed, resize and park %.1fms; parked=%s%n", nativeMs, parked.get());
            // Parking waits a bounded 2 s, and must report the failure so the caller can end the doomed host.
            if (nativeMs > 3000 || parked.get())
                throw new AssertionError("Foreign window calls blocked the EDT or parking a suspended host claimed success");

            // The writer took it, and a suspended receiver can never drain a write this much larger than the pipe.
            host.send("text " + "A".repeat(2_000_000) + " res://scene.tscn");
            for (long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3); host.pendingCommandCount() != 0; Thread.sleep(5))
                if (System.nanoTime() > deadline) throw new AssertionError("The writer never took the large write");
            long start = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> {
                host.send("stats");
                released.set(embedding.retireCanvas(canvas, host.getTermination()));
                host.stop();
            });
            double edtMs = (System.nanoTime() - start) / 1e6;
            released.get().get(3, TimeUnit.SECONDS);
            rescue.cancel(false);
            System.out.printf("Suspended host + full pipe: EDT close %.1fms; state=%s; "
                    + "processAlive=%s; canvasReleased=%s; watchdogNeeded=%s%n",
                edtMs, host.getState(), child.get().isAlive(), !canvas.isDisplayable(), rescued.get());
            if (child.get().isAlive() || canvas.isDisplayable() || rescued.get())
                throw new AssertionError("Suspended-host shutdown failed");
            exitCode = 0;
        } catch (Throwable failure) {
            failure.printStackTrace();
        } finally {
            if (processHandle != null) nt.NtResumeProcess(processHandle);
            if (child.get() != null) child.get().destroyForcibly().waitFor(3, TimeUnit.SECONDS);
            host.stop();
            SwingUtilities.invokeAndWait(frame::dispose);
            if (processHandle != null) kernel.CloseHandle(processHandle);
            watchdog.shutdownNow();
            // The parking window lives as long as the JVM by design, so AWT would never let this one exit.
            System.exit(exitCode);
        }
    }
}
