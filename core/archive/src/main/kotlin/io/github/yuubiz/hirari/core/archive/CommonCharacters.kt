package io.github.yuubiz.hirari.core.archive

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.BitSet

/**
 * The characters that names written in each candidate charset of docs/spec.md section 3.4 commonly use, and the
 * score of a decoding: the percentage of its non-ASCII characters in that set.
 *
 * Each set comes from the code layout of the standard its charset encodes, decoded through the platform's
 * converter for that charset:
 * - CP932 and EUC-JP (JIS X 0208): rows 1 to 5 (symbols, digits and Latin letters, hiragana, katakana), rows 16
 *   to 47 (the level 1 kanji), and the half-width katakana.
 * - GB18030 (GB 2312): rows 1 to 3 (symbols and full-width Latin letters) and rows 16 to 55 (the level 1 hanzi).
 *   Rows 4 and 5 of GB 2312 hold kana, which Chinese names do not use, so they stay out.
 * - Big5: the symbols at 0xA140 to 0xA3BF and the frequent characters at 0xA440 to 0xC67E.
 *
 * Bytes in one of these charsets decode in another to characters that are mostly outside its set, so the right
 * charset scores highest. EUC-JP and GB 2312 share the kana rows, which is why the kana count only for Japanese.
 */
internal object CommonCharacters {
    private val sets: Map<Charset, BitSet> by lazy {
        val japanese = BitSet().apply {
            addCodes(EntryNames.EUC_JP, 0xa1a1..0xa5fe, ::eucTrail)
            addCodes(EntryNames.EUC_JP, 0xb0a1..0xcffe, ::eucTrail)
            set(0xff61, 0xffa0)
        }
        val simplified = BitSet().apply {
            addCodes(EntryNames.GB18030, 0xa1a1..0xa3fe, ::eucTrail)
            addCodes(EntryNames.GB18030, 0xb0a1..0xd7fe, ::eucTrail)
        }
        val traditional = BitSet().apply {
            addCodes(EntryNames.BIG5, 0xa140..0xa3bf, ::big5Trail)
            addCodes(EntryNames.BIG5, 0xa440..0xc67e, ::big5Trail)
        }
        mapOf(
            EntryNames.CP932 to japanese,
            EntryNames.EUC_JP to japanese,
            EntryNames.GB18030 to simplified,
            EntryNames.BIG5 to traditional,
        )
    }

    /** The charsets that have a set, which are the candidates of the detection. */
    val candidates: List<Charset> = listOf(EntryNames.CP932, EntryNames.EUC_JP, EntryNames.GB18030, EntryNames.BIG5)

    /** 0 to 100: the percentage of the non-ASCII characters of [names], decoded in [charset], that are in its set. */
    fun score(charset: Charset, names: List<String>): Int {
        val common = sets.getValue(charset)
        var counted = 0
        var hits = 0
        for (name in names) {
            for (char in name) {
                if (char.code < 0x80) continue
                counted++
                if (common[char.code]) hits++
            }
        }
        return if (counted == 0) 0 else hits * 100 / counted
    }

    private fun eucTrail(byte: Int) = byte in 0xa1..0xfe

    private fun big5Trail(byte: Int) = byte in 0x40..0x7e || byte in 0xa1..0xfe

    /** Sets the character of each double-byte code in [codes] whose trail byte passes [trail] and that [charset] maps. */
    private fun BitSet.addCodes(charset: Charset, codes: IntRange, trail: (Int) -> Boolean) {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val bytes = ByteArray(2)
        for (code in codes) {
            if (!trail(code and 0xff)) continue
            bytes[0] = (code ushr 8).toByte()
            bytes[1] = code.toByte()
            val decoded = try {
                decoder.reset().decode(ByteBuffer.wrap(bytes))
            } catch (_: CharacterCodingException) {
                continue
            }
            if (decoded.length == 1) set(decoded[0].code)
        }
    }
}
