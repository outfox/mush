using Godot;

namespace Mush.Spike;

/// <summary>
/// Links the viewport to the document: outlines the node the text cursor is on and reports clicked nodes.
/// The overlay lives under the root, outside the scene, so selecting never changes what the scene would pack.
/// </summary>
internal sealed class NodeSelection
{
    public static readonly Color OutlineColor = new(1f, 0.6f, 0.1f);

    private const float OutlineWidth = 2f;
    private const float MarkerRadius = 8f;

    private readonly Window _root;
    private readonly Func<Node?> _scene;
    private readonly Action<string> _picked;
    private readonly Node2D _overlay = new() { Name = "MushSelection", VisibilityLayer = ViewCamera.OverlayLayer };
    private string? _path;

    public NodeSelection(Window root, Func<Node?> scene, Action<string> picked)
    {
        _root = root;
        _scene = scene;
        _picked = picked;
        var layer = new CanvasLayer { Name = "MushOverlay", Layer = 1024 };
        layer.AddChild(_overlay);
        root.AddChild(layer);
        _overlay.Draw += OnDraw;
        root.WindowInput += OnInput;
    }

    /// <summary>Selects by path relative to the scene root, so the outline survives document reloads.</summary>
    public void Select(string? path)
    {
        _path = path;
        _overlay.QueueRedraw();
    }

    /// <summary>The cameras move without telling the overlay.</summary>
    public void Redraw()
    {
        if (_path != null)
            _overlay.QueueRedraw();
    }

    private void OnDraw()
    {
        if (_path is null || _scene()?.GetNodeOrNull(_path) is not { } node)
            return;
        var zoom = _root.GlobalCanvasTransform.X.Length();
        if (Outline(node, zoom) is { } corners)
            _overlay.DrawPolyline([.. corners, corners[0]], OutlineColor, OutlineWidth / zoom);
    }

    // Overlay-space corners of the node's bounds, or a small square at its origin when Godot offers no bounds.
    private Vector2[]? Outline(Node node, float zoom)
    {
        switch (node)
        {
            case Control control:
                return Quad(control.GetGlobalTransformWithCanvas(), new Rect2(Vector2.Zero, control.Size));
            case Sprite2D sprite:
                return Quad(sprite.GetGlobalTransformWithCanvas(), sprite.GetRect());
            case Node2D item:
                return Marker(item.GetGlobalTransformWithCanvas().Origin, zoom);
        }

        if (node is not Node3D spatial || _root.Disable3D || _root.GetCamera3D() is not { } camera)
            return null;
        if (spatial is not VisualInstance3D visual)
            return camera.IsPositionBehind(spatial.GlobalPosition) ? null : Marker(ToOverlay(camera.UnprojectPosition(spatial.GlobalPosition)), zoom);

        var box = visual.GetAabb();
        Rect2? bounds = null;
        for (var i = 0; i < 8; i++)
        {
            var corner = visual.GlobalTransform * box.GetEndpoint(i);
            if (camera.IsPositionBehind(corner))
                return null;
            var projected = ToOverlay(camera.UnprojectPosition(corner));
            bounds = bounds?.Expand(projected) ?? new Rect2(projected, Vector2.Zero);
        }
        return Quad(Transform2D.Identity, bounds!.Value);
    }

    // The global canvas transform also moves the overlay's layer, so screen positions need it taken back out.
    private Vector2 ToOverlay(Vector2 screen) => _root.GlobalCanvasTransform.AffineInverse() * screen;

    private static Vector2[] Quad(Transform2D transform, Rect2 rect) =>
    [
        transform * rect.Position,
        transform * new Vector2(rect.End.X, rect.Position.Y),
        transform * rect.End,
        transform * new Vector2(rect.Position.X, rect.End.Y)
    ];

    private static Vector2[] Marker(Vector2 center, float zoom)
    {
        var radius = MarkerRadius / zoom;
        return Quad(Transform2D.Identity, new Rect2(center - Vector2.One * radius, Vector2.One * radius * 2));
    }

    private void OnInput(InputEvent e)
    {
        if (e is not InputEventMouseButton { ButtonIndex: MouseButton.Left, Pressed: true } click || _scene() is not { } scene)
            return;
        var hit = _root.CanvasCullMask != ViewCamera.OverlayLayer ? Pick2D(scene, ToOverlay(click.Position)) : null;
        hit ??= _root.Disable3D ? null : Pick3D(scene, click.Position);
        if (hit is null)
            return;
        var path = scene.GetPathTo(hit).ToString();
        Select(path);
        _picked(path);
    }

    // shortcut: reverse tree order ignores z_index and canvas layers, use the renderer's order if clicks pick wrong.
    private static Node? Pick2D(Node node, Vector2 point)
    {
        var children = node.GetChildren();
        for (var i = children.Count - 1; i >= 0; i--)
        {
            if (children[i] is not Viewport && Pick2D(children[i], point) is { } hit)
                return hit;
        }
        var rect = node switch
        {
            Control control when control.IsVisibleInTree() => new Rect2(Vector2.Zero, control.Size),
            Sprite2D sprite when sprite.IsVisibleInTree() => sprite.GetRect(),
            _ => (Rect2?)null
        };
        return rect is { } local && local.HasPoint(((CanvasItem)node).GetGlobalTransformWithCanvas().AffineInverse() * point)
            ? node
            : null;
    }

    // shortcut: tests visual bounds only, so a concave mesh also catches clicks in its gaps; ray-test meshes if that annoys.
    private Node? Pick3D(Node scene, Vector2 cursor)
    {
        if (_root.GetCamera3D() is not { } camera)
            return null;
        var origin = camera.ProjectRayOrigin(cursor);
        var direction = camera.ProjectRayNormal(cursor);
        Node? nearest = null;
        var nearestDistance = float.MaxValue;
        Visit(scene);
        return nearest;

        void Visit(Node node)
        {
            foreach (var child in node.GetChildren())
            {
                if (child is not Viewport)
                    Visit(child);
            }
            if (node is not VisualInstance3D visual || !visual.IsVisibleInTree())
                return;
            var toLocal = visual.GlobalTransform.AffineInverse();
            var localOrigin = toLocal * origin;
            var localDirection = toLocal.Basis * direction;
            if (EntryAlongRay(visual.GetAabb(), localOrigin, localDirection) is not { } entry)
                return;
            var distance = (visual.GlobalTransform * (localOrigin + localDirection * entry)).DistanceTo(origin);
            if (distance < nearestDistance)
            {
                nearest = visual;
                nearestDistance = distance;
            }
        }
    }

    // Godot's C# Aabb only answers whether a segment hits; picking the nearest node needs where the ray enters.
    private static float? EntryAlongRay(Aabb box, Vector3 origin, Vector3 direction)
    {
        var enter = 0f;
        var leave = float.MaxValue;
        for (var axis = 0; axis < 3; axis++)
        {
            var a = (box.Position[axis] - origin[axis]) / direction[axis];
            var b = (box.End[axis] - origin[axis]) / direction[axis];
            enter = Mathf.Max(enter, Mathf.Min(a, b));
            leave = Mathf.Min(leave, Mathf.Max(a, b));
        }
        return enter <= leave ? enter : null;
    }
}
