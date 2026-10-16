package dev.twodog.mush.embed;

import com.sun.jna.Native;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Frame;
import java.awt.Rectangle;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Linux/X11 regression against a foreign process, including SIGSTOP (a debugger suspension). */
public final class X11EmbeddingProbe {
    public static void main(String[] args) {
        try {
            run(args);
            // The production parking window intentionally lives until the IDE exits.
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("child")) {
            var child = new Frame();
            SwingUtilities.invokeAndWait(() -> {
                child.setUndecorated(true);
                child.setSize(320, 240);
                child.addNotify();
                System.out.println(Native.getComponentID(child));
                System.out.flush();
            });
            System.in.read();
            SwingUtilities.invokeAndWait(child::dispose);
            return;
        }
        if (!System.getProperty("os.name").equals("Linux"))
            throw new IllegalStateException("Run this probe on Linux with an X11/XWayland display.");

        var embedding = new ChildWindowEmbedding(false);
        if (embedding.getUnsupportedReason() != null) throw new IllegalStateException(embedding.getUnsupportedReason());
        boolean realHost = args.length == 2 && args[0].equals("--host");
        var command = realHost ? List.of(args[1], "--display-driver", "x11", "--audio-driver", "Dummy", "--hidden-window", "--resolution", "320x240",
            "--mush-scene", "res://scenes/sample_mixed.tscn") : List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-Djna.boot.library.path=" + System.getProperty("jna.boot.library.path", ""),
            "-cp", System.getProperty("java.class.path"),
            X11EmbeddingProbe.class.getName(), "child"
        );
        var process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var frame = new JFrame("Mush X11 regression probe");
        var panel = new JPanel(new BorderLayout());
        var canvas = new Canvas();
        var termination = new CompletableFuture<Void>();
        try {
            var reader = process.inputReader();
            long child = Long.parseLong(CompletableFuture.supplyAsync(() -> {
                try {
                    for (String line; (line = reader.readLine()) != null;) {
                        if (!realHost) return line;
                        if (line.startsWith("mush:hwnd ")) return line.substring("mush:hwnd ".length());
                    }
                    throw new IllegalStateException("Host exited without a native window handle.");
                } catch (Exception e) { throw new RuntimeException(e); }
            }).get(30, TimeUnit.SECONDS));
            onEdt(() -> {
                panel.add(canvas, BorderLayout.CENTER);
                frame.add(panel);
                frame.setSize(500, 400);
                frame.setVisible(true);
            });
            onEdt(() -> {
                embedding.embed(child, canvas);
                embedding.sync(child, canvas);
                assertEmbedded(embedding, child, canvas);
                canvas.setVisible(false);
                embedding.sync(child, canvas);
                require(embedding.describe(child, canvas).contains("visible=false"), "hidden child remained viewable");
                canvas.setVisible(true);
                frame.setSize(750, 500);
                frame.validate();
                embedding.resizeBeforeShow(child, canvas, 50);
                embedding.sync(child, canvas);
                assertEmbedded(embedding, child, canvas);

                // Destroy and recreate the actual Canvas peer, as on editor moves between frames.
                embedding.park(child);
                panel.remove(canvas);
                panel.add(canvas, BorderLayout.CENTER);
                frame.validate();
                embedding.embed(child, canvas);
                embedding.sync(child, canvas);
                assertEmbedded(embedding, child, canvas);
            });

            signal(process, "STOP");
            onEdt(() -> {
                frame.setSize(600, 450);
                frame.validate();
                embedding.resizeBeforeShow(child, canvas, 50);
                embedding.sync(child, canvas);
                assertEmbedded(embedding, child, canvas);
            });
            var released = new CompletableFuture<CompletableFuture<kotlin.Unit>>();
            onEdt(() -> released.complete(embedding.retireCanvas(canvas, termination)));
            require(canvas.isDisplayable(), "Canvas was destroyed before host termination");
            require(!released.get().isDone(), "Canvas retirement waited for no host");
            signal(process, "CONT");
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            onEdt(() -> {
                // A host can die between its HWND/XID reply and any embedding request.
                embedding.sync(child, canvas);
                embedding.park(child);
            });
            termination.complete(null);
            released.get().get(5, TimeUnit.SECONDS);
            require(!canvas.isDisplayable(), "Canvas peer was not released after host exit");
            System.out.println("PASS: embed, hide, resize, peer recreation, suspended host, dead window, retirement");
        } finally {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            termination.complete(null);
            onEdt(frame::dispose);
        }
    }

    private static void assertEmbedded(ChildWindowEmbedding embedding, long child, Canvas canvas) {
        Rectangle size = embedding.clientSize(canvas);
        String state = embedding.describe(child, canvas);
        require(state.contains("inParent=true") && state.contains("visible=true") &&
            state.contains("size=" + size.width + "x" + size.height), "Unexpected native window state: " + state);
    }

    private static void signal(Process process, String signal) throws Exception {
        var sender = new ProcessBuilder("kill", "-" + signal, Long.toString(process.pid())).start();
        require(sender.waitFor(5, TimeUnit.SECONDS) && sender.exitValue() == 0, "signal failed");
    }

    private static void onEdt(Runnable action) throws Exception {
        CompletableFuture.runAsync(() -> {
            try { SwingUtilities.invokeAndWait(action); } catch (Exception e) { throw new RuntimeException(e); }
        }).get(5, TimeUnit.SECONDS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
