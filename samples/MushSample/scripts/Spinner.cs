using Godot;

namespace MushSample;

/// <summary>Rotates every frame; shows whether the Process gate is open.</summary>
public partial class Spinner : Node2D
{
    [Export] public float DegreesPerSecond { get; set; } = 90f;

    public override void _Process(double delta) => RotationDegrees += DegreesPerSecond * (float)delta;
}
