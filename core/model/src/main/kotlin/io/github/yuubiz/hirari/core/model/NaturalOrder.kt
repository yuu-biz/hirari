package io.github.yuubiz.hirari.core.model

import java.math.BigInteger
import java.text.Normalizer
import java.util.Locale

/** Natural order of names (docs/spec.md section 3.5). Ties fall back to the UTF-8 bytes of the names. */
val NaturalOrder: Comparator<String> = Comparator { a, b ->
    compareNatural(a, b).takeIf { it != 0 } ?: compareUnsigned(a.encodeToByteArray(), b.encodeToByteArray())
}

/** Natural order of page names. Ties fall back to the raw name bytes, then to the stored order. */
val NaturalPageOrder: Comparator<PageEntry> = Comparator { a, b ->
    compareNatural(a.name, b.name).takeIf { it != 0 }
        ?: compareUnsigned(a.rawName, b.rawName).takeIf { it != 0 }
        ?: a.entryIndex.compareTo(b.entryIndex)
}

private fun compareNatural(a: String, b: String): Int {
    val left = components(a)
    val right = components(b)
    for (i in 0 until minOf(left.size, right.size)) {
        val c = compareTokens(left[i], right[i])
        if (c != 0) return c
    }
    return left.size.compareTo(right.size)
}

private fun components(name: String): List<List<Token>> =
    Normalizer.normalize(name, Normalizer.Form.NFKC)
        .uppercase(Locale.ROOT)
        .lowercase(Locale.ROOT)
        .split('/', '\\')
        .map(::tokenize)

private sealed interface Token {
    data class Number(val value: BigInteger, val digits: Int) : Token

    /** [key] orders characters. Volume markers share one slot and sort by rank inside it. */
    data class Char(val key: Int) : Token
}

private const val VOLUME_MARKERS = "上前中後下短外"
private const val KANJI_DIGITS = "〇一二三四五六七八九"
private const val KANJI_UNITS = "十百千"

private const val NUMBER_KEY = '0'.code * 8
private val MARKER_BASE_KEY = VOLUME_MARKERS.minOf { it.code } * 8

private fun charKey(codePoint: Int): Int {
    val rank = if (codePoint <= 0xFFFF) VOLUME_MARKERS.indexOf(codePoint.toChar()) else -1
    return if (rank >= 0) MARKER_BASE_KEY + rank else codePoint * 8
}

private fun Token.key(): Int = when (this) {
    is Token.Number -> NUMBER_KEY
    is Token.Char -> key
}

private fun compareToken(a: Token, b: Token): Int {
    if (a is Token.Number && b is Token.Number) {
        val c = a.value.compareTo(b.value)
        return if (c != 0) c else a.digits.compareTo(b.digits)
    }
    return a.key().compareTo(b.key())
}

private fun compareTokens(a: List<Token>, b: List<Token>): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = compareToken(a[i], b[i])
        if (c != 0) return c
    }
    return a.size.compareTo(b.size)
}

private fun isAsciiDigit(c: Char) = c in '0'..'9'
private fun isKanjiNumeral(c: Char) = c in KANJI_DIGITS || c in KANJI_UNITS

private fun tokenize(s: String): List<Token> {
    val tokens = mutableListOf<Token>()
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val run = when {
            isAsciiDigit(c) -> s.runFrom(i, ::isAsciiDigit)
            isKanjiNumeral(c) -> s.runFrom(i, ::isKanjiNumeral)
            else -> null
        }
        if (run != null) {
            val value = if (isAsciiDigit(c)) BigInteger(run) else kanjiValue(run)
            tokens += Token.Number(value, run.length)
            i += run.length
        } else {
            val codePoint = s.codePointAt(i)
            tokens += Token.Char(charKey(codePoint))
            i += Character.charCount(codePoint)
        }
    }
    return tokens
}

private fun String.runFrom(start: Int, belongs: (Char) -> Boolean): String {
    var end = start
    while (end < length && belongs(this[end])) end++
    return substring(start, end)
}

/** Reads both 十二 (12) and 二〇二四 (2024). */
private fun kanjiValue(run: String): BigInteger {
    var total = BigInteger.ZERO
    var current = BigInteger.ZERO
    for (c in run) {
        val digit = KANJI_DIGITS.indexOf(c)
        if (digit >= 0) {
            current = current * BigInteger.TEN + digit.toBigInteger()
        } else {
            val unit = BigInteger.TEN.pow(KANJI_UNITS.indexOf(c) + 1)
            total += (if (current == BigInteger.ZERO) BigInteger.ONE else current) * unit
            current = BigInteger.ZERO
        }
    }
    return total + current
}

private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = (a[i].toInt() and 0xFF).compareTo(b[i].toInt() and 0xFF)
        if (c != 0) return c
    }
    return a.size.compareTo(b.size)
}
