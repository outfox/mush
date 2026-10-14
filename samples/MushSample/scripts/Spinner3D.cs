using Godot;

namespace MushSample;

/// <summary>Rotates every frame around Y; shows whether the Process gate is open.</summary>
public partial class Spinner3D : Node3D
{
    [Export] public float DegreesPerSecond { get; set; } = 45f;

    public override void _Process(double delta) => RotateY(Mathf.DegToRad(DegreesPerSecond * (float)delta));
}
