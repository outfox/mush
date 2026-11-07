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
    // Low enough to idle, high enough that commands for a hidden tab are not left waiting.
    private const int HiddenMaxFps = 5;

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

        using var engine = new Engine("mush", args: godotArgs.ToArray());
        engine.Start();

        var tree = engine.Tree;
        // shortcut: stops motion and scene input, not constructors or _Ready; replace with the Frozen policy (milestone 5).
        tree.Paused = true;
        var loader = new SceneLoader(tree);
        var camera = new ViewCamera(tree.Root);
        var selection = new NodeSelection(tree.Root, () => loader.Current, path => Emit("selected", path));
        Emit("hwnd", DisplayServer.WindowGetNativeHandle(DisplayServer.HandleType.WindowHandle).ToString());

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
            selection.Redraw();
            // Leave time for a frame even when producers keep submitting commands.
            for (var i = 0; i < 16 && commands.TryDequeue(out var line); i++)
            {
                if (!Execute(line, loader, camera, selection))
                {
                    engine.RequestQuit();
                    break;
                }
            }
        }

        return 0;
    }

    private static bool Execute(string line, SceneLoader loader, ViewCamera camera, NodeSelection selection)
    {
        // Spacing between fields is free. Scene paths come last and stay whole, including any spaces inside them.
        const StringSplitOptions fields = StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries;
        var parts = line.Split(' ', 3, fields);
        if (parts.Length == 0)
            return true;
        string AfterVerb() => line.Split(' ', 2, fields)[1];

        try
        {
            switch (parts[0])
            {
                case "quit":
                    return false;
                case "load" when parts.Length >= 2:
                    LoadScene(AfterVerb(), text: null, loader, camera);
                    break;
                // Unsaved document text travels in-band as base64, so no temp file can race the editor.
                case "text" when parts.Length == 3:
                    LoadScene(parts[2], Encoding.UTF8.GetString(Convert.FromBase64String(parts[1])), loader, camera);
                    break;
                case "view":
                    camera.Configure(ViewSettings.Parse(line));
                    Emit("view", camera.Settings.Command[5..]);
                    break;
                case "reset":
                    camera.ResetView();
                    break;
                case "select":
                    selection.Select(parts.Length >= 2 ? AfterVerb() : null);
                    break;
                // shortcut: process-wide, fine while each editor has its own host; make it per-scratch with the shared host.
                case "visible" when parts.Length == 2:
                    Godot.Engine.MaxFps = parts[1] == "1" ? 0 : HiddenMaxFps;
                    break;
                case "ping":
                    Emit("pong", "");
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
                case "stats":
                    Emit("stats", Stats(loader.Current, camera.Settings));
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

    private static void LoadScene(string resPath, string? text, SceneLoader loader, ViewCamera camera)
    {
        // Godot's errors reach the plugin on the same pipe; this marks where the ones for this load begin.
        Emit("loading", resPath);
        var timing = loader.Load(resPath, text);
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
