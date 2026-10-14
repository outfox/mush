using System.Collections.Concurrent;
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

        using var engine = new Engine("mush", args: godotArgs.ToArray());
        engine.Start();

        var tree = engine.Tree;
        var loader = new SceneLoader(tree);
        var camera = new ViewCamera(tree.Root);
        Emit("hwnd", DisplayServer.WindowGetNativeHandle(DisplayServer.HandleType.WindowHandle).ToString());

        var commands = new ConcurrentQueue<string>();
        var reader = new Thread(() =>
        {
            while (Console.In.ReadLine() is { } line)
                commands.Enqueue(line);
            commands.Enqueue("quit");
        }) { IsBackground = true, Name = "mush stdin" };
        reader.Start();

        if (initialScene != null)
            commands.Enqueue($"load {initialScene}");

        while (!engine.Iteration())
        {
            while (commands.TryDequeue(out var line))
            {
                if (!Execute(line, loader, camera))
                    engine.RequestQuit();
            }
        }

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

    private static void Report(string resPath, SceneLoader.Timing timing, SceneLoader loader, ViewCamera camera)
    {
        camera.Attach(loader.Current!, resPath);
        Emit("loaded", $"parse={timing.ParseMs:F1}ms instantiate={timing.InstantiateMs:F1}ms");
    }

    private static int CountNodes(Node? node) =>
        node is null ? 0 : 1 + node.GetChildren().Sum(CountNodes);

    internal static void Emit(string kind, string payload)
    {
        Console.Out.WriteLine($"mush:{kind} {payload}");
        Console.Out.Flush();
    }
}
