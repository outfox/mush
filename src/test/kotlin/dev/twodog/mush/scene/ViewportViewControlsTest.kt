package dev.twodog.mush.scene

import org.junit.Assert.*
import org.junit.Test

class ViewportViewControlsTest {
    @Test fun `visibility and movement are independent and older replies cannot undo an action`() {
        val changes = mutableListOf<ViewState>()
        val controls = ViewportViewControls(changes::add)
        assertTrue(controls.isAvailable)
        assertFalse(controls.hasViewState)
        assertTrue(changes.isEmpty())
        controls.initialize(ViewState(true, true, ViewMode.THREE_D))
        assertTrue(controls.isAvailable)
        assertTrue(changes.isEmpty())

        controls.setVisible(ViewMode.THREE_D, false)
        assertEquals(ViewMode.THREE_D, controls.state.mode)
        assertEquals("view 1 0 3d", changes.last().command())
        controls.selectMode(ViewMode.TWO_D)
        assertEquals("view 1 0 2d", changes.last().command())
        controls.setVisible(ViewMode.TWO_D, false)
        assertEquals("view 0 0 2d", changes.last().command())

        controls.initialize(ViewState(true, true, ViewMode.THREE_D))
        assertEquals(ViewState(false, false, ViewMode.TWO_D), controls.state)
        controls.setAvailable(false)
        controls.selectMode(ViewMode.THREE_D)
        assertFalse(controls.isAvailable)
        assertEquals(ViewMode.TWO_D, controls.state.mode)
    }

    @Test fun `startup visibility choices apply once without overriding automatic scene mode`() {
        val changes = mutableListOf<ViewState>()
        val controls = ViewportViewControls(changes::add)
        repeat(1000) { controls.setVisible(ViewMode.TWO_D, it % 2 == 0) }
        controls.setVisible(ViewMode.THREE_D, false)
        assertTrue(controls.isAvailable)
        assertEquals(ViewState(false, false, ViewMode.TWO_D), controls.state)
        assertTrue(changes.isEmpty())

        controls.initialize(ViewState(true, true, ViewMode.THREE_D))
        assertTrue(controls.hasViewState)
        assertEquals(listOf(ViewState(false, false, ViewMode.THREE_D)), changes)
        controls.initialize(ViewState(true, true, ViewMode.THREE_D))
        assertEquals(1, changes.size)
        assertEquals(changes.single(), controls.state)
    }

    @Test fun `explicit provisional camera selection survives scene detection`() {
        val changes = mutableListOf<ViewState>()
        val controls = ViewportViewControls(changes::add)
        controls.selectMode(ViewMode.TWO_D)
        assertTrue(changes.isEmpty())
        controls.initialize(ViewState(true, true, ViewMode.THREE_D))
        assertEquals(ViewMode.TWO_D, controls.state.mode)
        assertEquals(listOf(ViewState(true, true, ViewMode.TWO_D)), changes)
    }

    @Test fun `failed startup disables choices and late readiness does not reenable them`() {
        val changes = mutableListOf<ViewState>()
        val controls = ViewportViewControls(changes::add)
        controls.selectMode(ViewMode.THREE_D)
        controls.setAvailable(false)
        controls.selectMode(ViewMode.TWO_D)
        controls.setVisible(ViewMode.TWO_D, false)
        controls.initialize(ViewState(true, true, ViewMode.TWO_D))
        assertFalse(controls.isAvailable)
        assertEquals(ViewState(true, true, ViewMode.THREE_D), controls.state)
        assertTrue(changes.isEmpty())
    }
}
