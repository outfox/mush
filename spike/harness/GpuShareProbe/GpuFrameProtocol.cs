using System.Buffers.Binary;

namespace Mush.Spike;

internal enum GpuMessageKind : ushort { Resource = 1, Frame = 2, Release = 3, Resize = 4, Stop = 5 }

/// <summary>Versioned fixed-size metadata on a same-user Unix SOCK_SEQPACKET channel. Only Resource carries an fd.</summary>
internal sealed record GpuMessage(GpuMessageKind Kind, ulong Generation, ulong Sequence, uint Width = 0, uint Height = 0,
    ulong MemorySize = 0, byte[]? DeviceUuid = null, byte[]? DriverUuid = null)
{
    public const int Size = 72;
    private const uint Magic = 0x3155474d; // MGU1, little endian

    public byte[] Encode()
    {
        if ((DeviceUuid is { Length: not 16 }) || (DriverUuid is { Length: not 16 }))
            throw new ArgumentException("GPU UUIDs must be exactly 16 bytes.");
        var bytes = new byte[Size];
        BinaryPrimitives.WriteUInt32LittleEndian(bytes, Magic);
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(4), 1);
        BinaryPrimitives.WriteUInt16LittleEndian(bytes.AsSpan(6), (ushort)Kind);
        BinaryPrimitives.WriteUInt64LittleEndian(bytes.AsSpan(8), Generation);
        BinaryPrimitives.WriteUInt64LittleEndian(bytes.AsSpan(16), Sequence);
        BinaryPrimitives.WriteUInt32LittleEndian(bytes.AsSpan(24), Width);
        BinaryPrimitives.WriteUInt32LittleEndian(bytes.AsSpan(28), Height);
        BinaryPrimitives.WriteUInt64LittleEndian(bytes.AsSpan(32), MemorySize);
        DeviceUuid?.CopyTo(bytes, 40);
        DriverUuid?.CopyTo(bytes, 56);
        return bytes;
    }

    public static GpuMessage Decode(ReadOnlySpan<byte> bytes)
    {
        if (bytes.Length != Size || BinaryPrimitives.ReadUInt32LittleEndian(bytes) != Magic ||
            BinaryPrimitives.ReadUInt16LittleEndian(bytes[4..]) != 1)
            throw new FormatException("Invalid GPU frame protocol header or version.");
        var kind = (GpuMessageKind)BinaryPrimitives.ReadUInt16LittleEndian(bytes[6..]);
        if (!Enum.IsDefined(kind)) throw new FormatException("Unknown GPU frame message.");
        return new GpuMessage(kind, BinaryPrimitives.ReadUInt64LittleEndian(bytes[8..]),
            BinaryPrimitives.ReadUInt64LittleEndian(bytes[16..]), BinaryPrimitives.ReadUInt32LittleEndian(bytes[24..]),
            BinaryPrimitives.ReadUInt32LittleEndian(bytes[28..]), BinaryPrimitives.ReadUInt64LittleEndian(bytes[32..]),
            bytes.Slice(40, 16).ToArray(), bytes.Slice(56, 16).ToArray());
    }
}

/// <summary>A single acknowledged GPU frame. A stalled consumer cannot cause producer overwrite or queue growth.</summary>
internal sealed class GpuFrameLease
{
    public ulong Generation { get; private set; }
    public ulong Sequence { get; private set; }
    public bool IsHeld { get; private set; }

    public void NewResource()
    {
        if (IsHeld) throw new InvalidOperationException("Cannot retire a texture still held by the consumer.");
        Generation++;
    }

    public GpuMessage Publish()
    {
        if (IsHeld) throw new InvalidOperationException("The previous GPU frame is still held.");
        IsHeld = true;
        return new GpuMessage(GpuMessageKind.Frame, Generation, ++Sequence);
    }

    public void Release(GpuMessage message)
    {
        if (!IsHeld || message.Kind != GpuMessageKind.Release || message.Generation != Generation || message.Sequence != Sequence)
            throw new InvalidOperationException("Stale, duplicate or mismatched GPU frame release.");
        IsHeld = false;
    }
}
