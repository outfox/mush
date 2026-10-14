using Godot;

namespace MushSample;

/// <summary>Builds a circle of boxes in _Ready; shows whether the Ready gate is open.</summary>
public partial class Pillars : Node3D
{
    [Export] public int Count { get; set; } = 12;
    [Export] public float Radius { get; set; } = 4f;

    public override void _Ready()
    {
        var mesh = new BoxMesh { Size = new Vector3(0.4f, 1.5f, 0.4f) };
        for (var i = 0; i < Count; i++)
        {
            var angle = Mathf.Tau * i / Count;
            AddChild(new MeshInstance3D
            {
                Mesh = mesh,
                Position = new Vector3(Mathf.Cos(angle) * Radius, 0.75f, Mathf.Sin(angle) * Radius),
            });
        }
    }
}
