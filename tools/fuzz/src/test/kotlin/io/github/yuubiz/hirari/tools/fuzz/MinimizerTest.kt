package io.github.yuubiz.hirari.tools.fuzz

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MinimizerTest {
    private fun contains(needle: ByteArray, haystack: ByteArray): Boolean =
        haystack.indices.any { at -> at + needle.size <= haystack.size && needle.indices.all { haystack[at + it] == needle[it] } }

    @Test
    fun `an input that fails because of three bytes shrinks to those three bytes`() {
        val trigger = byteArrayOf(7, 8, 9)
        val input = ByteArray(5000) { (it % 5).toByte() } + trigger + ByteArray(3000) { 1 }
        val minimizer = Minimizer { candidates -> candidates.indexOfFirst { contains(trigger, it) }.takeIf { it >= 0 } }
        assertContentEquals(trigger, minimizer.minimize(input))
    }

    @Test
    fun `bytes that must stay apart stay in order`() {
        val input = ByteArray(300) { 0 }.also { it[10] = 5; it[250] = 6 }
        val minimizer = Minimizer { candidates ->
            candidates.indexOfFirst { c -> c.indexOf(5).let { a -> a >= 0 && c.indexOf(6) > a } }.takeIf { it >= 0 }
        }
        val result = minimizer.minimize(input)
        assertEquals(listOf<Byte>(5, 6), result.filter { it != 0.toByte() })
        assertTrue(result.size <= 3, "left ${result.size} bytes")
    }

    @Test
    fun `an input that never fails comes back whole after one round at each granularity`() {
        val input = ByteArray(64) { it.toByte() }
        var rounds = 0
        val minimizer = Minimizer { rounds++; null }
        assertContentEquals(input, minimizer.minimize(input))
        assertEquals(6, rounds)
    }

    @Test
    fun `the number of rounds is bounded`() {
        val input = ByteArray(10_000) { it.toByte() }
        val minimizer = Minimizer(maxRounds = 5) { 0 }
        minimizer.minimize(input)
        assertEquals(5, minimizer.rounds)
    }
}
