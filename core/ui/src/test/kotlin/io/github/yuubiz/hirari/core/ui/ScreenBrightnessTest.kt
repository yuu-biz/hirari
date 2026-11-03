package io.github.yuubiz.hirari.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenBrightnessTest {
    @Test
    fun `the brightness follows the device unless set and never goes black`() {
        assertNull(windowBrightness(null))
        assertEquals(0.5f, windowBrightness(50))
        assertEquals(0.01f, windowBrightness(0))
        assertEquals(1f, windowBrightness(100))
    }
}
