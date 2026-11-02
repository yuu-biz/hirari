package io.github.yuubiz.hirari.core.text

import java.nio.charset.Charset
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class TextCharsetTest {
    private val sample = "吾輩《わがはい》は猫である。名前はまだ無い。\r\nどこで生れたかとんと見当がつかぬ。［＃改ページ］ｶﾀｶﾅ①\r\n"
    private val gbk: Charset = Charset.forName("GBK")

    private fun detect(bytes: ByteArray) = detectCharset(bytes, fallback = gbk)

    @Test
    fun `a byte order mark decides and is dropped`() {
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + sample.toByteArray(Charsets.UTF_8)
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + sample.toByteArray(Charsets.UTF_16LE)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + sample.toByteArray(Charsets.UTF_16BE)
        for ((bytes, charset) in listOf(utf8 to Charsets.UTF_8, le to Charsets.UTF_16LE, be to Charsets.UTF_16BE)) {
            assertEquals(DetectedCharset(charset, CharsetEvidence.Bom), detect(bytes))
            assertEquals(sample.replace("\r\n", "\n"), decodeText(bytes, charset))
        }
    }

    @Test
    fun `clean UTF-8, CP932, and EUC-JP are told apart`() {
        for (charset in listOf(Charsets.UTF_8, CP932, EUC_JP)) {
            assertEquals(DetectedCharset(charset, CharsetEvidence.Valid), detect(sample.toByteArray(charset)), charset.name())
        }
    }

    // Every EUC-JP kana byte is a half-width katakana in CP932, so kana-only EUC-JP also reads cleanly as CP932.
    @Test
    fun `kana-only EUC-JP is not taken for half-width CP932`() {
        val bytes = "あいうえお。かきくけこ。\n".repeat(50).toByteArray(EUC_JP)
        assertEquals(DetectedCharset(EUC_JP, CharsetEvidence.Valid), detect(bytes))
        assertEquals(CP932, detect("ｱｲｳｴｵ、カタカナ。\n".repeat(50).toByteArray(CP932)).charset)
    }

    @Test
    fun `ASCII reads as UTF-8`() {
        assertEquals(Charsets.UTF_8, detect("plain text\n".toByteArray()).charset)
    }

    @Test
    fun `a character cut by the end of the sample does not count as an error`() {
        val prefix = "a".repeat(CHARSET_SAMPLE_BYTES - 1)
        val bytes = (prefix + "猫" + "本文".repeat(100)).toByteArray(Charsets.UTF_8)
        assertEquals(DetectedCharset(Charsets.UTF_8, CharsetEvidence.Valid), detect(bytes))
    }

    @Test
    fun `a character cut by the end of a whole file counts`() {
        val bytes = "本文".repeat(20).toByteArray(Charsets.UTF_8).let { it.copyOf(it.size - 1) }
        assertEquals(CharsetEvidence.FewestErrors, detect(bytes).evidence)
    }

    @Test
    fun `a few bad bytes keep the charset with the fewest errors and decode as U+FFFD`() {
        val good = sample.repeat(20).toByteArray(CP932)
        val bytes = good.copyOf(200) + byteArrayOf(0x85.toByte(), 0x40) + good.copyOfRange(200, good.size)
        assertEquals(DetectedCharset(CP932, CharsetEvidence.FewestErrors), detect(bytes))
        assertEquals(1, decodeText(bytes, CP932).count { it == '�' })
    }

    @Test
    fun `noise falls back to the setting`() {
        val bytes = Random(7).nextBytes(4096)
        assertEquals(DetectedCharset(gbk, CharsetEvidence.Fallback), detect(bytes))
    }

    @Test
    fun `every line break becomes LF`() {
        assertEquals("a\nb\nc\n\nd", decodeText("a\r\nb\rc\n\r\nd".toByteArray(), Charsets.UTF_8))
    }
}
