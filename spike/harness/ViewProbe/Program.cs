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
        // The host keeps the tree paused, and editor navigation must work anyway.
        tree.Paused = true;
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
        scene.AddChild(new MeshInstance3D { Name = "Box", Mesh = mesh, MaterialOverride = material });
        var worldRect = new ColorRect { Name = "WorldRect", Position = new Vector2(20, 20), Size = new Vector2(40, 40), Color = Colors.Red };
        var world2D = new Node2D { Name = "World2D" };
        world2D.AddChild(worldRect);
        scene.AddChild(world2D);
        var layer = new CanvasLayer { Name = "Ui" };
        var uiRect = new ColorRect { Name = "UiRect", Position = new Vector2(100, 20), Size = new Vector2(40, 40), Color = Colors.Green };
        layer.AddChild(uiRect);
        // Draw order differs from tree order in both pairs: the layer draws over what follows it, and z_index beats order.
        layer.AddChild(new ColorRect { Name = "UiCover", Position = new Vector2(500, 400), Size = new Vector2(40, 40) });
        scene.AddChild(layer);
        scene.AddChild(new ColorRect { Name = "UnderUi", Position = new Vector2(500, 400), Size = new Vector2(40, 40) });
        scene.AddChild(new ColorRect { Name = "RaisedZ", ZIndex = 1, Position = new Vector2(560, 400), Size = new Vector2(40, 40) });
        scene.AddChild(new ColorRect { Name = "LowerZ", Position = new Vector2(560, 400), Size = new Vector2(40, 40) });
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
        var picked = new List<string>();
        var selection = new NodeSelection(root, () => tree.CurrentScene, picked.Add);
        selection.Select("Ui/UiRect");
        using (var image = Snapshot())
            Check(IsColor(image.GetPixel(120, 20), NodeSelection.OutlineColor), "selection outlines a UI node");
        selection.Select(null);
        using (var image = Snapshot())
            Check(!IsColor(image.GetPixel(120, 20), NodeSelection.OutlineColor), "clearing the selection removes its outline");
        selection.Select("Box");
        var boxOutline = OutlineLeftEdge();
        Check(boxOutline is > 0 and < 320, "selection outlines a 3D node");
        camera.Configure(new ViewSettings(false, true, ViewMode.TwoD));
        Check(OutlineLeftEdge() == boxOutline, "the outline stays visible when 2D/UI is hidden");
        camera.Configure(new ViewSettings(true, true, ViewMode.TwoD));
        selection.Select(null);
        Click(new Vector2(120, 40));
        Check(picked is ["Ui/UiRect"], "clicking UI picks its node");
        Click(new Vector2(320, 240));
        Check(picked is [_, "Box"], "clicking 3D picks its node");
        Click(new Vector2(600, 10));
        Check(picked.Count == 2, "clicking empty space picks nothing");
        Click(new Vector2(520, 420));
        Check(picked[^1] == "Ui/UiCover", $"a click picks the node drawn on top across canvas layers, got {picked[^1]}");
        Click(new Vector2(580, 420));
        Check(picked[^1] == "RaisedZ", $"a click picks the node drawn on top by z_index, got {picked[^1]}");
        Check(NodeSelection.EntryAlongRay(new Aabb(Vector3.Zero, Vector3.One), new Vector3(0, 0.5f, -1), Vector3.Back) is 1f,
            "a ray parallel to a box face and starting on it still hits the box");
        Check(NodeSelection.EntryAlongRay(new Aabb(Vector3.Zero, Vector3.One), new Vector3(-1, 0.5f, -1), Vector3.Back) is null,
            "a parallel ray outside the box misses it");
        selection.Select(null);

        var initial3D = root.GetCamera3D()!.GlobalTransform;
        Drag(new Vector2(20, 30));
        Check(root.GetCamera3D()!.GlobalTransform.IsEqualApprox(initial3D), "2D pan preserves 3D camera");
        using (var image = Snapshot())
        {
            Check(IsColor(image.GetPixel(45, 55), Colors.Red), "2D canvas pans");
            Check(IsColor(image.GetPixel(125, 55), Colors.Green), "CanvasLayer UI pans with 2D");
            Check(!IsColor(image.GetPixel(25, 25), Colors.Red), "old 2D position cleared");
        }
        selection.Select("Ui/UiRect");
        using (var image = Snapshot())
            Check(IsColor(image.GetPixel(140, 50), NodeSelection.OutlineColor), "a UI outline follows the panned 2D view");
        selection.Select("Box");
        Check(OutlineLeftEdge() == boxOutline, "a 3D outline ignores the panned 2D view");
        selection.Select(null);
        var cursor = new Vector2(80, 80);
        var pointBefore = root.GlobalCanvasTransform.AffineInverse() * cursor;
        EmitInput(new InputEventMouseButton { ButtonIndex = MouseButton.WheelUp, Pressed = true, Position = cursor });
        Check((root.GlobalCanvasTransform * pointBefore).IsEqualApprox(cursor), "2D zoom anchors the cursor");
        Check(root.GetCamera3D()!.GlobalTransform.IsEqualApprox(initial3D), "2D zoom preserves 3D camera");

        // After pan and zoom, outlines and clicks must both land where the nodes are drawn, in and out of canvas layers.
        foreach (var (path, topEdge, center) in new[]
        {
            ("World2D/WorldRect", new Vector2(40, 20), new Vector2(40, 40)),
            ("Ui/UiRect", new Vector2(120, 20), new Vector2(120, 40))
        })
        {
            selection.Select(path);
            using (var image = Snapshot())
            {
                var edge = root.GlobalCanvasTransform * topEdge;
                Check(IsColor(image.GetPixel(Mathf.RoundToInt(edge.X), Mathf.RoundToInt(edge.Y)), NodeSelection.OutlineColor),
                    $"the outline of {path} lands on the node at {edge} after pan and zoom");
            }
            selection.Select(null);
            Click(root.GlobalCanvasTransform * center);
            Check(picked[^1] == path, $"a click on {path} after pan and zoom picked {picked[^1]}");
        }
        selection.Select(null);
        var canvasBefore3D = root.GlobalCanvasTransform;

        camera.Configure(new ViewSettings(true, true, ViewMode.ThreeD));
        Drag(new Vector2(60, 20));
        Check(!root.GetCamera3D()!.GlobalTransform.IsEqualApprox(initial3D), "3D orbit moves its camera");
        Check(root.GlobalCanvasTransform.IsEqualApprox(canvasBefore3D), "3D orbit preserves 2D/UI view");
        var orbit = root.GetCamera3D()!.GlobalTransform;
        Drag(new Vector2(12, -8), shift: true);
        Check(!root.GetCamera3D()!.GlobalTransform.IsEqualApprox(orbit), "Shift+middle-drag pans 3D");
        var beforeRightDrag = root.GetCamera3D()!.GlobalTransform;
        Drag(new Vector2(30, 10), button: MouseButton.Right);
        Check(!root.GetCamera3D()!.GlobalTransform.IsEqualApprox(beforeRightDrag), "right-drag navigates like middle-drag");
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
        Check(root.CanvasCullMask == ViewCamera.OverlayLayer && !root.Disable3D && camera.Settings.Mode == ViewMode.TwoD,
            "reload preserves visibility independently of navigation");

        camera.ResetView();
        Check(root.GlobalCanvasTransform.IsEqualApprox(Transform2D.Identity), "reset returns 2D/UI to its start");
        Check(!root.GetCamera3D()!.GlobalTransform.IsEqualApprox(moved3D), "reset returns 3D to its start");

        var errors = engine.Errors.Drain();
        Check(errors.Length == 0, $"native engine warnings/errors: {string.Join("; ", errors.Select(e => e.ToString()))}");
        Console.WriteLine("PASS: 2D/UI and 3D visibility/navigation in a paused tree, cursor anchoring, node selection, unchanged PackedScene state and reload preservation.");

        void EmitInput(InputEvent input)
        {
            using (input) root.EmitSignal(Window.SignalName.WindowInput, input);
        }

        void Drag(Vector2 delta, bool shift = false, MouseButton button = MouseButton.Middle)
        {
            EmitInput(new InputEventMouseButton { ButtonIndex = button, Pressed = true });
            EmitInput(new InputEventMouseMotion { Relative = delta, ShiftPressed = shift });
            EmitInput(new InputEventMouseButton { ButtonIndex = button, Pressed = false });
        }

        void Click(Vector2 position)
        {
            EmitInput(new InputEventMouseButton { ButtonIndex = MouseButton.Left, Pressed = true, Position = position });
            EmitInput(new InputEventMouseButton { ButtonIndex = MouseButton.Left, Pressed = false, Position = position });
        }

        // Where the outline crosses the middle row; a 3D outline's position only depends on the 3D camera.
        int OutlineLeftEdge()
        {
            using var image = Snapshot();
            for (var x = 0; x < image.GetWidth(); x++)
            {
                if (IsColor(image.GetPixel(x, 240), NodeSelection.OutlineColor))
                    return x;
            }
            return -1;
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
