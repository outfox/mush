using Godot;

namespace Mush.Spike;

/// <summary>
/// Editor camera that overrides the scene's own: middle-drag pans (2D) or orbits (3D, Shift pans), wheel zooms.
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
        var is3D = scene is Node3D;
        var sameView = resPath == _scenePath && (is3D ? _camera3D != null : _camera2D != null);
        _scenePath = resPath;

        // Cameras in the freshly loaded scene may have made themselves current; take the view back.
        if (sameView)
        {
            if (is3D)
                _camera3D!.MakeCurrent();
            else
                _camera2D!.MakeCurrent();
            return;
        }

        _camera2D?.Free();
        _camera3D?.Free();
        _camera2D = null;
        _camera3D = null;

        if (is3D)
        {
            _target = Vector3.Zero;
            _distance = 10f;
            _yaw = 0f;
            _pitch = 0.4f;
            if (_root.GetCamera3D() is { } sceneCamera)
                OrbitFrom(sceneCamera.GlobalPosition);
            _camera3D = new Camera3D { Name = "MushCamera3D" };
            _root.AddChild(_camera3D);
            _camera3D.MakeCurrent();
            Apply3D();
        }
        else
        {
            _camera2D = new Camera2D { Name = "MushCamera2D", Position = _root.GetVisibleRect().Size / 2 };
            _root.AddChild(_camera2D);
            _camera2D.MakeCurrent();
        }
    }

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
        if (_camera2D != null)
        {
            _camera2D.Position -= delta / _camera2D.Zoom;
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

    // 2D zoom keeps the world point under the cursor fixed.
    private void Zoom(float factor, Vector2 cursor)
    {
        if (_camera2D != null)
        {
            var fromCenter = cursor - _root.GetVisibleRect().Size / 2;
            var world = _camera2D.Position + fromCenter / _camera2D.Zoom;
            _camera2D.Zoom *= factor;
            _camera2D.Position = world - fromCenter / _camera2D.Zoom;
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
