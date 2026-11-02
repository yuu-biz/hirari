package io.github.yuubiz.hirari.core.text

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

val CP932: Charset = Charset.forName("windows-31j")
val EUC_JP: Charset = Charset.forName("EUC-JP")

/** Why [DetectedCharset.charset] was chosen, in the order of docs/spec.md section 3.11. */
enum class CharsetEvidence { Bom, Valid, FewestErrors, Fallback }

data class DetectedCharset(val charset: Charset, val evidence: CharsetEvidence)

const val CHARSET_SAMPLE_BYTES = 64 * 1024

/** The candidates without a byte order mark, in the order a tie goes. */
private val candidates = listOf(Charsets.UTF_8, CP932, EUC_JP)

private enum class Bom(val bytes: ByteArray, val charset: Charset) {
    Utf8(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()), Charsets.UTF_8),
    Utf16Be(byteArrayOf(0xFE.toByte(), 0xFF.toByte()), Charsets.UTF_16BE),
    Utf16Le(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), Charsets.UTF_16LE),
}

private fun bomOf(bytes: ByteArray): Bom? = Bom.entries.firstOrNull { bom ->
    bytes.size >= bom.bytes.size && bom.bytes.indices.all { bytes[it] == bom.bytes[it] }
}

/**
 * How many malformed or unmappable sequences [sample] has in [charset]. When the sample is a prefix of a longer
 * file, a sequence cut at its end does not count, because the cut is ours and not the file's.
 */
private fun errorCount(sample: ByteArray, charset: Charset, isWholeFile: Boolean): Int {
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = ByteBuffer.wrap(sample)
    val output = CharBuffer.allocate(4096)
    var errors = 0
    while (true) {
        val result = decoder.decode(input, output, isWholeFile)
        when {
            result.isError -> {
                errors++
                input.position(input.position() + result.length())
            }
            result.isOverflow -> output.clear()
            else -> break
        }
    }
    return errors
}

/** docs/spec.md section 3.11. [fallback] is the `fallbackCharset` setting. */
fun detectCharset(bytes: ByteArray, fallback: Charset): DetectedCharset {
    bomOf(bytes)?.let { return DetectedCharset(it.charset, CharsetEvidence.Bom) }
    val isWholeFile = bytes.size <= CHARSET_SAMPLE_BYTES
    val sample = if (isWholeFile) bytes else bytes.copyOf(CHARSET_SAMPLE_BYTES)
    val errors = candidates.associateWith { errorCount(sample, it, isWholeFile) }
    errors.entries.firstOrNull { it.value == 0 }?.let { return DetectedCharset(it.key, CharsetEvidence.Valid) }
    val best = errors.entries.minBy { it.value }
    return if (best.value <= maxOf(1, sample.size / 1000)) {
        DetectedCharset(best.key, CharsetEvidence.FewestErrors)
    } else {
        DetectedCharset(fallback, CharsetEvidence.Fallback)
    }
}

/**
 * The text of [bytes] in [charset], without a byte order mark, with every line break as LF and every sequence that
 * does not decode as U+FFFD. Character offsets of docs/spec.md section 2.7 index this string.
 */
fun decodeText(bytes: ByteArray, charset: Charset): String {
    val bom = bomOf(bytes)?.takeIf { it.charset == charset }
    val start = bom?.bytes?.size ?: 0
    val decoded = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .replaceWith("�")
        .decode(ByteBuffer.wrap(bytes, start, bytes.size - start))
    return normalizeLineBreaks(decoded)
}

private fun normalizeLineBreaks(text: CharSequence): String {
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\r') {
            out.append('\n')
            if (i + 1 < text.length && text[i + 1] == '\n') i++
        } else {
            out.append(c)
        }
        i++
    }
    return out.toString()
}
