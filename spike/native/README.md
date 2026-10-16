# Native Wayland GPU-sharing spike

This is an experimental presentation backend, not yet selected by the scene editor. It reuses the existing 2dog
external-texture API rather than requiring changes to the Godot fork or loading Godot into Rider's process.

The producer renders a changing four-color scene on native Wayland/Vulkan. It exports an RGBA8 texture allocation
and sends its file descriptor to a separate JVM over a same-user Unix `SOCK_SEQPACKET` socket using `SCM_RIGHTS`.
The EGL/OpenGL ES consumer imports that memory with `GL_EXT_memory_object_fd`, samples it, and optionally presents
it into a Wayland subsurface owned by Rider's exact JBR 25.0.4/b508.27 runtime. The normal presentation path has no
CPU pixel readback. The regression reads four pixels to verify colors, orientation and updates.

## Running

Requires Linux, a working native Wayland display, .NET 10, a C11 compiler, and EGL/GLES/Vulkan development headers
(for example `libegl-dev`, `libgles-dev`, `libvulkan-dev` on Debian/Ubuntu). The GPU and driver must support opaque-fd
Vulkan export and OpenGL external-memory import on the same device; the probe rejects mismatched device/driver UUIDs.

```sh
# Import and verify in an offscreen EGL surface.
sh ./gradlew testLinuxGpuSharing

# Also present through an actual native Wayland JBR subsurface.
sh ./gradlew testLinuxGpuSharing -PMushWaylandGpuUi=true -PMushJbrHome=/path/to/Rider/jbr
```

`spike/native/build.sh` builds `build/native/libmush_gpu.so`. `CC` and `CPPFLAGS` can select a compiler and additional
header locations. The native library is loaded only by the opt-in spike; it is not packaged or enabled for regular
scene editors. The JBR subsurface adapter uses private APIs and explicit module opens only in the probe launch.

Both producer and consumer stay in their own processes. GPU operations remain on each process's owner thread;
the consumer never waits for producer I/O while holding the AWT lock. The socket lives in a user-only temporary
directory, received fds are close-on-exec, and malformed/truncated packets release received descriptors.

## Current frame contract

Version 1 uses a 72-byte little-endian metadata packet:

| Offset | Value |
| --- | --- |
| 0 | `MGU1` magic (uint32) |
| 4 | version (uint16), message kind (uint16) |
| 8 | resource generation (uint64) |
| 16 | frame sequence (uint64) |
| 24 | width and height (uint32 each) |
| 32 | external allocation size (uint64) |
| 40 | device UUID (16 bytes) |
| 56 | driver UUID (16 bytes) |

Only a Resource packet carries an fd. Image format is fixed to RGBA8 UNORM, optimal tiling, zero allocation offset,
and top-left origin in this version. The engine's `ExternalTexturePresent` waits for GPU completion. The consumer
finishes its GL read before sending the matching Release packet. No new frame or allocation retirement is allowed
while that frame is leased. Resize waits for release and advances the resource generation. The probe also suspends
the producer with SIGSTOP and checks that the completed image and Swing event queue remain usable.

## Godot / 2dog changes to discuss

No changes were necessary for the first proof. The current API is not yet a sufficient portable contract for a
production Vulkan/OpenGL importer; successful tests on one driver do not prove complete external ownership rules.

1. **Exported image metadata.** Expose an `external_texture_get_info(RID)`-style result including actual dedicated
   allocation status, format, tiling, allocation size/offset, device/driver UUIDs and supported handoff mechanisms.
   The spike obtains UUIDs through `GetDriverResource(PhysicalDevice)` and a small Vulkan helper. It follows
   Avalonia's existing allocation assumptions, which must be made explicit before claiming support on other drivers.
2. **Explicit external ownership and layouts.** Add opt-in release/reclaim operations tracked by Godot's render
   graph, including the Vulkan external queue family and the agreed image layout. A CPU completion wait alone does
   not describe these transitions. Changing Vulkan image state behind Godot's resource tracker in Mush is fragile.
3. **Asynchronous synchronization.** Export/import GPU semaphores so producer and consumer can order reads/writes
   without `ExternalTexturePresent`'s all-frames stall or the consumer's `glFinish`. Binary semaphore pairs are a
   candidate for broad `GL_EXT_semaphore_fd` interop; timeline synchronization should be capability-negotiated.
   This can follow metadata/ownership work rather than expanding the first API change.
4. **Shared 2dog presentation helpers.** Consider extracting the export metadata, ownership and synchronization
   contract from the Avalonia integration into a toolkit-independent helper. Keep compositor-specific imports in
   Avalonia and Mush respectively, and preserve the existing Avalonia path while validating the new opt-in contract.

The remaining Mush work includes editor lifecycle and input integration, clipping against Swing overlays/popups,
HiDPI/monitor moves, device loss, and packing the native helper. A native subsurface is above its parent surface;
it does not automatically inherit Swing's z-order. Do not enable it in scene editors until those semantics are proved.

The initial offscreen and JBR subsurface probes passed under WSLg using Mesa llvmpipe/lavapipe (software devices).
Hardware-driver validation, especially dedicated allocations and external image handoff, is still required.
