package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.text.Paragraph
import io.github.yuubiz.hirari.core.text.TextDocument
import java.text.Normalizer
import java.util.Locale

/**
 * One match of a search. [start] and [end] are source offsets, as positions are. [context] is the text around the
 * match, which takes `matchStart until matchEnd` of it.
 */
data class SearchHit(val start: Int, val end: Int, val context: String, val matchStart: Int, val matchEnd: Int)

data class SearchResult(val query: String, val hits: List<SearchHit>, val truncated: Boolean)

/** The characters of context on each side of a match. */
const val SEARCH_CONTEXT_CHARS = 16

/** A search stops after this many matches, so a one-letter query stays quick and the list stays usable. */
const val SEARCH_MAX_HITS = 1000

private const val OBJECT_REPLACEMENT = '￼'

private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

/**
 * The search of docs/spec.md section 4.9: NFKC and case folded on both sides, over the drawn text of each line. Ruby,
 * emphasis marks and pictures are not in the drawn text, so they never match. Matches do not cross lines.
 */
fun searchText(document: TextDocument, query: String): SearchResult {
    val needle = fold(query)
    if (needle.isBlank()) return SearchResult(query, emptyList(), truncated = false)
    val hits = ArrayList<SearchHit>()
    for (paragraph in document.blocks) {
        if (paragraph !is Paragraph || paragraph.text.isEmpty()) continue
        val folded = Folded(paragraph.text)
        var at = folded.text.indexOf(needle)
        while (at >= 0) {
            if (hits.size == SEARCH_MAX_HITS) return SearchResult(query, hits, truncated = true)
            hits += hit(paragraph, folded.starts[at], folded.ends[at + needle.length - 1])
            at = folded.text.indexOf(needle, at + needle.length)
        }
    }
    return SearchResult(query, hits, truncated = false)
}

/** Whether [cp] may combine with the code point before it under NFKC, as a combining mark or a half-width voiced mark does. */
private fun joinsPrevious(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> cp == 0xFF9E || cp == 0xFF9F
}

/**
 * [source] folded a cluster at a time: a code point with the marks that may combine with it, so `ｶﾞ` folds to `ガ`
 * as the query does. Each folded char maps back to the [starts] and [ends] of its cluster in [source].
 */
private class Folded(source: String) {
    val text: String
    var starts = IntArray(source.length + 16)
        private set
    var ends = IntArray(source.length + 16)
        private set

    init {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            var end = i + Character.charCount(source.codePointAt(i))
            while (end < source.length && joinsPrevious(source.codePointAt(end))) end += Character.charCount(source.codePointAt(end))
            val piece = if (source[i] == OBJECT_REPLACEMENT) "\u0000" else fold(source.substring(i, end))
            if (out.length + piece.length > starts.size) {
                starts = starts.copyOf(starts.size * 2 + piece.length)
                ends = ends.copyOf(starts.size)
            }
            for (c in piece) {
                starts[out.length] = i
                ends[out.length] = end
                out.append(c)
            }
            i = end
        }
        text = out.toString()
    }
}

private fun hit(paragraph: Paragraph, from: Int, to: Int): SearchHit {
    val text = paragraph.text
    val contextStart = maxOf(0, from - SEARCH_CONTEXT_CHARS)
    val contextEnd = minOf(text.length, to + SEARCH_CONTEXT_CHARS)
    val context = text.substring(contextStart, contextEnd).replace(OBJECT_REPLACEMENT, ' ')
    return SearchHit(paragraph.sourceStart(from), paragraph.sourceStart(to), context, from - contextStart, to - contextStart)
}
