package io.github.yuubiz.hirari.core.archive

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Decodes the entry names of one archive by the rules of docs/spec.md section 3.4. */
object EntryNames {
    val CP932: Charset = Charset.forName("windows-31j")

    /**
     * The one charset for all of [rawNames]: UTF-8 when every name is valid UTF-8, else CP932 when every name is
     * valid CP932, else [undetermined]. [undetermined] is the setting `fallbackCharset` until the detector of
     * section 3.4 step 4 takes its place.
     */
    fun charsetOf(rawNames: Collection<ByteArray>, undetermined: Charset = CP932): Charset = when {
        rawNames.all { decodes(it, Charsets.UTF_8) } -> Charsets.UTF_8
        rawNames.all { decodes(it, CP932) } -> CP932
        else -> undetermined
    }

    /** [rawName] in [charset], with U+FFFD for each byte sequence it cannot decode. An empty name is U+FFFD. */
    fun decode(rawName: ByteArray, charset: Charset): String {
        if (rawName.isEmpty()) return "�"
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(rawName))
            .toString()
    }

    private fun decodes(bytes: ByteArray, charset: Charset): Boolean = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (_: CharacterCodingException) {
        false
    }
}
