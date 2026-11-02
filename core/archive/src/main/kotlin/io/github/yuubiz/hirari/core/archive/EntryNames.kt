package io.github.yuubiz.hirari.core.archive

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodes the entry names of one archive by the rules of docs/spec.md section 3.4. [ArchiveSession.nameCharset]
 * and [ArchiveSession.name] apply them with what the session knows about its format.
 */
object EntryNames {
    val CP932: Charset = Charset.forName("windows-31j")
    val EUC_JP: Charset = Charset.forName("EUC-JP")
    val GB18030: Charset = Charset.forName("GB18030")
    val BIG5: Charset = Charset.forName("Big5")

    /** The charsets a reader can choose for a book by hand, in the order the menu lists them. */
    val choices: List<Charset> = listOf(Charsets.UTF_8, CP932, EUC_JP, GB18030, BIG5)

    /**
     * The one charset for all of [rawNames]: UTF-8 when every name is valid UTF-8, else CP932 when every name is
     * valid CP932, else the candidate that decodes every name with the best [CommonCharacters.score], with
     * Japanese preferred, else [undetermined], the setting `fallbackCharset`. [slashMayBeBackslash] is for RAR 4
     * names ([restoreBackslashes]).
     */
    fun charsetOf(
        rawNames: Collection<ByteArray>,
        undetermined: Charset = CP932,
        slashMayBeBackslash: Boolean = false,
    ): Charset {
        fun valid(charset: Charset) = rawNames.all { decodes(it, charset, slashMayBeBackslash) }
        return when {
            valid(Charsets.UTF_8) -> Charsets.UTF_8
            valid(CP932) -> CP932
            else -> detected(rawNames, slashMayBeBackslash, ::valid) ?: undetermined
        }
    }

    /** [rawName] in [charset], with U+FFFD for each byte sequence it cannot decode. An empty name is U+FFFD. */
    fun decode(rawName: ByteArray, charset: Charset, slashMayBeBackslash: Boolean = false): String {
        if (rawName.isEmpty()) return "�"
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(if (slashMayBeBackslash) restoreBackslashes(rawName, charset) else rawName))
            .toString()
    }

    private fun detected(rawNames: Collection<ByteArray>, slashMayBeBackslash: Boolean, valid: (Charset) -> Boolean): Charset? {
        val scores = CommonCharacters.candidates.filter(valid).map { charset ->
            charset to CommonCharacters.score(charset, rawNames.map { decode(it, charset, slashMayBeBackslash) })
        }
        val best = scores.maxByOrNull { it.second } ?: return null
        val japanese = scores.filter { it.first in JAPANESE && it.second >= best.second - JAPANESE_MARGIN }.maxByOrNull { it.second }
        val (charset, score) = japanese ?: best
        return charset.takeIf { score >= MIN_CONFIDENCE }
    }

    /**
     * libarchive turns every `\` byte of a RAR 4 name into `/`, the trail byte of a double-byte character too, as
     * in 表 of CP932 (docs/spec.md section 3.4). A trail byte is never `/` in these charsets, so a `/` right after
     * a lead byte goes back to `\`.
     */
    internal fun restoreBackslashes(raw: ByteArray, charset: Charset): ByteArray {
        val isLead = LEAD_BYTES[charset] ?: return raw
        var restored: ByteArray? = null
        var i = 0
        while (i < raw.size) {
            if (isLead(raw[i].toInt() and 0xff) && i + 1 < raw.size) {
                if (raw[i + 1] == SLASH) (restored ?: raw.copyOf().also { restored = it })[i + 1] = BACKSLASH
                i += 2
            } else {
                i++
            }
        }
        return restored ?: raw
    }

    private fun decodes(bytes: ByteArray, charset: Charset, slashMayBeBackslash: Boolean): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(if (slashMayBeBackslash) restoreBackslashes(bytes, charset) else bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    private const val SLASH = '/'.code.toByte()
    private const val BACKSLASH = '\\'.code.toByte()

    private val LEAD_BYTES: Map<Charset, (Int) -> Boolean> = mapOf(
        CP932 to { b -> b in 0x81..0x9f || b in 0xe0..0xfc },
        GB18030 to { b -> b in 0x81..0xfe },
        BIG5 to { b -> b in 0x81..0xfe },
    )

    private val JAPANESE = setOf(CP932, EUC_JP)

    /** Below this score a candidate counts as none (docs/spec.md section 3.4). */
    private const val MIN_CONFIDENCE = 10

    /** A Japanese candidate wins over a better scoring one that scores at most this much more. */
    private const val JAPANESE_MARGIN = 10
}
