using System.Text;
using Godot;
using Engine = twodog.Engine;

namespace Mush.Spike;

/// <summary>
/// Spike host: boots the game project, shows one scene, and takes line commands on stdin.
/// Replies on stdout are prefixed with "mush:" to separate them from Godot's log.
/// </summary>
internal static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        var godotArgs = new List<string>();
        string? initialScene = null;
        for (var i = 0; i < args.Length; i++)
        {
            if (args[i] == "--mush-scene" && i + 1 < args.Length)
                initialScene = args[++i];
            else
                godotArgs.Add(args[i]);
        }

        // The viewport is embedded in the IDE, so this process should not look like an app of its own. This has
        // to be in place before the engine exists: its macOS startup promotes the process to a Dock-visible app,
        // and by the time Start() returns the tile has already been on screen for half a second.
        var dockFailure = MacLayerPublisher.PinToAccessory();

        using var engine = new Engine("mush", args: godotArgs.ToArray());
        engine.Start();
        if (dockFailure != null)
            Emit("dock", dockFailure);

        // Diagnostics: blue is Godot's frame, grey is the IDE behind it.
        RenderingServer.SetDefaultClearColor(new Color(0.15f, 0.25f, 0.45f));

        var tree = engine.Tree;
        var loader = new SceneLoader(tree);
        var camera = new ViewCamera(tree.Root);
        // Windows embeds this window directly, so it gets the HWND; macOS can only host a published layer, so it
        // gets a CAContext id instead. Both reach the IDE as the one handle its embedding knows how to use.
        var contextId = MacLayerPublisher.Publish(out var layerDiagnostic);
        Emit("layer", layerDiagnostic);
        Emit("hwnd", contextId?.ToString()
                     ?? DisplayServer.WindowGetNativeHandle(DisplayServer.HandleType.WindowHandle).ToString());
        if (contextId != null)
            SetPresentingOffscreen(true);

        var commands = new PendingCommands();
        if (initialScene != null)
            commands.Enqueue($"load {initialScene}");
        var reader = new Thread(() =>
        {
            while (Console.In.ReadLine() is { } line)
            {
                if (!commands.Enqueue(line))
                    Emit("error", "host command queue is full or closing");
            }
            commands.Enqueue("quit");
        }) { IsBackground = true, Name = "mush stdin" };
        reader.Start();

        while (!engine.Iteration())
        {
            // The engine re-registers the process as a foreground app through TransformProcessType, on a run-loop
            // delay and again whenever its window is activated, so the policy has to be held rather than set once.
            MacLayerPublisher.KeepOutOfDock();

            // Leave time for a frame even when producers keep submitting commands.
            for (var i = 0; i < 16 && commands.TryDequeue(out var line); i++)
            {
                if (!Execute(line, loader, camera))
                {
                    engine.RequestQuit();
                    break;
                }
            }
        }

        SetPresentingOffscreen(false);
        return 0;
    }

    private static bool Execute(string line, SceneLoader loader, ViewCamera camera)
    {
        var parts = line.Split(' ', 3, StringSplitOptions.RemoveEmptyEntries);
        if (parts.Length == 0)
            return true;

        try
        {
            switch (parts[0])
            {
                case "quit":
                    return false;
                case "load" when parts.Length == 2:
                    Report(parts[1], loader.Load(parts[1], text: null), loader, camera);
                    break;
                // Unsaved document text travels in-band as base64, so no temp file can race the editor.
                case "text" when parts.Length == 3:
                    Report(parts[1], loader.Load(parts[1], Encoding.UTF8.GetString(Convert.FromBase64String(parts[2]))), loader, camera);
                    break;
                case "view":
                    camera.Configure(ViewSettings.Parse(line));
                    Emit("view", camera.Settings.Command[5..]);
                    break;
                // A hosted layer gives the IDE no way to resize this window, so it sends the size instead.
                case "resolution" when parts.Length == 2:
                    Resize(parts[1]);
                    break;
                // Blocks the engine thread like a debugger breakpoint would.
                case "stall" when parts.Length == 2:
                    Thread.Sleep(int.Parse(parts[1]));
                    Emit("stalled", parts[1]);
                    break;
                case "count":
                    Emit("count", CountNodes(loader.Current).ToString());
                    break;
                case "orphans":
                    Node.PrintOrphanNodes();
                    Emit("orphans", "printed");
                    break;
                // The two states of the render-loop gate below, so one process can show the difference.
                case "present" when parts.Length == 2:
                    SetPresentingOffscreen(parts[1] == "on");
                    Emit("present", DisplayServer.HasAdditionalOutputs() ? "on" : "off");
                    break;
                // Takes the window off screen the way AppKit does, to prove frames keep coming.
                case "minimize":
                    DisplayServer.WindowSetMode(DisplayServer.WindowMode.Minimized);
                    Emit("minimize", DisplayServer.WindowGetMode().ToString());
                    break;
                case "restore":
                    DisplayServer.WindowSetMode(DisplayServer.WindowMode.Windowed);
                    Emit("restore", DisplayServer.WindowGetMode().ToString());
                    break;
                case "stats":
                    Emit("stats", Stats(loader.Current, camera.Settings));
                    break;
                // Just the render-loop gate, with none of Stats' readback: reading the viewport texture races
                // presentation and aborts the Metal backend, so progress cannot be measured through "stats".
                // Cost of getting a frame to the CPU, which is what a streamed viewport pays per frame.
                case "readback" when parts.Length == 2:
                    Emit("readback", Readback(loader.Current, int.Parse(parts[1])));
                    break;
                // Milestone 4's open question: does one engine give a second scratch its own OS window, with its
                // own native handle to embed and its own world to render? A SubViewport cannot be reparented.
                case "scratch":
                    Emit("scratch", SecondWindow());
                    break;
                case "frames":
                    Emit("frames", $"frames={Godot.Engine.GetFramesDrawn()} " +
                                   $"canDraw={DisplayServer.WindowCanDraw()} " +
                                   $"outputs={DisplayServer.HasAdditionalOutputs()} " +
                                   $"mode={DisplayServer.WindowGetMode()} " +
                                   $"rect={DisplayServer.WindowGetPosition()}+{DisplayServer.WindowGetSize()}");
                    break;
                default:
                    Emit("error", $"unknown command: {line}");
                    break;
            }
        }
        catch (Exception e)
        {
            Emit("error", e.Message);
        }

        return true;
    }

    /// <summary>
    /// Holds the object that keeps the engine presenting while its window is off screen.
    /// DisplayServer records only an instance id and takes no reference, so this has to outlive the engine.
    /// </summary>
    private static GodotObject? offscreenOutput;

    /// <summary>
    /// Declares that something other than a window consumes the frames.
    ///
    /// <c>Main::iteration</c> draws only when <c>can_any_window_draw()</c> or <c>has_additional_outputs()</c>
    /// holds, and on macOS a window is drawable only while AppKit reports it unminimized and unoccluded. A hosted
    /// layer makes that test answer the wrong question: the IDE shows the pixels and this window never does, so
    /// the moment Rider covers it the engine stops rendering the viewport the user is looking at. Registering an
    /// output is the engine's own escape hatch for that -- the same one XR and the editor's game embedding use.
    /// </summary>
    private static void SetPresentingOffscreen(bool presenting)
    {
        if (presenting == (offscreenOutput != null))
            return;
        if (presenting)
        {
            offscreenOutput = new GodotObject();
            DisplayServer.RegisterAdditionalOutput(offscreenOutput);
            return;
        }
        DisplayServer.UnregisterAdditionalOutput(offscreenOutput!);
        // Not Dispose: a plain Object is not reference counted, so only Free releases it and Godot would
        // otherwise report it as a leaked ObjectDB instance at exit.
        offscreenOutput!.Free();
        offscreenOutput = null;
    }

    /// <summary>Times the viewport readback a streamed viewport would do per frame, over <paramref name="iterations"/> samples.</summary>
    private static string Readback(Node? scene, int iterations)
    {
        var viewport = scene?.GetViewport();
        if (viewport is null)
            return "no viewport";
        var size = viewport.GetVisibleRect().Size;
        var watch = new System.Diagnostics.Stopwatch();
        var samples = new List<double>(iterations);
        long bytes = 0;
        for (var i = 0; i < iterations; i++)
        {
            watch.Restart();
            using var image = viewport.GetTexture().GetImage();
            watch.Stop();
            if (image is null || image.IsEmpty())
                return "empty image";
            bytes = image.GetData().Length;
            samples.Add(watch.Elapsed.TotalMilliseconds);
        }
        samples.Sort();
        return $"{size.X}x{size.Y} {bytes / 1024}KiB n={iterations} " +
               $"min={samples[0]:F2}ms median={samples[samples.Count / 2]:F2}ms max={samples[^1]:F2}ms " +
               $"=> {1000.0 / samples[samples.Count / 2]:F0}fps ceiling";
    }

    /// <summary>Creates a second top-level window and reports whether it is independently embeddable.</summary>
    private static string SecondWindow()
    {
        var subwindows = DisplayServer.HasFeature(DisplayServer.Feature.Subwindows);
        var rootId = (int)DisplayServer.MainWindowId;
        var rootView = DisplayServer.WindowGetNativeHandle(DisplayServer.HandleType.WindowView, rootId);

        // Godot embeds sub-windows inside the parent viewport by default, which gives them no OS window and so
        // nothing to reparent. Turning it off is what makes a scratch independently embeddable.
        var root = ((SceneTree)Godot.Engine.GetMainLoop()).Root;
        root.GuiEmbedSubwindows = false;

        var window = new Window { Title = "mush scratch B", Size = new Vector2I(640, 360) };
        // Without its own worlds the second scratch would render the first one's scene.
        window.World3D = new World3D();
        window.World2D = new World2D();
        secondScratch?.QueueFree();
        secondScratch = window;
        root.AddChild(window);

        var id = window.GetWindowId();
        var view = DisplayServer.WindowGetNativeHandle(DisplayServer.HandleType.WindowView, id);
        var handle = DisplayServer.WindowGetNativeHandle(DisplayServer.HandleType.WindowHandle, id);
        return $"subwindows={subwindows} rootId={rootId} rootView=0x{rootView:x} " +
               $"id={id} view=0x{view:x} handle=0x{handle:x} " +
               $"distinct={(id != rootId && view != rootView && view != 0)} " +
               $"embedded={window.IsEmbedded()} worlds={window.World3D != null}/{window.World2D != null}";
    }

    private static Window? secondScratch;

    private static void Resize(string spec)
    {
        var parts = spec.Split('x');
        if (parts.Length != 2
            || !int.TryParse(parts[0], out var width) || !int.TryParse(parts[1], out var height)
            || width <= 0 || height <= 0)
        {
            Emit("error", $"bad resolution: {spec}");
            return;
        }

        DisplayServer.WindowSetSize(new Vector2I(width, height));
        Emit("resolution", $"{width}x{height}");
    }

    private static void Report(string resPath, SceneLoader.Timing timing, SceneLoader loader, ViewCamera camera)
    {
        camera.Attach(loader.Current!, resPath);
        Emit("view", camera.Settings.Command[5..]);
        Emit("loaded", $"parse={timing.ParseMs:F1}ms instantiate={timing.InstantiateMs:F1}ms");
    }

    // What the window should be showing, to tell a host that draws nothing from frames that never reach the screen.
    private static string Stats(Node? scene, ViewSettings settings)
    {
        var viewport = scene?.GetViewport();
        var camera = settings.Mode == ViewMode.ThreeD && viewport?.GetCamera3D() is { } c3 ? $"{c3.Name}@{c3.GlobalPosition}"
            : viewport?.GetCamera2D() is { } c2 ? $"{c2.Name}@{c2.GlobalPosition}" : "none";
        var zoom = viewport?.GlobalCanvasTransform.X.Length().ToString("F3") ?? "-";
        return $"frames={Godot.Engine.GetFramesDrawn()} window={DisplayServer.WindowGetSize()} " +
               $"canDraw={DisplayServer.WindowCanDraw()} outputs={DisplayServer.HasAdditionalOutputs()} " +
               $"mode={DisplayServer.WindowGetMode()} scene={scene?.Name ?? "none"} nodes={CountNodes(scene)} camera={camera} " +
               $"zoom={zoom} canvasOffset={viewport?.GlobalCanvasTransform.Origin} {settings.Command} " +
               $"viewport={viewport?.GetVisibleRect().Size} drawn={Coverage(viewport)}";
    }

    // Share of sampled pixels in the last rendered frame that differ from the clear colour.
    private static string Coverage(Viewport? viewport)
    {
        using var image = viewport?.GetTexture().GetImage();
        if (image is null || image.IsEmpty())
            return "-";
        var clear = RenderingServer.GetDefaultClearColor();
        int samples = 0, drawn = 0;
        for (var y = 0; y < image.GetHeight(); y += 16)
        for (var x = 0; x < image.GetWidth(); x += 16)
        {
            samples++;
            var p = image.GetPixel(x, y);
            if (Math.Abs(p.R - clear.R) + Math.Abs(p.G - clear.G) + Math.Abs(p.B - clear.B) > 0.03f)
                drawn++;
        }

        return $"{100 * drawn / Math.Max(samples, 1)}%@{image.GetWidth()}x{image.GetHeight()}";
    }

    private static int CountNodes(Node? node) =>
        node is null ? 0 : 1 + node.GetChildren().Sum(CountNodes);

    internal static void Emit(string kind, string payload)
    {
        Console.Out.WriteLine($"mush:{kind} {payload}");
        Console.Out.Flush();
    }
}
