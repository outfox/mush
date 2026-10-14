import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Embedding spike harness: a Swing window on JetBrains Runtime with the scene text on the left and an AWT Canvas
 * on the right, into which the spike host's Godot window is embedded.
 * Mode a: Godot's way, an owned popup (--wid) kept over the Canvas rect. Mode b: SetParent as a WS_CHILD.
 * --auto runs a scripted measurement and writes screenshots, then exits.
 */
public final class Harness {
    private final Options options;
    private final JFrame frame = new JFrame();
    private final JTextArea text = new JTextArea();
    // AWT destroys and recreates the Canvas HWND whenever the component leaves and re-enters a displayable
    // hierarchy. A WS_CHILD dies with its parent, so the Godot window moves out first and back in afterwards.
    private final Canvas canvas = new Canvas() {
        @Override public void removeNotify() {
            park();
            super.removeNotify();
        }

        @Override public void addNotify() {
            super.addNotify();
            if (host.hwnd != 0)
                SwingUtilities.invokeLater(() -> embed(host.hwnd));
        }
    };
    private final JPanel viewport = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ");
    private final Host host = new Host();
    private final AtomicLong maxEdtGapNanos = new AtomicLong();
    private long lastTick = System.nanoTime();
    private Rectangle lastRect;
    private boolean hostShown;

    private Harness(Options options) {
        this.options = options;
    }

    public static void main(String[] args) throws Exception {
        var options = Options.parse(args);
        var harness = new Harness(options);
        SwingUtilities.invokeAndWait(harness::buildUi);
        harness.startHost();
        if (options.autoDir != null)
            harness.runAuto();
    }

    // UI

    private void buildUi() {
        frame.setTitle("mush harness - mode " + options.mode + (options.detach ? " detach" : "") + (options.sync ? " sync" : ""));
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            // The host must release its window before the Canvas HWND that parents it is destroyed.
            @Override public void windowClosing(WindowEvent e) {
                park();
                host.stop();
                frame.dispose();
                System.out.println("RESULT host_errors = " + host.errors.get());
                System.exit(0);
            }
        });

        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        text.setText(readScene());
        var debounce = new Timer(120, e -> sendText());
        debounce.setRepeats(false);
        text.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { if (options.autoDir == null) debounce.restart(); }
            @Override public void removeUpdate(DocumentEvent e) { if (options.autoDir == null) debounce.restart(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });

        canvas.setBackground(Color.DARK_GRAY);
        canvas.setIgnoreRepaint(true);
        viewport.add(canvas, BorderLayout.CENTER);

        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JScrollPane(text), viewport);
        split.setResizeWeight(0.35);

        var toolbar = new JToolBar();
        toolbar.add(button("2D", () -> host.send("load " + options.scene)));
        toolbar.add(button("3D", () -> host.send("load res://scenes/sample_3d.tscn")));
        toolbar.add(button("Overlay", this::toggleOverlay));
        toolbar.add(button("Popup", this::showPopup));
        toolbar.add(button("Dialog", this::showDialog));
        toolbar.add(button("Stall 3s", () -> { maxEdtGapNanos.set(0); host.send("stall 3000"); }));
        toolbar.add(button("Re-home", this::rehome));

        frame.add(toolbar, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(status, BorderLayout.SOUTH);
        frame.setSize(1400, 820);
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        frame.toFront();

        // EDT heartbeat: the largest gap shows whether the UI thread froze.
        new Timer(10, e -> {
            var now = System.nanoTime();
            maxEdtGapNanos.accumulateAndGet(now - lastTick, Math::max);
            lastTick = now;
        }).start();

        // Godot's editor polls the target rect every 100 ms in addition to reacting to events.
        new Timer(100, e -> sync()).start();
        var listener = new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { sync(); }
            @Override public void componentMoved(ComponentEvent e) { sync(); }
            @Override public void componentShown(ComponentEvent e) { sync(); }
            @Override public void componentHidden(ComponentEvent e) { sync(); }
        };
        canvas.addComponentListener(listener);
        frame.addComponentListener(listener);
    }

    private static JButton button(String label, Runnable action) {
        var button = new JButton(label);
        button.addActionListener(e -> action.run());
        return button;
    }

    private String readScene() {
        try {
            return Files.readString(options.file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Host lifecycle and embedding

    private void startHost() throws Exception {
        var command = new ArrayList<String>(List.of(options.hostExe.toString(), "--mush-scene", options.scene));
        var rect = new Rectangle[1];
        var frameHwnd = new long[1];
        SwingUtilities.invokeAndWait(() -> {
            frameHwnd[0] = Win32.hwnd(frame);
            rect[0] = Win32.windowRect(Win32.hwnd(canvas));
        });
        var r = rect[0];
        if (options.mode == 'a') {
            command.addAll(List.of("--wid", Long.toString(frameHwnd[0]),
                "--position", r.x + "," + r.y, "--resolution", r.width + "x" + r.height));
        } else {
            command.addAll(List.of("--hidden-window", "--resolution", r.width + "x" + r.height));
        }
        host.onHwnd = hwnd -> SwingUtilities.invokeLater(() -> embed(hwnd));
        if (options.autoDir == null)
            host.onReply = reply -> {
                if (reply.startsWith("stalled"))
                    log(String.format("stall over, max EDT gap %.0f ms", maxEdtGapNanos.get() / 1e6));
                else if (reply.startsWith("loaded") || reply.startsWith("error"))
                    log(reply);
            };
        host.start(command);
    }

    private void park() {
        var hwnd = host.hwnd;
        if (hwnd == 0)
            return;
        Win32.showWindow(hwnd, Win32.SW_HIDE);
        hostShown = false;
        lastRect = null;
        if (options.mode == 'b') {
            var style = Win32.getWindowLongPtr(hwnd, Win32.GWL_STYLE);
            Win32.setWindowLongPtr(hwnd, Win32.GWL_STYLE, (style & ~Win32.WS_CHILD) | Win32.WS_POPUP);
            Win32.setParent(hwnd, 0);
        }
    }

    // Simulates an editor tab moving to another window: the Canvas peer is destroyed and recreated.
    private void rehome() {
        viewport.remove(canvas);
        viewport.revalidate();
        viewport.add(canvas, BorderLayout.CENTER);
        viewport.revalidate();
    }

    private void embed(long hwnd) {
        Win32.allowSetForegroundWindow(host.pid());
        if (options.mode == 'b') {
            var style = Win32.getWindowLongPtr(hwnd, Win32.GWL_STYLE);
            style &= ~(Win32.WS_POPUP | Win32.WS_CAPTION | Win32.WS_THICKFRAME | Win32.WS_SYSMENU
                | Win32.WS_MINIMIZEBOX | Win32.WS_MAXIMIZEBOX);
            style |= Win32.WS_CHILD | Win32.WS_CLIPSIBLINGS | Win32.WS_CLIPCHILDREN;
            Win32.setWindowLongPtr(hwnd, Win32.GWL_STYLE, style);
            Win32.setParent(hwnd, Win32.hwnd(canvas));
            if (options.detach) {
                var hostThread = Win32.windowThread(hwnd);
                var canvasThread = Win32.windowThread(Win32.hwnd(canvas));
                log("detach input queues: " + Win32.attachThreadInput(hostThread, canvasThread, false));
            }
        }
        lastRect = null;
        hostShown = false;
        sync();
    }

    private void sync() {
        var hwnd = host.hwnd;
        if (hwnd == 0 || !canvas.isDisplayable())
            return;

        var visible = canvas.isShowing() && (frame.getExtendedState() & Frame.ICONIFIED) == 0;
        var rect = options.mode == 'a' ? Win32.windowRect(Win32.hwnd(canvas)) : Win32.clientRect(Win32.hwnd(canvas));
        visible &= rect.width > 100 && rect.height > 100;

        if (visible && !rect.equals(lastRect)) {
            var flags = Win32.SWP_NOZORDER | Win32.SWP_NOACTIVATE | (options.sync ? 0 : Win32.SWP_ASYNCWINDOWPOS);
            if (options.mode == 'b')
                flags |= Win32.SWP_FRAMECHANGED;
            Win32.setWindowPos(hwnd, 0, rect.x, rect.y, rect.width, rect.height, flags);
            lastRect = rect;
        }
        if (visible != hostShown) {
            Win32.showWindow(hwnd, visible ? Win32.SW_SHOWNA : Win32.SW_HIDE);
            hostShown = visible;
        }
    }

    private void sendText() {
        try {
            host.send("text " + options.scene + " " + base64(text.getText()));
        } catch (UncheckedIOException e) {
            log("text failed: " + e.getMessage());
        }
    }

    // Overlap probes

    private JComponent overlay;

    private void toggleOverlay() {
        var layers = frame.getLayeredPane();
        if (overlay != null) {
            layers.remove(overlay);
            overlay = null;
        } else {
            overlay = new JPanel();
            overlay.setBackground(Color.MAGENTA);
            var at = SwingUtilities.convertPoint(canvas, 40, 40, layers);
            overlay.setBounds(at.x, at.y, 260, 300);
            layers.add(overlay, JLayeredPane.POPUP_LAYER);
        }
        layers.repaint();
    }

    private JPopupMenu popup;

    private void showPopup() {
        popup = new JPopupMenu();
        for (var i = 1; i <= 8; i++)
            popup.add(new JMenuItem("Completion item " + i));
        popup.show(canvas, canvas.getWidth() / 2, canvas.getHeight() / 2);
        var heavy = SwingUtilities.getWindowAncestor(popup) != frame;
        log("popup heavyweight=" + heavy);
    }

    private JDialog dialog;

    private void showDialog() {
        dialog = new JDialog(frame, "probe", false);
        var content = new JPanel();
        content.setBackground(Color.ORANGE);
        dialog.setContentPane(content);
        dialog.setSize(300, 200);
        var at = canvas.getLocationOnScreen();
        dialog.setLocation(at.x + canvas.getWidth() / 2, at.y + 60);
        dialog.setVisible(true);
    }

    private void log(String message) {
        System.out.println("[harness] " + message);
        SwingUtilities.invokeLater(() -> status.setText(message));
    }

    // Scripted measurement

    private void runAuto() throws Exception {
        var out = options.autoDir;
        Files.createDirectories(out);
        var tag = "mode-" + options.mode + (options.detach ? "-detach" : "") + (options.sync ? "-sync" : "");
        var results = new LinkedHashMap<String, Object>();

        // Windows refuses foreground activation to background processes; screenshots need the frame on top.
        SwingUtilities.invokeAndWait(() -> frame.setAlwaysOnTop(true));
        host.await("hwnd", 60_000);
        results.put("first_load", host.await("loaded", 60_000));
        Thread.sleep(1500);
        capture(out, tag + "-1-initial");

        var original = readScene();
        var latencies = new ArrayList<Double>();
        for (var i = 1; i <= 10; i++) {
            var edited = original.replace("text = \"mush sample 2D\"", "text = \"edit " + i + "\"");
            var start = System.nanoTime();
            host.send("text " + options.scene + " " + base64(edited));
            host.await("loaded", 10_000);
            latencies.add((System.nanoTime() - start) / 1e6);
            Thread.sleep(100);
        }
        results.put("text_reload_ms", summarize(latencies));
        Thread.sleep(500);
        capture(out, tag + "-2-edited");

        SwingUtilities.invokeAndWait(this::toggleOverlay);
        Thread.sleep(600);
        capture(out, tag + "-3-overlay");
        SwingUtilities.invokeAndWait(this::toggleOverlay);

        SwingUtilities.invokeAndWait(this::showPopup);
        Thread.sleep(600);
        capture(out, tag + "-4-popup");
        SwingUtilities.invokeAndWait(() -> popup.setVisible(false));

        SwingUtilities.invokeAndWait(this::showDialog);
        Thread.sleep(600);
        capture(out, tag + "-5-dialog");
        SwingUtilities.invokeAndWait(() -> dialog.dispose());
        Thread.sleep(300);

        // Resize the frame while the host is stalled, as if it sat on a breakpoint.
        var size = frame.getSize();
        maxEdtGapNanos.set(0);
        host.send("stall 3000");
        var resizeStart = System.nanoTime();
        var resizeCalls = new ArrayList<Double>();
        for (var i = 0; i < 16; i++) {
            var width = size.width + (i % 2 == 0 ? 40 : 0);
            var t0 = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> frame.setSize(width, size.height));
            resizeCalls.add((System.nanoTime() - t0) / 1e6);
            Thread.sleep(150);
        }
        host.await("stalled", 15_000);
        results.put("stall_resize_loop_ms", (System.nanoTime() - resizeStart) / 1e6);
        results.put("stall_resize_call_ms", summarize(resizeCalls));
        results.put("stall_max_edt_gap_ms", maxEdtGapNanos.get() / 1e6);
        Thread.sleep(1000);
        capture(out, tag + "-6-after-stall");

        host.send("load res://scenes/sample_3d.tscn");
        results.put("load_3d", host.await("loaded", 10_000));
        Thread.sleep(1500);
        capture(out, tag + "-7-3d");

        SwingUtilities.invokeAndWait(this::rehome);
        Thread.sleep(1500);
        capture(out, tag + "-8-rehomed");
        results.put("host_errors_before_close", host.errors.get());

        for (var entry : results.entrySet())
            System.out.println("RESULT " + tag + " " + entry.getKey() + " = " + entry.getValue());
        // Close the way a user does, so shutdown ordering is exercised too.
        SwingUtilities.invokeLater(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)));
    }

    private static String base64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String summarize(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        return String.format("min=%.1f median=%.1f max=%.1f", sorted.getFirst(), sorted.get(sorted.size() / 2),
            sorted.getLast());
    }

    private void capture(Path dir, String name) throws Exception {
        var bounds = new Rectangle[1];
        SwingUtilities.invokeAndWait(() -> bounds[0] = frame.getBounds());
        BufferedImage image = new Robot().createScreenCapture(bounds[0]);
        ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
    }

    // Host process: line commands in, "mush:" replies out.

    private static final class Host {
        private Process process;
        private Thread reader;
        private BufferedWriter input;
        private final BlockingQueue<String> replies = new LinkedBlockingQueue<>();
        volatile long hwnd;
        final java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.LongConsumer onHwnd = h -> { };
        java.util.function.Consumer<String> onReply = r -> { };

        void start(List<String> command) throws IOException {
            System.out.println("[harness] " + String.join(" ", command));
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
            reader = new Thread(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (!line.startsWith("mush:")) {
                            if (line.contains("ERROR"))
                                errors.incrementAndGet();
                            if (line.contains("ERROR") || line.contains("WARNING"))
                                System.out.println("[host] " + line);
                            continue;
                        }
                        var reply = line.substring(5);
                        System.out.println("[host] " + reply);
                        if (reply.startsWith("hwnd ")) {
                            hwnd = Long.parseLong(reply.substring(5).trim());
                            onHwnd.accept(hwnd);
                        }
                        onReply.accept(reply);
                        replies.add(reply);
                    }
                } catch (IOException ignored) {
                }
            }, "host stdout");
            reader.setDaemon(true);
            reader.start();
        }

        long pid() {
            return process.pid();
        }

        synchronized void send(String command) {
            try {
                input.write(command);
                input.newLine();
                input.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String await(String kind, long timeoutMs) throws InterruptedException {
            var deadline = System.currentTimeMillis() + timeoutMs;
            while (true) {
                var reply = replies.poll(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (reply == null)
                    throw new IllegalStateException("timed out waiting for " + kind);
                if (reply.startsWith(kind))
                    return reply.substring(kind.length()).trim();
                if (reply.startsWith("error"))
                    System.out.println("[harness] host error while waiting for " + kind + ": " + reply);
            }
        }

        void stop() {
            if (process == null)
                return;
            try {
                send("quit");
                if (!process.waitFor(5, TimeUnit.SECONDS))
                    process.destroy();
                // Drain the host's last lines before the JVM exits.
                reader.join(2000);
            } catch (Exception e) {
                process.destroy();
            }
        }
    }

    // Win32 through the FFM API; HWNDs come from the AWT peers.

    private static final class Win32 {
        static final int GWL_STYLE = -16;
        static final long WS_CHILD = 0x40000000L, WS_POPUP = 0x80000000L, WS_CAPTION = 0x00C00000L;
        static final long WS_THICKFRAME = 0x00040000L, WS_SYSMENU = 0x00080000L;
        static final long WS_MINIMIZEBOX = 0x00020000L, WS_MAXIMIZEBOX = 0x00010000L;
        static final long WS_CLIPSIBLINGS = 0x04000000L, WS_CLIPCHILDREN = 0x02000000L;
        static final int SWP_NOZORDER = 0x4, SWP_NOACTIVATE = 0x10, SWP_FRAMECHANGED = 0x20;
        static final int SWP_ASYNCWINDOWPOS = 0x4000;
        static final int SW_HIDE = 0, SW_SHOWNA = 8;

        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32", Arena.global());
        private static final ValueLayout.OfLong P = ValueLayout.JAVA_LONG;
        private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;
        private static final AddressLayout A = ValueLayout.ADDRESS;

        private static final MethodHandle SET_WINDOW_POS = fn("SetWindowPos", FunctionDescriptor.of(I, P, P, I, I, I, I, I));
        private static final MethodHandle SHOW_WINDOW = fn("ShowWindow", FunctionDescriptor.of(I, P, I));
        private static final MethodHandle SET_PARENT = fn("SetParent", FunctionDescriptor.of(P, P, P));
        private static final MethodHandle GET_LONG = fn("GetWindowLongPtrW", FunctionDescriptor.of(P, P, I));
        private static final MethodHandle SET_LONG = fn("SetWindowLongPtrW", FunctionDescriptor.of(P, P, I, P));
        private static final MethodHandle THREAD_ID = fn("GetWindowThreadProcessId", FunctionDescriptor.of(I, P, A));
        private static final MethodHandle ATTACH = fn("AttachThreadInput", FunctionDescriptor.of(I, I, I, I));
        private static final MethodHandle WINDOW_RECT = fn("GetWindowRect", FunctionDescriptor.of(I, P, A));
        private static final MethodHandle CLIENT_RECT = fn("GetClientRect", FunctionDescriptor.of(I, P, A));
        private static final MethodHandle ALLOW_FOREGROUND = fn("AllowSetForegroundWindow", FunctionDescriptor.of(I, I));

        private static MethodHandle fn(String name, FunctionDescriptor descriptor) {
            return LINKER.downcallHandle(USER32.find(name).orElseThrow(), descriptor);
        }

        static long hwnd(Component component) {
            try {
                Field peerField = Component.class.getDeclaredField("peer");
                peerField.setAccessible(true);
                var peer = peerField.get(component);
                Method getHWnd = peer.getClass().getMethod("getHWnd");
                getHWnd.setAccessible(true);
                return (long) getHWnd.invoke(peer);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        static void setWindowPos(long hwnd, long after, int x, int y, int w, int h, int flags) {
            try { var ignored = (int) SET_WINDOW_POS.invokeExact(hwnd, after, x, y, w, h, flags); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }

        static void showWindow(long hwnd, int command) {
            try { var ignored = (int) SHOW_WINDOW.invokeExact(hwnd, command); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }

        static void setParent(long child, long parent) {
            try { var ignored = (long) SET_PARENT.invokeExact(child, parent); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }

        static long getWindowLongPtr(long hwnd, int index) {
            try { return (long) GET_LONG.invokeExact(hwnd, index); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }

        static void setWindowLongPtr(long hwnd, int index, long value) {
            try { var ignored = (long) SET_LONG.invokeExact(hwnd, index, value); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }

        static int windowThread(long hwnd) {
            try { return (int) THREAD_ID.invokeExact(hwnd, MemorySegment.NULL); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }

        static boolean attachThreadInput(int from, int to, boolean attach) {
            try { return (int) ATTACH.invokeExact(from, to, attach ? 1 : 0) != 0; }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }

        static void allowSetForegroundWindow(long pid) {
            try { var ignored = (int) ALLOW_FOREGROUND.invokeExact((int) pid); }
            catch (Throwable t) { throw new IllegalStateException(t); }
        }

        static Rectangle windowRect(long hwnd) {
            return rect(WINDOW_RECT, hwnd);
        }

        static Rectangle clientRect(long hwnd) {
            return rect(CLIENT_RECT, hwnd);
        }

        private static Rectangle rect(MethodHandle getter, long hwnd) {
            try (var arena = Arena.ofConfined()) {
                var r = arena.allocate(16);
                var ignored = (int) getter.invokeExact(hwnd, r);
                int left = r.get(I, 0), top = r.get(I, 4), right = r.get(I, 8), bottom = r.get(I, 12);
                return new Rectangle(left, top, right - left, bottom - top);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }

    private record Options(char mode, boolean detach, boolean sync, Path hostExe, String scene, Path file, Path autoDir) {
        static Options parse(String[] args) {
            char mode = 'a';
            boolean detach = false, sync = false;
            Path hostExe = null, file = null, autoDir = null;
            String scene = "res://scenes/sample_2d.tscn";
            for (var i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--mode" -> mode = args[++i].charAt(0);
                    case "--detach" -> detach = true;
                    case "--sync" -> sync = true;
                    case "--host" -> hostExe = Path.of(args[++i]);
                    case "--scene" -> scene = args[++i];
                    case "--file" -> file = Path.of(args[++i]);
                    case "--auto" -> autoDir = Path.of(args[++i]);
                    default -> throw new IllegalArgumentException("unknown argument " + args[i]);
                }
            }
            return new Options(mode, detach, sync, Objects.requireNonNull(hostExe, "--host"),
                scene, Objects.requireNonNull(file, "--file"), autoDir);
        }
    }
}
