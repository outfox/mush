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
        try {
            host.start(List.of(args[0], "--hidden-window", "--resolution", "320x240"));
            long hwnd = ready.get(15, TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(() -> {
                frame.add(canvas);
                frame.setSize(320, 240);
                frame.addNotify();
                embedding.embed(hwnd, canvas);
            });
            processHandle = kernel.OpenProcess(0x800, false, (int) child.get().pid()); // PROCESS_SUSPEND_RESUME
            if (processHandle == null || nt.NtSuspendProcess(processHandle) != 0)
                throw new IllegalStateException("Could not suspend the probe's child process");
            Pointer suspended = processHandle;
            var rescued = new AtomicBoolean();
            var rescue = watchdog.schedule(() -> {
                rescued.set(true);
                nt.NtResumeProcess(suspended);
                child.get().destroyForcibly();
            }, 4, TimeUnit.SECONDS);

            host.send("text res://scene.tscn " + "A".repeat(2_000_000));
            Thread.sleep(100); // The suspended receiver cannot drain this larger-than-pipe write.
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
        } finally {
            if (processHandle != null) nt.NtResumeProcess(processHandle);
            if (child.get() != null) child.get().destroyForcibly().waitFor(3, TimeUnit.SECONDS);
            host.stop();
            SwingUtilities.invokeAndWait(frame::dispose);
            if (processHandle != null) kernel.CloseHandle(processHandle);
            watchdog.shutdownNow();
        }
    }
}
