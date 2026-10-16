using Godot;
using Mush.Spike;
using System.Text;
using Engine = twodog.Engine;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        if (args is ["--host", var hostPath])
        {
            HostProtocolProbe.Run(hostPath);
            return;
        }
        // Native rendering/input regression: exercises the production camera against mixed content without Rider or
        // desktop input automation. Run with: dotnet run --project spike/harness/ViewProbe
        using var engine = new Engine("mush view probe", args: ["--hidden-window", "--resolution", "640x480", "--audio-driver", "Dummy", ..args])
        {
            CaptureErrors = true
        };
        engine.Start();
        var tree = engine.Tree;
        var root = tree.Root;
        RenderingServer.SetDefaultClearColor(Colors.Black);
        RenderingServer.ViewportSetUpdateMode(root.GetViewportRid(), RenderingServer.ViewportUpdateMode.Always);
        if (tree.CurrentScene is { } old)
        {
            root.RemoveChild(old);
            old.Free();
        }

        // A generic root deliberately catches camera selection based only on the root's runtime type.
        var scene = new Node { Name = "Mixed" };
        using var mesh = new BoxMesh();
        using var material = new StandardMaterial3D
        {
            AlbedoColor = Colors.Blue,
            ShadingMode = BaseMaterial3D.ShadingModeEnum.Unshaded
        };
        scene.AddChild(new MeshInstance3D { Mesh = mesh, MaterialOverride = material });
        var worldRect = new ColorRect { Position = new Vector2(20, 20), Size = new Vector2(40, 40), Color = Colors.Red };
        var world2D = new Node2D { Name = "World2D" };
        world2D.AddChild(worldRect);
        scene.AddChild(world2D);
        var layer = new CanvasLayer();
        var uiRect = new ColorRect { Position = new Vector2(100, 20), Size = new Vector2(40, 40), Color = Colors.Green };
        layer.AddChild(uiRect);
        scene.AddChild(layer);
        root.AddChild(scene);
        tree.CurrentScene = scene;
        OwnChildren(scene);
        var camera = new ViewCamera(root);
        camera.Attach(scene, "res://mixed.tscn");
        Check(camera.Settings.Mode == ViewMode.ThreeD, "generic mixed root selects 3D initially");
        Check(root.GetCamera2D() != null && root.GetCamera3D() != null, "both editor cameras exist");

        AssertFrame(red: true, green: true, blue: true);
        var packedBeforeNavigation = PackedState(scene);
        camera.Configure(new ViewSettings(false, true, ViewMode.ThreeD));
        AssertFrame(red: false, green: false, blue: true);
        Check(worldRect.Visible && uiRect.Visible, "visibility filtering preserves scene node properties");
        camera.Configure(new ViewSettings(true, false, ViewMode.ThreeD));
        AssertFrame(red: true, green: true, blue: false);
        camera.Configure(new ViewSettings(false, false, ViewMode.TwoD));
        AssertFrame(red: false, green: false, blue: false);

        camera.Configure(new ViewSettings(true, true, ViewMode.TwoD));
        var initial3D = root.GetCamera3D()!.GlobalTransform;
        Drag(new Vector2(20, 30));
        Check(root.GetCamera3D()!.GlobalTransform.IsEqualApprox(initial3D), "2D pan preserves 3D camera");
        using (var image = Snapshot())
        {
            Check(IsColor(image.GetPixel(45, 55), Colors.Red), "2D canvas pans");
            Check(IsColor(image.GetPixel(125, 55), Colors.Green), "CanvasLayer UI pans with 2D");
            Check(!IsColor(image.GetPixel(25, 25), Colors.Red), "old 2D position cleared");
        }
        var cursor = new Vector2(80, 80);
        var pointBefore = root.GlobalCanvasTransform.AffineInverse() * cursor;
        EmitInput(new InputEventMouseButton { ButtonIndex = MouseButton.WheelUp, Pressed = true, Position = cursor });
        Check((root.GlobalCanvasTransform * pointBefore).IsEqualApprox(cursor), "2D zoom anchors the cursor");
        Check(root.GetCamera3D()!.GlobalTransform.IsEqualApprox(initial3D), "2D zoom preserves 3D camera");
        var canvasBefore3D = root.GlobalCanvasTransform;

        camera.Configure(new ViewSettings(true, true, ViewMode.ThreeD));
        Drag(new Vector2(60, 20));
        Check(!root.GetCamera3D()!.GlobalTransform.IsEqualApprox(initial3D), "3D orbit moves its camera");
        Check(root.GlobalCanvasTransform.IsEqualApprox(canvasBefore3D), "3D orbit preserves 2D/UI view");
        var orbit = root.GetCamera3D()!.GlobalTransform;
        Drag(new Vector2(12, -8), shift: true);
        Check(!root.GetCamera3D()!.GlobalTransform.IsEqualApprox(orbit), "Shift+middle-drag pans 3D");
        var before3DZoom = root.GetCamera3D()!.GlobalTransform;
        EmitInput(new InputEventMouseButton { ButtonIndex = MouseButton.WheelUp, Pressed = true, Position = cursor });
        Check(!root.GetCamera3D()!.GlobalTransform.IsEqualApprox(before3DZoom), "3D wheel zoom moves its camera");
        Check(root.GlobalCanvasTransform.IsEqualApprox(canvasBefore3D), "3D navigation preserves 2D/UI view");
        var moved3D = root.GetCamera3D()!.GlobalTransform;
        Check(PackedState(scene) == packedBeforeNavigation,
            "PackedScene.Pack preserves scene properties and hierarchy after editor navigation and visibility changes");

        // A scene reload can activate its own cameras; the editor must take both views back without resetting them.
        root.RemoveChild(scene);
        scene.Free();
        scene = new Node3D { Name = "Reloaded" };
        root.AddChild(scene);
        tree.CurrentScene = scene;
        var scene2D = new Camera2D();
        var scene3D = new Camera3D();
        scene.AddChild(scene2D);
        scene.AddChild(scene3D);
        scene2D.MakeCurrent();
        scene3D.MakeCurrent();
        camera.Configure(new ViewSettings(false, true, ViewMode.TwoD));
        camera.Attach(scene, "res://mixed.tscn");
        Check(root.GetCamera2D() != scene2D && root.GetCamera3D() != scene3D, "reload restores both editor cameras");
        Check(root.GetCamera3D()!.GlobalTransform.IsEqualApprox(moved3D), "reload preserves 3D view");
        Check(root.GlobalCanvasTransform.IsEqualApprox(canvasBefore3D), "reload preserves 2D/UI view");
        Check(root.CanvasCullMask == 0 && !root.Disable3D && camera.Settings.Mode == ViewMode.TwoD,
            "reload preserves visibility independently of navigation");

        var errors = engine.Errors.Drain();
        Check(errors.Length == 0, $"native engine warnings/errors: {string.Join("; ", errors.Select(e => e.ToString()))}");
        Console.WriteLine("PASS: 2D/UI and 3D visibility/navigation, cursor anchoring, unchanged PackedScene state and reload preservation.");

        void EmitInput(InputEvent input)
        {
            using (input) root.EmitSignal(Window.SignalName.WindowInput, input);
        }

        void Drag(Vector2 delta, bool shift = false)
        {
            EmitInput(new InputEventMouseButton { ButtonIndex = MouseButton.Middle, Pressed = true });
            EmitInput(new InputEventMouseMotion { Relative = delta, ShiftPressed = shift });
            EmitInput(new InputEventMouseButton { ButtonIndex = MouseButton.Middle, Pressed = false });
        }

        Image Snapshot()
        {
            for (var i = 0; i < 8; i++)
            {
                Check(!engine.Iteration(), "engine remains running");
                Thread.Sleep(5);
            }
            var image = root.GetTexture().GetImage();
            Check(!image.IsEmpty(), "hidden viewport produces a rendered frame");
            return image;
        }

        void AssertFrame(bool red, bool green, bool blue)
        {
            using var image = Snapshot();
            Check(IsColor(image.GetPixel(25, 25), Colors.Red) == red, $"2D visibility={red}");
            Check(IsColor(image.GetPixel(105, 25), Colors.Green) == green, $"UI visibility={green}");
            Check(IsColor(image.GetPixel(320, 240), Colors.Blue) == blue, $"3D visibility={blue}");
        }

        void OwnChildren(Node parent)
        {
            foreach (var child in parent.GetChildren())
            {
                child.Owner = scene;
                OwnChildren(child);
            }
        }

        static string PackedState(Node scene)
        {
            using var packed = new PackedScene();
            Check(packed.Pack(scene) == Error.Ok, "scene packs successfully");
            using var state = packed.GetState();
            Check(state.GetNodeCount() >= 5, "packing check includes the scene's renderable descendants");
            var result = new StringBuilder();
            for (var i = 0; i < state.GetNodeCount(); i++)
            {
                result.AppendLine($"{state.GetNodePath(i)}|{state.GetNodeName(i)}|{state.GetNodeType(i)}|{state.GetNodeOwnerPath(i)}");
                for (var j = 0; j < state.GetNodePropertyCount(i); j++)
                {
                    using var value = state.GetNodePropertyValue(i, j);
                    result.AppendLine($"{state.GetNodePropertyName(i, j)}={value}");
                }
            }
            return result.ToString();
        }

        static bool IsColor(Color actual, Color expected) =>
            Math.Abs(actual.R - expected.R) + Math.Abs(actual.G - expected.G) + Math.Abs(actual.B - expected.B) < 0.1f;

        static void Check(bool condition, string message)
        {
            if (!condition) throw new InvalidOperationException(message);
        }
    }
}
