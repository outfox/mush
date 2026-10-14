using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using Godot;

namespace Mush.Spike;

/// <summary>Loads scenes from disk or from unsaved text and swaps them in as the current scene.</summary>
internal sealed class SceneLoader(SceneTree tree)
{
    private const string TempDir = "res://.godot/mush";

    public readonly record struct Timing(double ParseMs, double InstantiateMs);

    public Node? Current { get; private set; } = tree.CurrentScene;

    public Timing Load(string resPath, string? text)
    {
        var watch = Stopwatch.StartNew();
        var scene = text is null ? LoadFile(resPath) : LoadText(resPath, text);
        var parseMs = watch.Elapsed.TotalMilliseconds;

        watch.Restart();
        var instance = scene.Instantiate();
        Swap(instance);
        return new Timing(parseMs, watch.Elapsed.TotalMilliseconds);
    }

    private static PackedScene LoadFile(string resPath) =>
        ResourceLoader.Load<PackedScene>(resPath, cacheMode: ResourceLoader.CacheMode.Ignore)
        ?? throw new InvalidOperationException($"cannot load {resPath}");

    // Godot cannot parse a scene from a string, so the buffer goes through a temp file under .godot/, which the
    // editor's filesystem scan skips. IGNORE re-parses the scene but reuses cached textures and meshes.
    private static PackedScene LoadText(string resPath, string text)
    {
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(resPath)))[..16];
        var tempPath = $"{TempDir}/{hash}.tscn";
        Directory.CreateDirectory(ProjectSettings.GlobalizePath(TempDir));
        File.WriteAllText(ProjectSettings.GlobalizePath(tempPath), text);

        var scene = ResourceLoader.Load<PackedScene>(tempPath, cacheMode: ResourceLoader.CacheMode.Ignore)
                    ?? throw new InvalidOperationException($"cannot parse unsaved text of {resPath}");
        scene.SetPathCache(resPath);
        return scene;
    }

    // The previous scene stays alive until the new one is instantiated, so shared resources stay cached.
    private void Swap(Node instance)
    {
        var root = tree.Root;
        if (Current is { } old)
        {
            root.RemoveChild(old);
            old.Free();
        }

        root.AddChild(instance);
        tree.CurrentScene = instance;
        Current = instance;
    }
}
