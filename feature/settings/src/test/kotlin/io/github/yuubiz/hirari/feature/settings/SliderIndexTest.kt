package io.github.yuubiz.hirari.feature.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class SliderIndexTest {
    @Test
    fun `a value a hair off a tick is that tick`() {
        for (tick in 0..60) {
            assertEquals(tick, sliderIndex(Math.nextDown(tick.toFloat()), count = 61), "just below $tick")
            assertEquals(tick, sliderIndex(Math.nextUp(tick.toFloat()), count = 61), "just above $tick")
        }
    }

    @Test
    fun `the value Material 3 gives for tick 31 is tick 31`() {
        assertEquals(31, sliderIndex(30.999998f, count = 40))
    }

    @Test
    fun `a value between ticks goes to the nearer one`() {
        assertEquals(3, sliderIndex(3.4f, count = 10))
        assertEquals(4, sliderIndex(3.6f, count = 10))
    }

    @Test
    fun `a value outside the ticks is held to the first or the last`() {
        assertEquals(0, sliderIndex(-0.7f, count = 10))
        assertEquals(9, sliderIndex(12f, count = 10))
        assertEquals(0, sliderIndex(5f, count = 1))
    }
}
