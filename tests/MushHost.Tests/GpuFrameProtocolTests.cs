using Mush.Spike;
using Xunit;

namespace MushHost.Tests;

public class GpuFrameProtocolTests
{
    [Fact] public void WireMetadataPreservesWideAllocationSizesAndGpuIdentity()
    {
        var device = Enumerable.Range(0, 16).Select(i => (byte)i).ToArray();
        var original = new GpuMessage(GpuMessageKind.Resource, 9, 27, 1920, 1080, 1ul << 34, device, device.Reverse().ToArray());
        var decoded = GpuMessage.Decode(original.Encode());
        Assert.Equal(original.MemorySize, decoded.MemorySize);
        Assert.Equal(original.Generation, decoded.Generation);
        Assert.Equal(original.Sequence, decoded.Sequence);
        Assert.Equal(original.Width, decoded.Width);
        Assert.Equal(original.Height, decoded.Height);
        Assert.Equal(original.DeviceUuid, decoded.DeviceUuid);
        Assert.Equal(original.DriverUuid, decoded.DriverUuid);
    }

    [Fact] public void InvalidHeadersAndTruncatedPacketsAreRejected()
    {
        var bytes = new GpuMessage(GpuMessageKind.Release, 1, 1).Encode();
        Assert.Throws<FormatException>(() => GpuMessage.Decode(bytes.AsSpan(0, bytes.Length - 1)));
        bytes[4] = 2;
        Assert.Throws<FormatException>(() => GpuMessage.Decode(bytes));
        bytes[4] = 1; bytes[6] = 99;
        Assert.Throws<FormatException>(() => GpuMessage.Decode(bytes));
    }

    [Fact] public void MalformedGpuIdentityCannotOverwriteOtherWireFields()
    {
        var message = new GpuMessage(GpuMessageKind.Resource, 1, 0, 64, 64, 65536, new byte[17], new byte[16]);
        Assert.Throws<ArgumentException>(() => message.Encode());
    }

    [Fact] public void OnlyTheExactFrameReleasePermitsReuseOrResize()
    {
        var lease = new GpuFrameLease();
        lease.NewResource();
        var frame = lease.Publish();
        Assert.Throws<InvalidOperationException>(() => lease.Publish());
        Assert.Throws<InvalidOperationException>(() => lease.NewResource());
        Assert.Throws<InvalidOperationException>(() => lease.Release(new GpuMessage(GpuMessageKind.Release, 0, frame.Sequence)));
        Assert.Throws<InvalidOperationException>(() => lease.Release(new GpuMessage(GpuMessageKind.Release, frame.Generation, frame.Sequence + 1)));
        lease.Release(frame with { Kind = GpuMessageKind.Release });
        Assert.Throws<InvalidOperationException>(() => lease.Release(frame with { Kind = GpuMessageKind.Release }));
        lease.NewResource();
        Assert.Equal(2ul, lease.Generation);
        Assert.False(lease.IsHeld);
    }
}
