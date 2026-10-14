using Godot;

namespace MushSample;

/// <summary>Builds a ring of sprites in _Ready; shows whether the Ready gate is open.</summary>
public partial class Ring : Node2D
{
    [Export] public Texture2D Texture { get; set; }
    [Export] public int Count { get; set; } = 8;
    [Export] public float Radius { get; set; } = 200f;

    public override void _Ready()
    {
        for (var i = 0; i < Count; i++)
        {
            var angle = Mathf.Tau * i / Count;
            AddChild(new Sprite2D
            {
                Texture = Texture,
                Position = Vector2.FromAngle(angle) * Radius,
                Scale = Vector2.One * 0.5f,
            });
        }
    }
}
