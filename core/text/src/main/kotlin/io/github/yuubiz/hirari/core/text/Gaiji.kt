package io.github.yuubiz.hirari.core.text

import java.text.Normalizer

/** JIS X 0213 plane, row, and cell to Unicode, for 外字 notations (docs/spec.md Q13). */
fun interface JisX0213Table {
    fun lookup(plane: Int, row: Int, cell: Int): String?

    companion object {
        /** No table ships until Q13 settles one whose license allows it. */
        val None = JisX0213Table { _, _, _ -> null }
    }
}

private val unicodeNotation = Regex("""U\+([0-9A-Fa-f]{4,6})""")
private val menKuTen = Regex("""(?:第[34]水準)?([12])-(\d{1,2})-(\d{1,2})""")

/**
 * The text a 外字 note `※［＃note］` stands for, or null when it names no character this reader can produce. The
 * note gives a description, then after `、` a code: `U+XXXX`, or a plane-row-cell number for [table].
 */
fun gaijiText(note: String, table: JisX0213Table): String? {
    unicodeNotation.find(note)?.let { match ->
        val codePoint = match.groupValues[1].toInt(16)
        if (Character.isValidCodePoint(codePoint)) return String(Character.toChars(codePoint))
    }
    menKuTen.find(note)?.let { match ->
        val (plane, row, cell) = match.destructured
        table.lookup(plane.toInt(), row.toInt(), cell.toInt())?.let { return it }
    }
    return namedGaiji(note.substringBefore('、'))
}

private val circled = Regex("""丸(\d+)""")
private val roman = Regex("""(小文字)?ローマ数字(\d+)""")
private val voiced = Regex("""(半)?濁点付き(?:平仮名|片仮名|小書き片仮名)?(.)""")
private val eraLigature = Regex("""「(明治|大正|昭和|平成|令和)」の(?:縦中横)?合字""")

private val eras = mapOf("明治" to "㍾", "大正" to "㍽", "昭和" to "㍼", "平成" to "㍻", "令和" to "㋿")

private fun circledNumber(n: Int): String? = when (n) {
    in 1..20 -> String(Character.toChars(0x2460 + n - 1))
    in 21..35 -> String(Character.toChars(0x3251 + n - 21))
    in 36..50 -> String(Character.toChars(0x32B1 + n - 36))
    else -> null
}

/** The gaiji that 4.9 lets a note name in words: circled and Roman numbers, voiced kana, and era ligatures. */
private fun namedGaiji(description: String): String? {
    val name = normalizeDigits(description)
    circled.matchEntire(name)?.let { return circledNumber(it.groupValues[1].toInt()) }
    roman.matchEntire(name)?.let { match ->
        val n = match.groupValues[2].toInt()
        if (n !in 1..12) return null
        return String(Character.toChars((if (match.groupValues[1].isEmpty()) 0x2160 else 0x2170) + n - 1))
    }
    voiced.matchEntire(name)?.let { match ->
        val mark = if (match.groupValues[1].isEmpty()) '゙' else '゚'
        return Normalizer.normalize(match.groupValues[2] + mark, Normalizer.Form.NFC)
    }
    eraLigature.matchEntire(name)?.let { return eras.getValue(it.groupValues[1]) }
    return null
}

/** Full-width digits as ASCII, so notations may write numbers either way. */
internal fun normalizeDigits(s: String): String = buildString(s.length) {
    for (c in s) append(if (c in '０'..'９') '0' + (c - '０') else c)
}
