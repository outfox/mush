package dev.twodog.mush.scene

import org.junit.Assert.*
import org.junit.Test
import javax.swing.SwingUtilities

class ViewportViewControlsTest {
    @Test fun `visibility and movement are independent and older replies cannot undo a click`() = SwingUtilities.invokeAndWait {
        val changes = mutableListOf<ViewState>()
        val controls = ViewportViewControls(4, changes::add)
        assertFalse(controls.move3D.isEnabled)
        controls.initialize(ViewState(true, true, ViewMode.THREE_D))
        assertTrue(controls.move3D.isSelected)
        assertTrue(changes.isEmpty())

        controls.show3D.doClick(0)
        assertTrue(controls.move3D.isSelected)
        assertEquals("view 1 0 3d", changes.last().command())
        controls.move2D.doClick(0)
        assertFalse(controls.move3D.isSelected)
        assertEquals("view 1 0 2d", changes.last().command())
        controls.show2D.doClick(0)
        assertEquals("view 0 0 2d", changes.last().command())

        controls.initialize(ViewState(true, true, ViewMode.THREE_D))
        assertFalse(controls.show2D.isSelected)
        assertFalse(controls.show3D.isSelected)
        assertTrue(controls.move2D.isSelected)
        controls.setAvailable(false)
        assertFalse(controls.show2D.isEnabled)
        assertFalse(controls.move2D.isEnabled)
    }
}
