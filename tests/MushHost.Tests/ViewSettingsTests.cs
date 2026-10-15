using Mush.Spike;
using Xunit;

namespace MushHost.Tests;

public sealed class ViewSettingsTests
{
    [Fact]
    public void NavigationDoesNotRequireItsRendererToBeVisible()
    {
        Assert.Equal(new ViewSettings(false, true, ViewMode.TwoD), ViewSettings.Parse("view 0 1 2d"));
        Assert.Equal(new ViewSettings(true, false, ViewMode.ThreeD), ViewSettings.Parse("view 1 0 3d"));
        Assert.Equal("view 0 0 3d", new ViewSettings(false, false, ViewMode.ThreeD).Command);
    }

    [Theory]
    [InlineData("view 1 1")]
    [InlineData("view 2 1 2d")]
    [InlineData("view 1 true 3d")]
    [InlineData("view 1 1 ui")]
    [InlineData("view 1 1 3d ignored")]
    public void MalformedSettingsAreRejected(string command) =>
        Assert.Throws<ArgumentException>(() => ViewSettings.Parse(command));
}
