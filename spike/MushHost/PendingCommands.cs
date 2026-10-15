using System.Diagnostics.CodeAnalysis;

namespace Mush.Spike;

/// <summary>Bounded controls and latest document/diagnostics state while the engine thread is busy.</summary>
internal sealed class PendingCommands(int capacity = 32)
{
    private readonly object _gate = new();
    private readonly LinkedList<string> _pending = new();
    private bool _quitting;

    public bool Enqueue(string command)
    {
        lock (_gate)
        {
            if (_quitting) return false;
            if (command == "quit")
            {
                _quitting = true;
                _pending.Clear();
            }
            else if (StateKey(command) is { } key)
            {
                for (var node = _pending.First; node is not null;)
                {
                    var next = node.Next;
                    if (StateKey(node.Value) == key)
                        _pending.Remove(node);
                    node = next;
                }
            }
            if (_pending.Count >= capacity) return false;
            _pending.AddLast(command);
            return true;
        }
    }

    private static string? StateKey(string command) => command switch
    {
        "stats" => "stats",
        _ when command.StartsWith("text ", StringComparison.Ordinal) => "text",
        _ when command.StartsWith("view ", StringComparison.Ordinal) => "view",
        _ => null
    };

    public bool TryDequeue([NotNullWhen(true)] out string? command)
    {
        lock (_gate)
        {
            if (_pending.First is not { } node)
            {
                command = null;
                return false;
            }
            command = node.Value;
            _pending.RemoveFirst();
            return true;
        }
    }
}
