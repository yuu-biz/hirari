package io.github.yuubiz.hirari.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlin.math.sign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NaturalOrderTest {
    private fun assertSorted(vararg expected: String) {
        assertEquals(expected.toList(), expected.reversed().sortedWith(NaturalOrder))
        assertEquals(expected.toList(), expected.toList().shuffled(java.util.Random(1)).sortedWith(NaturalOrder))
    }

    @Test
    fun numbersCompareByValue() = assertSorted("1.jpg", "2.jpg", "10.jpg")

    @Test
    fun volumeMarkersFollowTheirOrder() = assertSorted("作品 上.zip", "作品 中.zip", "作品 下.zip", "作品 外伝.zip")

    @Test
    fun allVolumeMarkersInOrder() = assertSorted("作品 上", "作品 前", "作品 中", "作品 後", "作品 下", "作品 短", "作品 外")

    @Test
    fun kanjiNumeralsCompareByValue() = assertSorted("第三巻", "第十二巻", "第百巻")

    @Test
    fun kanjiNumeralsReadPositionalAndUnitForms() = assertSorted("一九九九年", "二千二十四年", "二〇二五年")

    @Test
    fun fewerDigitsFirstWhenValuesTie() = assertSorted("1.jpg", "01.jpg", "001.jpg")

    @Test
    fun fullWidthDigitsAreDigits() = assertSorted("２.jpg", "10.jpg", "１１.jpg")

    @Test
    fun caseIsIgnored() = assertSorted("a.jpg", "B.jpg", "c.jpg")

    @Test
    fun foldersCompareBeforeFiles() = assertSorted("ch1/9.jpg", "ch2/1.jpg", "ch10/1.jpg")

    @Test
    fun backslashSeparatesFolders() = assertSorted("ch2\\5.jpg", "ch10\\1.jpg")

    @Test
    fun tiesFallBackToTheOriginalName() {
        assertTrue(NaturalOrder.compare("A.jpg", "a.jpg") != 0)
        assertEquals(0, NaturalOrder.compare("a.jpg", "a.jpg"))
    }

    @Test
    fun pageTiesFallBackToRawBytes() {
        fun entry(raw: Int) = PageEntry(0, 0, byteArrayOf(raw.toByte()), "same", 0, 0, null)
        val sorted = listOf(entry(0xE3), entry(0x41)).sortedWith(NaturalPageOrder)
        assertEquals(listOf(0x41, 0xE3), sorted.map { it.rawName[0].toInt() and 0xFF })
    }

    private val names = Arb.list(Arb.element("a", "A", "b", "0", "1", "9", "一", "十", "上", "下", "中", "丌", "/", " "), 0..6)
        .map { it.joinToString("") }

    @Test
    fun isAntisymmetric(): Unit = runBlocking {
        checkAll(names, names) { a, b ->
            assertEquals(NaturalOrder.compare(a, b).sign, -NaturalOrder.compare(b, a).sign)
        }
    }

    @Test
    fun isTransitive(): Unit = runBlocking {
        checkAll(names, names, names) { a, b, c ->
            for ((x, y, z) in listOf(listOf(a, b, c), listOf(a, c, b), listOf(b, a, c), listOf(b, c, a), listOf(c, a, b), listOf(c, b, a))) {
                if (NaturalOrder.compare(x, y) <= 0 && NaturalOrder.compare(y, z) <= 0) {
                    assertTrue(NaturalOrder.compare(x, z) <= 0, "'$x' <= '$y' <= '$z' but not '$x' <= '$z'")
                }
            }
        }
    }
}
