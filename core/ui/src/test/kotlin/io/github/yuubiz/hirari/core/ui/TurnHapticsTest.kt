package io.github.yuubiz.hirari.core.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnHapticsTest {
    @Test
    fun `a turn onto another view ticks when the setting is on`() {
        assertTrue(TurnHaptics.due(enabled = true, before = 3, after = 4))
        assertTrue(TurnHaptics.due(enabled = true, before = listOf(2, 3), after = listOf(4, 5)))
    }

    @Test
    fun `the setting off keeps every turn quiet`() {
        assertFalse(TurnHaptics.due(enabled = false, before = 3, after = 4))
    }

    @Test
    fun `a turn that ends on the view it left does not tick`() {
        assertFalse(TurnHaptics.due(enabled = true, before = 3, after = 3))
        assertFalse(TurnHaptics.due(enabled = true, before = listOf(2, 3), after = listOf(2, 3)))
    }
}
