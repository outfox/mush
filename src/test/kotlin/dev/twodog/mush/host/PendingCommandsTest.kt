package dev.twodog.mush.host

import org.junit.Assert.*
import org.junit.Test

class PendingCommandsTest {
    @Test fun `latest state follows earlier controls without accumulating snapshots`() {
        val queue = PendingCommands()
        repeat(10_000) {
            assertTrue(queue.offer("text res://scene.tscn $it"))
            assertTrue(queue.offer("stats"))
        }
        assertEquals(2, queue.size)
        assertEquals("text res://scene.tscn 9999", queue.poll())
        assertEquals("stats", queue.poll())
        assertNull(queue.poll())
    }

    @Test fun `a replacement does not jump ahead of controls`() {
        val queue = PendingCommands(3)
        assertTrue(queue.offer("text old"))
        assertTrue(queue.offer("load first"))
        assertTrue(queue.offer("load second"))
        assertFalse(queue.offer("load rejected"))
        assertTrue(queue.offer("text newest"))
        assertEquals("load first", queue.poll())
        assertEquals("load second", queue.poll())
        assertEquals("text newest", queue.poll())
    }

    @Test fun `a drag-resize leaves only the size the host still has to reach`() {
        val queue = PendingCommands()
        assertTrue(queue.offer("load scene"))
        repeat(10_000) { assertTrue(queue.offer("resolution ${800 + it}x600")) }
        assertEquals(2, queue.size)
        assertEquals("load scene", queue.poll())
        assertEquals("resolution 10799x600", queue.poll())
        assertNull(queue.poll())
    }

    @Test fun `view changes stay bounded alongside document state and earlier controls`() {
        val queue = PendingCommands(3)
        assertTrue(queue.offer("view 1 1 2d"))
        assertTrue(queue.offer("text res://scene.tscn latest"))
        assertTrue(queue.offer("load next"))
        repeat(10_000) { assertTrue(queue.offer("view ${it % 2} 0 3d")) }
        assertEquals(3, queue.size)
        assertEquals("text res://scene.tscn latest", queue.poll())
        assertEquals("load next", queue.poll())
        assertEquals("view 1 0 3d", queue.poll())
    }
}
