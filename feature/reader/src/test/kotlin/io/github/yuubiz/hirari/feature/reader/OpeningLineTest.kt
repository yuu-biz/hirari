package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.OpenStep
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The name and progress shown while a book opens (docs/spec.md section 4.8, 本を開いている間). */
class OpeningLineTest {
    private val mb = 1024L * 1024

    @Test
    fun `a step without progress has a name only`() {
        for ((step, name) in listOf(
            OpenStep.Preparing to R.string.opening_preparing,
            OpenStep.Connecting to R.string.opening_connecting,
            OpenStep.ListingFolder to R.string.opening_listing_folder,
            OpenStep.OpeningPdf to R.string.opening_pdf,
        )) {
            val line = step.line()
            assertEquals(name, line.name)
            assertNull(line.detail)
            assertNull(line.fraction)
        }
    }

    @Test
    fun `a copy with a total shows megabytes with grouping and the share`() {
        val line = OpenStep.Copying(bytes = 120 * mb + mb / 2, total = 1700 * mb).line()
        assertEquals(R.string.opening_copying, line.name)
        assertEquals(R.string.opening_copied_of, line.detail)
        assertEquals(listOf("120.5", "1,700.0", 7), line.args)
        assertEquals(0.0709f, line.fraction!!, 0.0001f)
    }

    @Test
    fun `a copy whose provider reports no size shows the megabytes alone and no ring`() {
        for (total in listOf(null, 0L)) {
            val line = OpenStep.Copying(bytes = 120 * mb + mb / 2, total = total).line()
            assertEquals(R.string.opening_copied, line.detail)
            assertEquals(listOf<Any>("120.5"), line.args)
            assertNull(line.fraction)
        }
    }

    @Test
    fun `a copy past its reported size stops at a whole share`() {
        val line = OpenStep.Copying(bytes = 11 * mb, total = 10 * mb).line()
        assertEquals(listOf("11.0", "10.0", 100), line.args)
        assertEquals(1f, line.fraction)
    }

    @Test
    fun `a listing with a fraction shows the count and the share`() {
        val line = OpenStep.Listing(entries = 1234, fraction = 0.45f).line()
        assertEquals(R.string.opening_listing, line.name)
        assertEquals(R.string.opening_items_of, line.detail)
        assertEquals(listOf("1,234", 45), line.args)
        assertEquals(0.45f, line.fraction)
    }

    @Test
    fun `a listing held at 99 percent shows 99`() {
        assertEquals(listOf("6,000", 99), OpenStep.Listing(entries = 6000, fraction = 0.99f).line().args)
    }

    @Test
    fun `a listing without a fraction shows the count alone`() {
        val line = OpenStep.Listing(entries = 1234, fraction = null).line()
        assertEquals(R.string.opening_items, line.detail)
        assertEquals(listOf<Any>("1,234"), line.args)
        assertNull(line.fraction)
    }

    @Test
    fun `a listing that has told no count shows no zero`() {
        val line = OpenStep.Listing(entries = 0, fraction = null).line()
        assertEquals(R.string.opening_listing, line.name)
        assertNull(line.detail)
        assertNull(line.fraction)
    }
}
