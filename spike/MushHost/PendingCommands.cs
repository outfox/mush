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

    private static string? StateKey(string command)
    {
        var parts = command.Split(' ', 2);
        return parts[0] switch
        {
            "stats" or "select" or "ping" => parts[0],
            // Without a payload these are malformed, and must not evict a valid pending update.
            "text" or "view" or "visible" when parts.Length == 2 && !string.IsNullOrWhiteSpace(parts[1]) => parts[0],
            _ => null
        };
    }

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
