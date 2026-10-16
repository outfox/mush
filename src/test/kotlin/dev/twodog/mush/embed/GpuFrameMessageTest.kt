package dev.twodog.mush.embed

import dev.twodog.mush.embed.gpu.GpuFrameMessage
import org.junit.Assert.*
import org.junit.Test

class GpuFrameMessageTest {
    @Test fun `resource metadata matches the fixed C sharp wire layout`() {
        val device = ByteArray(16) { it.toByte() }
        val message = GpuFrameMessage(GpuFrameMessage.RESOURCE, 9, 27, 1920, 1080, 1L shl 34, device, device.reversedArray())
        val bytes = message.encode()
        assertArrayEquals(byteArrayOf(0x4d, 0x47, 0x55, 0x31, 1, 0, 1, 0), bytes.copyOf(8))
        val decoded = GpuFrameMessage.decode(bytes, bytes.size)
        assertEquals(message.memorySize, decoded.memorySize)
        assertEquals(message.generation, decoded.generation)
        assertArrayEquals(device, decoded.deviceUuid)
        assertArrayEquals(message.driverUuid, decoded.driverUuid)
    }

    @Test fun `invalid versions resource sizes and GPU identities fail before native import`() {
        val bytes = GpuFrameMessage(GpuFrameMessage.RESOURCE, 1, 0, 64, 64, 65536).encode()
        assertThrows(IllegalArgumentException::class.java) { GpuFrameMessage.decode(bytes, 71) }
        bytes[4] = 2
        assertThrows(IllegalArgumentException::class.java) { GpuFrameMessage.decode(bytes, bytes.size) }
        bytes[4] = 1; bytes[24] = 0
        assertThrows(IllegalArgumentException::class.java) { GpuFrameMessage.decode(bytes, bytes.size) }
        assertThrows(IllegalArgumentException::class.java) { GpuFrameMessage(1, 1, 0, deviceUuid = ByteArray(17)).encode() }
    }
}
