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
        val (folded, origin) = folded(paragraph.text)
        var at = folded.indexOf(needle)
        while (at >= 0) {
            if (hits.size == SEARCH_MAX_HITS) return SearchResult(query, hits, truncated = true)
            val last = origin[at + needle.length - 1]
            hits += hit(paragraph, origin[at], last + Character.charCount(paragraph.text.codePointAt(last)))
            at = folded.indexOf(needle, at + needle.length)
        }
    }
    return SearchResult(query, hits, truncated = false)
}

/** [text] folded one code point at a time, and the index in [text] of the code point each folded char came from. */
private fun folded(text: String): Pair<String, IntArray> {
    val out = StringBuilder(text.length)
    var origin = IntArray(text.length + 16)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val width = Character.charCount(cp)
        val piece = if (cp == OBJECT_REPLACEMENT.code) "\u0000" else fold(text.substring(i, i + width))
        if (out.length + piece.length > origin.size) origin = origin.copyOf(origin.size * 2 + piece.length)
        for (c in piece) {
            origin[out.length] = i
            out.append(c)
        }
        i += width
    }
    return out.toString() to origin
}

private fun hit(paragraph: Paragraph, from: Int, to: Int): SearchHit {
    val text = paragraph.text
    val contextStart = maxOf(0, from - SEARCH_CONTEXT_CHARS)
    val contextEnd = minOf(text.length, to + SEARCH_CONTEXT_CHARS)
    val context = text.substring(contextStart, contextEnd).replace(OBJECT_REPLACEMENT, ' ')
    return SearchHit(paragraph.sourceStart(from), paragraph.sourceStart(to), context, from - contextStart, to - contextStart)
}
