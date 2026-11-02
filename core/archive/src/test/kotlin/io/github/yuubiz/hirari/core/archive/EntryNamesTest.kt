package io.github.yuubiz.hirari.core.archive

import org.junit.Assert.assertEquals
import org.junit.Test

class EntryNamesTest {
    private val cp932 = EntryNames.CP932

    private fun names(vararg names: String, charset: java.nio.charset.Charset = Charsets.UTF_8) =
        names.map { it.toByteArray(charset) }

    @Test
    fun `names that are all UTF-8 decode as UTF-8`() {
        val raw = names("表紙.jpg", "第1話/001.jpg")
        assertEquals(Charsets.UTF_8, EntryNames.charsetOf(raw))
        assertEquals(listOf("表紙.jpg", "第1話/001.jpg"), raw.map { EntryNames.decode(it, Charsets.UTF_8) })
    }

    @Test
    fun `ASCII names decode as UTF-8`() {
        assertEquals(Charsets.UTF_8, EntryNames.charsetOf(names("001.jpg", "a\\b~.jpg")))
    }

    @Test
    fun `CP932 names decode as CP932, including trail bytes equal to a backslash`() {
        val raw = names("表紙.jpg", "ソ.jpg", "001.jpg", charset = cp932)
        assertEquals(0x5c, raw[0][1].toInt())

        val charset = EntryNames.charsetOf(raw)

        assertEquals(cp932, charset)
        assertEquals(listOf("表紙.jpg", "ソ.jpg", "001.jpg"), raw.map { EntryNames.decode(it, charset) })
    }

    @Test
    fun `one name that is neither UTF-8 nor CP932 sends the whole archive to the undetermined charset`() {
        val bad = "bad".toByteArray() + byteArrayOf(0x81.toByte()) + ".jpg".toByteArray()
        val raw = names("表紙.jpg", charset = cp932) + listOf(bad)

        assertEquals(Charsets.ISO_8859_1, EntryNames.charsetOf(raw, undetermined = Charsets.ISO_8859_1))
        assertEquals(cp932, EntryNames.charsetOf(raw))
        assertEquals("bad�.jpg", EntryNames.decode(bad, cp932))
    }

    @Test
    fun `a mix of UTF-8 and CP932 names is undetermined when the UTF-8 bytes are not CP932`() {
        val raw = names("表紙.jpg", charset = cp932) + names("が.jpg")
        assertEquals(Charsets.US_ASCII, EntryNames.charsetOf(raw, undetermined = Charsets.US_ASCII))
    }

    @Test
    fun `an empty name, which libarchive gives for an invalid flagged name, decodes to a replacement character`() {
        assertEquals(Charsets.UTF_8, EntryNames.charsetOf(listOf(ByteArray(0))))
        assertEquals("�", EntryNames.decode(ByteArray(0), Charsets.UTF_8))
    }
}
