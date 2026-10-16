package dev.twodog.mush.embed;

import com.sun.jna.Pointer;
import dev.twodog.mush.embed.gpu.GpuFrameMessage;
import dev.twodog.mush.embed.gpu.LinuxGpuBridge;
import dev.twodog.mush.embed.gpu.JbrWaylandSurface;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

/** Actual Godot Vulkan memory is received over SCM_RIGHTS and sampled in another process's EGL context. */
public final class GpuSharingProbe {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Pass the Linux GpuShareProbe apphost path and optional --wayland.");
        boolean wayland = args.length == 2 && args[1].equals("--wayland");
        var bridge = new LinuxGpuBridge();
        var directory = Files.createTempDirectory("mush-gpu-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        var socket = directory.resolve("frames.sock");
        int server = bridge.listen(socket.toString()), channel = -1;
        Pointer gpu = null;
        Process producer = null;
        var window = new JFrame[1];
        var surface = new JbrWaylandSurface[1];
        try {
            if (wayland) {
                SwingUtilities.invokeAndWait(() -> {
                    window[0] = new JFrame("Mush native Wayland GPU-sharing spike");
                    window[0].setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    window[0].add(new JLabel("Godot → exported Vulkan memory → JBR subsurface", JLabel.CENTER), java.awt.BorderLayout.NORTH);
                    window[0].setSize(520, 220);
                    window[0].setVisible(true);
                    surface[0] = new JbrWaylandSurface(bridge, window[0], 220, 70);
                });
                var holder = new Pointer[1];
                surface[0].withAwtLock(() -> holder[0] = bridge.create(surface[0].getDisplay(), surface[0].getSurface(), 64, 64));
                gpu = holder[0];
            } else gpu = bridge.create(0, 0, 64, 64);
            System.out.println("GPU consumer: " + bridge.info(gpu));
            producer = new ProcessBuilder(args[0], "--socket", socket.toString()).inheritIO().start();
            channel = bridge.accept(server, 30_000);
            long generation = 0, lastSequence = 0;
            int frames = 0, width = 64, height = 64;
            boolean resized = false;
            while (frames < 12) {
                var packet = bridge.receive(channel, 10_000);
                require(packet != null, "Timed out waiting for a GPU frame");
                var message = packet.getMessage();
                if (message.getKind() == GpuFrameMessage.RESOURCE) {
                    require(message.getGeneration() > generation, "Texture generation did not advance");
                    generation = message.getGeneration(); width = message.getWidth(); height = message.getHeight();
                    bridge.importResource(gpu, packet);
                } else {
                    require(message.getKind() == GpuFrameMessage.FRAME && message.getGeneration() == generation &&
                        message.getSequence() > lastSequence, "Unexpected or stale frame");
                    lastSequence = message.getSequence();
                    bridge.draw(gpu);
                    // glReadPixels only exists in this verification path; the importer/presenter copies no pixels to Java.
                    boolean red = message.getSequence() % 2 == 1;
                    expect(bridge.sample(gpu, 16, 48), red ? 255 : 0, red ? 0 : 255, red ? 0 : 255, "top left frame update");
                    expect(bridge.sample(gpu, 48, 48), 0, 255, 0, "top right");
                    expect(bridge.sample(gpu, 16, 16), 0, 0, 255, "bottom left");
                    expect(bridge.sample(gpu, 48, 16), 255, 255, 255, "bottom right");
                    if (wayland) {
                        Pointer current = gpu;
                        surface[0].withAwtLock(() -> bridge.present(current));
                    }
                    if (frames == 0) {
                        // Holding a lease must leave no further frame queued, even while Godot continues iterating.
                        require(bridge.receive(channel, 150) == null, "Producer overwrote or queued a leased frame");
                        signal(producer, "STOP");
                        try {
                            bridge.draw(gpu); // The last completed GPU image remains usable with every producer thread suspended.
                            CompletableFuture.runAsync(() -> {
                                try { SwingUtilities.invokeAndWait(() -> {}); }
                                catch (Exception e) { throw new RuntimeException(e); }
                            }).get(2, TimeUnit.SECONDS);
                        } finally { signal(producer, "CONT"); }
                    }
                    bridge.send(channel, control(GpuFrameMessage.RELEASE, generation, lastSequence, 0, 0));
                    frames++;
                    if (frames == 6) {
                        bridge.send(channel, control(GpuFrameMessage.RESIZE, generation, lastSequence, 80, 48));
                    }
                    resized |= generation > 1 && width == 80 && height == 48;
                }
            }
            require(resized, "Resized external allocation was never presented");
            bridge.send(channel, control(GpuFrameMessage.STOP, generation, lastSequence, 0, 0));
            require(producer.waitFor(5, TimeUnit.SECONDS) && producer.exitValue() == 0, "Godot producer did not stop cleanly");
            System.out.println("PASS: cross-process Vulkan/EGL sharing, changing colors/orientation, frame leases, suspended producer, resizing and clean shutdown");
        } finally {
            if (producer != null && producer.isAlive()) producer.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            if (gpu != null) bridge.destroy(gpu);
            if (surface[0] != null) SwingUtilities.invokeAndWait(surface[0]::close);
            if (window[0] != null) SwingUtilities.invokeAndWait(window[0]::dispose);
            bridge.closeFd(channel); bridge.closeFd(server);
            Files.deleteIfExists(socket); Files.deleteIfExists(directory);
        }
    }

    private static GpuFrameMessage control(int kind, long generation, long sequence, int width, int height) {
        return new GpuFrameMessage(kind, generation, sequence, width, height, 0, new byte[16], new byte[16]);
    }
    private static void signal(Process process, String name) throws Exception {
        var signal = new ProcessBuilder("kill", "-" + name, Long.toString(process.pid())).start();
        require(signal.waitFor(5, TimeUnit.SECONDS) && signal.exitValue() == 0, "Could not send " + name + " to probe producer");
    }
    private static void expect(byte[] p, int r, int g, int b, String name) {
        require(Math.abs((p[0] & 255) - r) <= 4 && Math.abs((p[1] & 255) - g) <= 4 && Math.abs((p[2] & 255) - b) <= 4,
            "Wrong " + name + " pixel: " + (p[0] & 255) + "," + (p[1] & 255) + "," + (p[2] & 255));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
