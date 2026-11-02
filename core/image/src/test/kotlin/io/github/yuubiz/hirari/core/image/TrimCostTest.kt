package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.TrimStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cost of reading the margins of a typical page at the analysis size, on the JVM. */
class TrimCostTest {
    @Test
    fun aTypicalPageIsReadInAFewMilliseconds() {
        val page = portrait(ORDINARY) { grain(12, seed = 3) }.luma()
        val argb = IntArray(page.pixels.size) { val v = page.pixels[it].toInt() and 0xFF; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val strengths = TrimStrength.entries
        fun once(): Int {
            val scan = scanMargins(LumaImage.fromArgb(page.width, page.height, argb), PORTRAIT_PAGE)
            return strengths.sumOf { scan.trim(it).cut.left }
        }
        fun floor(): Int {
            var sum = 0
            for (b in page.pixels) sum += b.toInt() and 0xFF
            return sum
        }
        val expected = once()
        assertEquals(0 + 67 + 89 + 100 + 112, expected)
        val scans = timed(expected) { once() }
        val passes = timed(floor()) { floor() }
        println("trim scan of ${page.width}x${page.height}, all strengths: $scans; one pass over the pixels: $passes")
        assertTrue("median ${scans.median} ms", scans.median < 20.0)
    }

    private class Timing(val millis: List<Double>) {
        val median: Double get() = millis.sorted()[millis.size / 2]

        override fun toString(): String {
            val sorted = millis.sorted()
            fun at(q: Double) = "%.3f".format(sorted[((sorted.size - 1) * q).toInt()])
            return "median ${at(0.5)} ms, p10 ${at(0.1)}, p90 ${at(0.9)}, min ${at(0.0)}, max ${at(1.0)} over ${millis.size} runs"
        }
    }

    private inline fun timed(expected: Int, run: () -> Int): Timing {
        repeat(WARMUP) { check(run() == expected) }
        val millis = List(RUNS) {
            val start = System.nanoTime()
            val result = run()
            val elapsed = (System.nanoTime() - start) / 1e6
            check(result == expected)
            elapsed
        }
        return Timing(millis)
    }

    private companion object {
        const val WARMUP = 300
        const val RUNS = 500
    }
}
