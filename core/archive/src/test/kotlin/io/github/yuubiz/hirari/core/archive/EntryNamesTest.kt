package io.github.yuubiz.hirari.core.archive

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class EntryNamesTest {
    private val cp932 = EntryNames.CP932

    private fun names(vararg names: String, charset: Charset = Charsets.UTF_8) = names.map { it.toByteArray(charset) }

    private fun charsetOf(raw: List<ByteArray>, undetermined: Charset = cp932) = EntryNames.charsetOf(raw, undetermined)

    @Test
    fun `names that are all UTF-8 decode as UTF-8`() {
        val raw = names("表紙.jpg", "第1話/001.jpg")
        assertEquals(Charsets.UTF_8, charsetOf(raw))
        assertEquals(listOf("表紙.jpg", "第1話/001.jpg"), raw.map { EntryNames.decode(it, Charsets.UTF_8) })
    }

    @Test
    fun `ASCII names decode as UTF-8`() {
        assertEquals(Charsets.UTF_8, charsetOf(names("001.jpg", "a\\b~.jpg")))
    }

    @Test
    fun `CP932 names decode as CP932, including trail bytes equal to a backslash`() {
        val raw = names("表紙.jpg", "ソ.jpg", "001.jpg", charset = cp932)
        assertEquals(0x5c, raw[0][1].toInt())

        val charset = charsetOf(raw)

        assertEquals(cp932, charset)
        assertEquals(listOf("表紙.jpg", "ソ.jpg", "001.jpg"), raw.map { EntryNames.decode(it, charset) })
    }

    @Test
    fun `EUC-JP names that are not CP932 are detected as EUC-JP`() {
        val raw = names("第一巻/第一話 はじまりの朝.jpg", "第一巻/第二話 わかれの夜.jpg", "第一巻/表紙.jpg", charset = EntryNames.EUC_JP)

        assertEquals(EntryNames.EUC_JP, charsetOf(raw))
    }

    @Test
    fun `GB18030 names that are not CP932 are detected as GB18030`() {
        val raw = names("第一卷/第一话 开始的早晨.jpg", "第一卷/第二话 离别的夜晚.jpg", "第一卷/封面.jpg", charset = EntryNames.GB18030)

        assertEquals(EntryNames.GB18030, charsetOf(raw))
    }

    @Test
    fun `names the detector cannot place go to the undetermined charset`() {
        val bad = "bad".toByteArray() + byteArrayOf(0x81.toByte()) + ".jpg".toByteArray()
        val raw = names("表紙.jpg", charset = cp932) + listOf(bad)

        assertEquals(Charsets.ISO_8859_1, charsetOf(raw, undetermined = Charsets.ISO_8859_1))
        assertEquals("bad�.jpg", EntryNames.decode(bad, cp932))
    }

    @Test
    fun `Big5 names that are not CP932 are detected as Big5`() {
        val raw = names("第一卷/第一話 開始的早晨.jpg", "第一卷/第二話 離別的夜晚.jpg", "第一卷/封面.jpg", charset = EntryNames.BIG5)

        assertEquals(EntryNames.BIG5, charsetOf(raw))
    }

    @Test
    fun `EUC-JP names mixing kanji-only names with kana are detected as EUC-JP`() {
        val raw = names("表紙.jpg", "目次.jpg", "第一話 はじまり/001.jpg", "第二話 ソラの国/001.jpg", charset = EntryNames.EUC_JP)

        assertEquals(EntryNames.EUC_JP, charsetOf(raw))
    }

    @Test
    fun `a short name that reads as common kanji and as common hanzi goes to Japanese`() {
        // 一 in EUC-JP and a level 1 hanzi in GB18030, but in CP932 a lead byte before a dot.
        val raw = listOf(byteArrayOf(0xb0.toByte(), 0xec.toByte(), 0x2e))

        assertEquals(EntryNames.EUC_JP, charsetOf(raw, undetermined = Charsets.US_ASCII))
    }

    @Test
    fun `names no candidate decodes go to the undetermined charset`() {
        val raw = names("第一巻/表紙.jpg", charset = EntryNames.EUC_JP) + listOf(byteArrayOf(0xff.toByte(), 0xff.toByte()))

        assertEquals(Charsets.US_ASCII, charsetOf(raw, undetermined = Charsets.US_ASCII))
    }

    @Test
    fun `names that decode only into rare characters go to the undetermined charset`() {
        val raw = names("𠀀𠀁.jpg", charset = EntryNames.GB18030)

        assertEquals(Charsets.US_ASCII, charsetOf(raw, undetermined = Charsets.US_ASCII))
    }

    @Test
    fun `a mix of UTF-8 and CP932 names is not UTF-8`() {
        val raw = names("表紙.jpg", charset = cp932) + names("が.jpg")
        assertEquals(false, charsetOf(raw) == Charsets.UTF_8)
    }

    @Test
    fun `a RAR 4 name whose trail byte libarchive turned into a slash decodes as written`() {
        val written = "第1話\\表紙.jpg".toByteArray(cp932)
        val listed = written.map { if (it == '\\'.code.toByte()) '/'.code.toByte() else it }.toByteArray()

        val charset = EntryNames.charsetOf(listOf(listed), slashMayBeBackslash = true)

        assertEquals(cp932, charset)
        assertEquals("第1話/表紙.jpg", EntryNames.decode(listed, charset, slashMayBeBackslash = true))
        assertEquals("第1話/表紙.jpg", EntryNames.decode("第1話/表紙.jpg".toByteArray(cp932), cp932, slashMayBeBackslash = true))
    }

    @Test
    fun `an empty name, which libarchive gives for an invalid flagged name, decodes to a replacement character`() {
        assertEquals(Charsets.UTF_8, charsetOf(listOf(ByteArray(0))))
        assertEquals("�", EntryNames.decode(ByteArray(0), Charsets.UTF_8))
    }
}
