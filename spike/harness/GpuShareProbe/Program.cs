using System.Net.Sockets;
using System.Runtime.InteropServices;
using Godot;
using Mush.Spike;
using Engine = twodog.Engine;

// A real Godot producer for the cross-process GPU importer spike. The engine and every RD access stay on this thread.
if (!OperatingSystem.IsLinux() || args is not ["--socket", var socketPath])
    throw new ArgumentException("Linux only: GpuShareProbe --socket /path/to/socket");

using var channel = new Socket(AddressFamily.Unix, SocketType.Seqpacket, ProtocolType.Unspecified);
channel.Connect(new UnixDomainSocketEndPoint(socketPath));
var channelFd = checked((int)channel.SafeHandle.DangerousGetHandle());
using var engine = new Engine("mush GPU sharing probe", args:
    ["--display-driver", "wayland", "--hidden-window", "--resolution", "64x64", "--rendering-method", "mobile",
     "--rendering-driver", "vulkan", "--audio-driver", "Dummy"]);
engine.Start();
if (DisplayServer.GetName() != "Wayland") throw new InvalidOperationException("The GPU probe requires native Wayland; refusing an X11 fallback.");
DisplayServer.WindowSetVsyncMode(DisplayServer.VSyncMode.Disabled);
Godot.Engine.MaxFps = 120;
var root = engine.Tree.Root;
RenderingServer.ViewportSetUpdateMode(root.GetViewportRid(), RenderingServer.ViewportUpdateMode.Always);
var rd = RenderingServer.GetRenderingDevice() ?? throw new InvalidOperationException("Vulkan RenderingDevice is required.");
if ((rd.ExternalTextureGetSupportedHandleTypes() & (1u << (int)RenderingDevice.ExternalTextureShareHandleType.OpaqueFd)) == 0)
    throw new NotSupportedException("Godot's Vulkan device cannot export opaque-fd images.");
var deviceUuid = new byte[16];
var driverUuid = new byte[16];
Native.Uuid(rd.GetDriverResource(RenderingDevice.DriverResource.PhysicalDevice, default, 0), deviceUuid, driverUuid);
Console.WriteLine($"GPU producer: {rd.GetDeviceName()} device={Convert.ToHexString(deviceUuid)} driver={Convert.ToHexString(driverUuid)}");

var quadrants = new ColorRect[4];
for (var i = 0; i < quadrants.Length; i++) { quadrants[i] = new ColorRect(); root.AddChild(quadrants[i]); }
var colors = new[] { Colors.Red, Colors.Lime, Colors.Blue, Colors.White };
var lease = new GpuFrameLease();
Rid texture = default;
var width = 64u;
var height = 64u;
var warmup = 4;
GpuMessage? resize = null;
var packet = new byte[GpuMessage.Size];
var deadline = DateTime.UtcNow.AddSeconds(60);
try
{
    CreateResource();
    while (!engine.Iteration())
    {
        if (DateTime.UtcNow > deadline) throw new TimeoutException("GPU probe consumer stopped responding.");
        for (var i = 0; i < 16; i++)
        {
            var count = Native.Receive(channelFd, packet, packet.Length, out var receivedFd, 0);
            if (receivedFd >= 0) { Native.Close(receivedFd); throw new FormatException("Consumer control must not carry file descriptors."); }
            if (count == 0) break;
            Check(count);
            var message = GpuMessage.Decode(packet.AsSpan(0, count));
            switch (message.Kind)
            {
                case GpuMessageKind.Release:
                    lease.Release(message);
                    quadrants[0].Color = lease.Sequence % 2 == 0 ? Colors.Red : Colors.Cyan;
                    warmup = 2;
                    deadline = DateTime.UtcNow.AddSeconds(60);
                    break;
                case GpuMessageKind.Resize when message.Width is > 0 and <= 4096 && message.Height is > 0 and <= 4096:
                    resize = message; break;
                case GpuMessageKind.Stop: Console.WriteLine("GPU producer stopped cleanly."); return;
                default: throw new FormatException("Unexpected GPU control message.");
            }
        }
        if (lease.IsHeld) continue;
        if (resize is { } requested)
        {
            width = requested.Width; height = requested.Height; resize = null;
            DisplayServer.WindowSetSize(new Vector2I((int)width, (int)height));
            CreateResource();
            warmup = 4;
        }
        if (warmup-- > 0) continue;
        var source = RenderingServer.TextureGetRdTexture(root.GetTexture().GetRid());
        var result = rd.ExternalTexturePresent(source, texture);
        if (result != Error.Ok) throw new InvalidOperationException($"ExternalTexturePresent failed: {result}");
        // The current 2dog call completes GPU work before returning; consumer must finish its read before Release.
        Send(lease.Publish());
    }
}
finally { if (texture.IsValid) rd.FreeRid(texture); }

void CreateResource()
{
    lease.NewResource();
    if (texture.IsValid) rd.FreeRid(texture);
    texture = rd.ExternalTextureCreate(RenderingDevice.ExternalTextureShareHandleType.OpaqueFd,
        RenderingDevice.DataFormat.R8G8B8A8Unorm, width, height);
    if (!texture.IsValid) throw new InvalidOperationException("Shared GPU texture allocation failed.");
    var fd = checked((int)rd.ExternalTextureGetHandle(texture));
    try { Send(new GpuMessage(GpuMessageKind.Resource, lease.Generation, 0, width, height,
        rd.ExternalTextureGetMemorySize(texture), deviceUuid, driverUuid), fd); }
    finally { Native.Close(fd); }
    for (var i = 0; i < quadrants.Length; i++)
    {
        quadrants[i].Position = new Vector2((i % 2) * width / 2f, (i / 2) * height / 2f);
        quadrants[i].Size = new Vector2(width / 2f, height / 2f);
        quadrants[i].Color = colors[i];
    }
    quadrants[0].Color = lease.Sequence % 2 == 0 ? Colors.Red : Colors.Cyan;
}

void Send(GpuMessage message, int fd = -1)
{
    var bytes = message.Encode();
    var count = Native.Send(channelFd, bytes, bytes.Length, fd);
    Check(count);
    if (count != bytes.Length) throw new IOException("GPU control channel is full; refusing to overwrite a leased frame.");
}

void Check(int result)
{
    if (result < 0) throw new IOException(Marshal.PtrToStringUTF8(Native.Error()));
}

internal static class Native
{
    [DllImport("mush_gpu", EntryPoint = "mush_vulkan_uuid")] public static extern int Uuid(ulong physical, byte[] device, byte[] driver);
    [DllImport("mush_gpu", EntryPoint = "mush_channel_send")] public static extern int Send(int socket, byte[] data, int length, int fd);
    [DllImport("mush_gpu", EntryPoint = "mush_channel_receive")] public static extern int Receive(int socket, byte[] data, int capacity, out int fd, int timeoutMs);
    [DllImport("mush_gpu", EntryPoint = "mush_close_fd")] public static extern void Close(int fd);
    [DllImport("mush_gpu", EntryPoint = "mush_gpu_error")] public static extern nint Error();
}
