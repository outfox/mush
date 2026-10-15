using Mush.Spike;
using Xunit;

namespace MushHost.Tests;

public sealed class PendingCommandsTests
{
    [Fact]
    public void BusyEngineKeepsOnlyLatestDocumentAndDiagnostics()
    {
        var commands = new PendingCommands();
        for (var i = 0; i < 10_000; i++)
        {
            Assert.True(commands.Enqueue($"text res://scene.tscn {i}"));
            Assert.True(commands.Enqueue("stats"));
        }
        Assert.True(commands.TryDequeue(out var text));
        Assert.Equal("text res://scene.tscn 9999", text);
        Assert.True(commands.TryDequeue(out var stats));
        Assert.Equal("stats", stats);
        Assert.False(commands.TryDequeue(out _));
    }

    [Fact]
    public void ControlsStayOrderedAndOverflowIsRejected()
    {
        var commands = new PendingCommands(3);
        Assert.True(commands.Enqueue("text old"));
        Assert.True(commands.Enqueue("load first"));
        Assert.True(commands.Enqueue("load second"));
        Assert.False(commands.Enqueue("load rejected"));
        Assert.True(commands.Enqueue("text newest"));
        foreach (var expected in new[] { "load first", "load second", "text newest" })
        {
            Assert.True(commands.TryDequeue(out var actual));
            Assert.Equal(expected, actual);
        }
        Assert.False(commands.TryDequeue(out _));
    }

    [Fact]
    public void QuitDiscardsPendingWorkEvenAtCapacityAndRejectsLateCommands()
    {
        var commands = new PendingCommands(1);
        Assert.True(commands.Enqueue("stall 10000"));
        Assert.True(commands.Enqueue("quit"));
        Assert.False(commands.Enqueue("text late"));
        Assert.True(commands.TryDequeue(out var quit));
        Assert.Equal("quit", quit);
        Assert.False(commands.TryDequeue(out _));
        Assert.False(commands.Enqueue("quit"));
    }

    [Fact]
    public void ViewChangesCoalesceWithoutDiscardingDocumentOrControls()
    {
        var commands = new PendingCommands(3);
        Assert.True(commands.Enqueue("view 1 1 2d"));
        Assert.True(commands.Enqueue("text res://scene.tscn latest"));
        Assert.True(commands.Enqueue("load next"));
        for (var i = 0; i < 10_000; i++)
            Assert.True(commands.Enqueue($"view {i % 2} 0 3d"));
        foreach (var expected in new[] { "text res://scene.tscn latest", "load next", "view 1 0 3d" })
        {
            Assert.True(commands.TryDequeue(out var actual));
            Assert.Equal(expected, actual);
        }
        Assert.False(commands.TryDequeue(out _));
    }
}
