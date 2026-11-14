package io.github.yuubiz.hirari.feature.reader

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 展開の状況 (docs/spec.md section 4.8). */
class ExtractionProgressTest {
    @Test
    fun `the numerator counts the pass from 1 and the denominator is the last waiting entry in storage order`() {
        assertEquals(ExtractionProgress(120, 340), extractionProgress(passAt = 119, waiting = listOf(339, 12)))
    }

    @Test
    fun `a pass that starts over counts from 1 again`() {
        assertEquals(ExtractionProgress(1, 340), extractionProgress(passAt = 0, waiting = listOf(339)))
    }

    @Test
    fun `a pass at the last entry a page needs reads equal`() {
        assertEquals(ExtractionProgress(340, 340), extractionProgress(passAt = 339, waiting = listOf(339)))
    }

    @Test
    fun `nothing shows for a book that is not extracted`() {
        assertNull(extractionProgress(passAt = null, waiting = listOf(3)))
    }

    @Test
    fun `nothing shows when no page waits or the pass is past the last one that does`() {
        assertNull(extractionProgress(passAt = 5, waiting = emptyList()))
        assertNull(extractionProgress(passAt = 10, waiting = listOf(3, 9)))
    }

    @Test
    fun `entries show with a thousands separator`() {
        assertEquals("1,234", count(1234))
    }
}
