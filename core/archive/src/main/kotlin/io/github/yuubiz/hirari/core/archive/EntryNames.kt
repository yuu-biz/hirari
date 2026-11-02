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

    /** One guess of a charset detector: the charset's name as the detector gives it, and a confidence of 0 to 100. */
    class Guess(val name: String, val confidence: Int)

    /** Guesses the charset of a run of bytes, likeliest first, as ICU's `CharsetDetector` does. */
    fun interface Detector {
        fun guess(bytes: ByteArray): List<Guess>
    }

    /**
     * The detector the app has: none. `android.icu` leaves `CharsetDetector` out of the public API, and ICU4J
     * would add its whole library to the APK, so step 3 of docs/spec.md section 3.4 waits for that decision.
     */
    val platformDetector: Detector = Detector { emptyList() }

    /**
     * The one charset for all of [rawNames]: UTF-8 when every name is valid UTF-8, else CP932 when every name is
     * valid CP932, else the likeliest of [detector]'s guesses that decodes every name, with Japanese preferred,
     * else [undetermined], the setting `fallbackCharset`. [slashMayBeBackslash] is for RAR 4 names
     * ([restoreBackslashes]).
     */
    fun charsetOf(
        rawNames: Collection<ByteArray>,
        detector: Detector = platformDetector,
        undetermined: Charset = CP932,
        slashMayBeBackslash: Boolean = false,
    ): Charset {
        fun valid(charset: Charset) = rawNames.all { decodes(it, charset, slashMayBeBackslash) }
        return when {
            valid(Charsets.UTF_8) -> Charsets.UTF_8
            valid(CP932) -> CP932
            else -> detected(rawNames, detector, ::valid) ?: undetermined
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

    private fun detected(rawNames: Collection<ByteArray>, detector: Detector, valid: (Charset) -> Boolean): Charset? {
        val joined = rawNames.fold(ByteArray(0)) { all, name -> all + name + '\n'.code.toByte() }
        val candidates = detector.guess(joined).mapNotNull { guess ->
            DETECTABLE[guess.name.uppercase()]?.takeIf(valid)?.let { it to guess.confidence }
        }
        val best = candidates.maxByOrNull { it.second } ?: return null
        if (best.second < MIN_CONFIDENCE) return null
        val japanese = candidates.firstOrNull { it.first in JAPANESE && it.second >= best.second - JAPANESE_MARGIN }
        return (japanese ?: best).first
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

    /** ICU's names for the charsets docs/spec.md section 3.4 detects. ICU reports CP932 as Shift_JIS. */
    private val DETECTABLE: Map<String, Charset> = mapOf(
        "SHIFT_JIS" to CP932,
        "EUC-JP" to EUC_JP,
        "GB18030" to GB18030,
        "BIG5" to BIG5,
    )

    private val JAPANESE = setOf(CP932, EUC_JP)

    /** Below this confidence a guess counts as none (docs/spec.md section 3.4). */
    private const val MIN_CONFIDENCE = 10

    /** A Japanese guess wins over a likelier one that is at most this much likelier. */
    private const val JAPANESE_MARGIN = 10
}
