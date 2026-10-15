using Godot;

namespace Mush.Spike;

/// <summary>
/// Independent editor views: middle-drag pans 2D/UI or orbits 3D (Shift pans), wheel zooms the selected view.
/// Input arrives natively in the Godot window; the root's WindowInput signal sees it before the scene does.
/// The cameras live under the root, outside the scene, so the view carries over when edits reload the scene.
/// </summary>
internal sealed class ViewCamera
{
    private const float ZoomStep = 1.1f;

    private readonly Window _root;
    private Camera2D? _camera2D;
    private Camera3D? _camera3D;
    private bool _dragging;
    private string? _scenePath;
    private bool _modeSelected;
    private Vector2 _offset2D;
    private float _zoom2D = 1f;

    public ViewSettings Settings { get; private set; } = new(true, true, ViewMode.TwoD);

    private Vector3 _target;
    private float _distance = 10f;
    private float _yaw;
    private float _pitch = 0.4f;

    public ViewCamera(Window root)
    {
        _root = root;
        _root.WindowInput += OnInput;
    }

    public void Attach(Node scene, string resPath)
    {
        var sameView = resPath == _scenePath && _camera2D != null && _camera3D != null;
        _scenePath = resPath;

        // Cameras in the freshly loaded scene may have made themselves current; take the view back.
        if (sameView)
        {
            _camera2D!.MakeCurrent();
            _camera3D!.MakeCurrent();
            ApplyView();
            return;
        }

        // A generic Node can contain a 3D scene too. Nested viewports own their own camera/rendering state.
        var is3D = scene is Node3D || (scene is not CanvasItem && Contains3D(scene));
        if (!_modeSelected)
            Settings = Settings with { Mode = is3D ? ViewMode.ThreeD : ViewMode.TwoD };

        var scenePosition = _root.GetCamera3D()?.GlobalPosition;
        _camera2D?.Free();
        _camera3D?.Free();

        _target = Vector3.Zero;
        _distance = 10f;
        _yaw = 0f;
        _pitch = 0.4f;
        if (scenePosition is { } position)
            OrbitFrom(position);
        _camera3D = new Camera3D { Name = "MushCamera3D" };
        _root.AddChild(_camera3D);
        _camera3D.MakeCurrent();
        Apply3D();

        // Preserve the old 2D framing; a 3D scene's UI starts at its ordinary screen coordinates.
        var gameSize = new Vector2(
            ProjectSettings.GetSetting("display/window/size/viewport_width").AsInt32(),
            ProjectSettings.GetSetting("display/window/size/viewport_height").AsInt32());
        _camera2D = new Camera2D
        {
            Name = "MushCamera2D",
            AnchorMode = is3D ? Camera2D.AnchorModeEnum.FixedTopLeft : Camera2D.AnchorModeEnum.DragCenter,
            Position = is3D ? Vector2.Zero : gameSize / 2
        };
        _root.AddChild(_camera2D);
        _camera2D.MakeCurrent();
        _offset2D = Vector2.Zero;
        _zoom2D = 1f;
        _dragging = false;
        ApplyView();
    }

    public void Configure(ViewSettings settings)
    {
        Settings = settings;
        _modeSelected = true;
        _dragging = false;
        ApplyView();
    }

    private void ApplyView()
    {
        // Controls and Node2D share the canvas renderer. A global transform also moves CanvasLayer-based UI.
        _root.CanvasCullMask = Settings.Show2D ? uint.MaxValue : 0;
        _root.Disable3D = !Settings.Show3D;
        _root.GlobalCanvasTransform = new Transform2D(0, Vector2.One * _zoom2D, 0, _offset2D);
    }

    private static bool Contains3D(Node node) => node.GetChildren().Any(child =>
        child is not Viewport && (child is Node3D || Contains3D(child)));

    private void OnInput(InputEvent e)
    {
        switch (e)
        {
            case InputEventMouseButton { ButtonIndex: MouseButton.Middle } button:
                _dragging = button.Pressed;
                break;
            case InputEventMouseButton { Pressed: true, ButtonIndex: MouseButton.WheelUp } wheel:
                Zoom(ZoomStep, wheel.Position);
                break;
            case InputEventMouseButton { Pressed: true, ButtonIndex: MouseButton.WheelDown } wheel:
                Zoom(1 / ZoomStep, wheel.Position);
                break;
            case InputEventMouseMotion motion when _dragging:
                Drag(motion.Relative, motion.ShiftPressed);
                break;
        }
    }

    private void Drag(Vector2 delta, bool shift)
    {
        if (Settings.Mode == ViewMode.TwoD)
        {
            _offset2D += delta;
            ApplyView();
        }
        else if (_camera3D != null)
        {
            if (shift)
            {
                var basis = _camera3D.GlobalBasis;
                _target += (-basis.X * delta.X + basis.Y * delta.Y) * _distance * 0.002f;
            }
            else
            {
                _yaw -= delta.X * 0.01f;
                _pitch = Mathf.Clamp(_pitch + delta.Y * 0.01f, -1.5f, 1.5f);
            }

            Apply3D();
        }
    }

    // Global 2D zoom keeps the point under the cursor fixed, including UI in separate CanvasLayers.
    private void Zoom(float factor, Vector2 cursor)
    {
        if (Settings.Mode == ViewMode.TwoD)
        {
            var zoom = Mathf.Clamp(_zoom2D * factor, 0.01f, 100f);
            _offset2D = cursor - (cursor - _offset2D) * (zoom / _zoom2D);
            _zoom2D = zoom;
            ApplyView();
        }
        else if (_camera3D != null)
        {
            _distance = Mathf.Clamp(_distance / factor, 0.1f, 1000f);
            Apply3D();
        }
    }

    private void OrbitFrom(Vector3 position)
    {
        var offset = position - _target;
        _distance = Mathf.Max(offset.Length(), 0.1f);
        _yaw = Mathf.Atan2(offset.X, offset.Z);
        _pitch = Mathf.Asin(offset.Y / _distance);
    }

    private void Apply3D()
    {
        var offset = new Vector3(
            Mathf.Sin(_yaw) * Mathf.Cos(_pitch),
            Mathf.Sin(_pitch),
            Mathf.Cos(_yaw) * Mathf.Cos(_pitch)) * _distance;
        _camera3D!.GlobalPosition = _target + offset;
        _camera3D.LookAt(_target);
    }
}
