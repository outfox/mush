package dev.twodog.mush.embed.gpu

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Experimental same-user GPU transport/importer. It is not selected by the scene editor yet. */
class LinuxGpuBridge {
    private val api = Native.load("mush_gpu", Api::class.java)

    fun listen(path: String): Int = checked(api.mush_channel_listen(path))
    fun accept(server: Int, timeoutMs: Int): Int = checked(api.mush_channel_accept(server, timeoutMs))
    fun closeFd(fd: Int) = api.mush_close_fd(fd)
    fun error(): String = api.mush_gpu_error()

    fun receive(channel: Int, timeoutMs: Int): Packet? {
        val data = ByteArray(GpuFrameMessage.SIZE)
        val fd = IntByReference(-1)
        val count = checked(api.mush_channel_receive(channel, data, data.size, fd, timeoutMs))
        if (count == 0) return null
        try {
            val message = GpuFrameMessage.decode(data, count)
            require((message.kind == GpuFrameMessage.RESOURCE) == (fd.value >= 0)) { "Unexpected/missing GPU resource descriptor" }
            return Packet(message, fd.value)
        } catch (e: Exception) {
            closeFd(fd.value)
            throw e
        }
    }

    fun send(channel: Int, message: GpuFrameMessage) {
        val data = message.encode()
        check(checked(api.mush_channel_send(channel, data, data.size, -1)) == data.size) { "GPU channel is full" }
    }

    fun create(display: Long, surface: Long, width: Int, height: Int): Pointer =
        api.mush_gpu_create(if (display == 0L) null else Pointer(display), if (surface == 0L) null else Pointer(surface), width, height)
            ?: error(error())

    fun info(gpu: Pointer): String = api.mush_gpu_info(gpu)
    /** Consumes the received fd on success or failure. */
    fun importResource(gpu: Pointer, packet: Packet) {
        val m = packet.message
        require(m.kind == GpuFrameMessage.RESOURCE && packet.fd >= 0)
        checked(api.mush_gpu_import(gpu, packet.fd, m.memorySize, m.width, m.height, m.deviceUuid, m.driverUuid))
    }
    fun draw(gpu: Pointer) { checked(api.mush_gpu_draw(gpu)) }
    fun present(gpu: Pointer) { checked(api.mush_gpu_present(gpu)) }
    fun placeAbove(subsurface: Long, parent: Long) { checked(api.mush_wayland_subsurface_above(Pointer(subsurface), Pointer(parent))) }
    /** Probe only: reads a single pixel; normal presentation stays on the GPU. */
    fun sample(gpu: Pointer, x: Int, y: Int): ByteArray = ByteArray(4).also { checked(api.mush_gpu_sample(gpu, x, y, it)) }
    fun destroy(gpu: Pointer) = api.mush_gpu_destroy(gpu)

    private fun checked(result: Int): Int {
        check(result >= 0) { error() }
        return result
    }

    data class Packet(val message: GpuFrameMessage, val fd: Int)

    @Suppress("FunctionName")
    private interface Api : Library {
        fun mush_gpu_error(): String
        fun mush_channel_listen(path: String): Int
        fun mush_channel_accept(server: Int, timeoutMs: Int): Int
        fun mush_channel_receive(channel: Int, data: ByteArray, capacity: Int, fd: IntByReference, timeoutMs: Int): Int
        fun mush_channel_send(channel: Int, data: ByteArray, length: Int, fd: Int): Int
        fun mush_close_fd(fd: Int)
        fun mush_gpu_create(display: Pointer?, surface: Pointer?, width: Int, height: Int): Pointer?
        fun mush_gpu_info(gpu: Pointer): String
        fun mush_gpu_import(gpu: Pointer, fd: Int, memorySize: Long, width: Int, height: Int, device: ByteArray, driver: ByteArray): Int
        fun mush_gpu_draw(gpu: Pointer): Int
        fun mush_gpu_present(gpu: Pointer): Int
        fun mush_wayland_subsurface_above(subsurface: Pointer, parent: Pointer): Int
        fun mush_gpu_sample(gpu: Pointer, x: Int, y: Int, rgba: ByteArray): Int
        fun mush_gpu_destroy(gpu: Pointer)
    }
}

data class GpuFrameMessage(
    val kind: Int, val generation: Long, val sequence: Long,
    val width: Int = 0, val height: Int = 0, val memorySize: Long = 0,
    val deviceUuid: ByteArray = ByteArray(16), val driverUuid: ByteArray = ByteArray(16),
) {
    fun encode(): ByteArray = ByteBuffer.allocate(SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        require(deviceUuid.size == 16 && driverUuid.size == 16) { "GPU UUIDs must be 16 bytes" }
        putInt(MAGIC); putShort(1); putShort(kind.toShort()); putLong(generation); putLong(sequence)
        putInt(width); putInt(height); putLong(memorySize); put(deviceUuid); put(driverUuid)
    }.array()

    companion object {
        const val SIZE = 72
        const val RESOURCE = 1
        const val FRAME = 2
        const val RELEASE = 3
        const val RESIZE = 4
        const val STOP = 5
        private const val MAGIC = 0x3155474d

        @JvmStatic fun decode(bytes: ByteArray, count: Int): GpuFrameMessage {
            require(count == SIZE && bytes.size >= SIZE) { "Invalid GPU packet size" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(b.int == MAGIC && b.short.toInt() == 1) { "Invalid GPU protocol/version" }
            val kind = b.short.toInt()
            require(kind in RESOURCE..STOP) { "Unknown GPU packet" }
            val generation = b.long; val sequence = b.long
            val width = b.int; val height = b.int; val memorySize = b.long
            val device = ByteArray(16).also(b::get); val driver = ByteArray(16).also(b::get)
            if (kind == RESOURCE) require(width in 1..4096 && height in 1..4096 && memorySize > 0) { "Invalid GPU allocation metadata" }
            return GpuFrameMessage(kind, generation, sequence, width, height, memorySize, device, driver)
        }
    }
}
