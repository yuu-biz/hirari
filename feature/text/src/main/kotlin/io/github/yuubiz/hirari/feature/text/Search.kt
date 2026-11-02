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

/** The folded characters a search holds past the query at a time, whatever the length of the line. */
internal const val SEARCH_WINDOW_CHARS = 1 shl 16

/** The code points of one cluster a search folds as a whole, so a run of marks cannot grow the working text without bound. */
private const val MAX_CLUSTER_CODE_POINTS = 32

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
        if (!LineSearch(paragraph, needle, hits).run()) return SearchResult(query, hits, truncated = true)
    }
    return SearchResult(query, hits, truncated = false)
}

/** Whether [cp] may combine with the code point before it under NFKC, as a combining mark or a half-width voiced mark does. */
private fun joinsPrevious(cp: Int): Boolean = when (Character.getType(cp)) {
    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt() -> true
    else -> cp == 0xFF9E || cp == 0xFF9F
}

/**
 * The search of one line for [needle]. The line is folded a cluster at a time: a code point with the marks that may
 * combine with it, so `ｶﾞ` folds to `ガ` as the query does. Only a window of the folded text is held, with the source
 * range of each folded char, and it is searched whenever it fills. What stays is the last chars that a match of
 * [needle] could still start in, so a match across two windows is found. The memory held depends on [needle] and the
 * window, not on how long the line is or how far NFKC expands it.
 */
private class LineSearch(private val paragraph: Paragraph, private val needle: String, private val hits: MutableList<SearchHit>) {
    private val source = paragraph.text
    private val text = StringBuilder()
    private var starts = IntArray(minOf(source.length, SEARCH_WINDOW_CHARS) + 16)
    private var ends = IntArray(starts.size)

    /** The index in the whole folded line of `text[0]`. */
    private var base = 0

    /** The index in the whole folded line where the next match may start, which is past the last one. */
    private var from = 0
    private var scanAt = SEARCH_WINDOW_CHARS

    /** Whether the line was searched to its end, which is false when [SEARCH_MAX_HITS] matches were already found. */
    fun run(): Boolean {
        var i = 0
        while (i < source.length) {
            var end = i + Character.charCount(source.codePointAt(i))
            var marks = 1
            while (end < source.length && marks < MAX_CLUSTER_CODE_POINTS && joinsPrevious(source.codePointAt(end))) {
                end += Character.charCount(source.codePointAt(end))
                marks++
            }
            append(if (source[i] == OBJECT_REPLACEMENT) "\u0000" else fold(source.substring(i, end)), i, end)
            i = end
            if (text.length >= scanAt && !scan()) return false
        }
        return scan()
    }

    private fun append(piece: String, start: Int, end: Int) {
        if (text.length + piece.length > starts.size) {
            starts = starts.copyOf(starts.size * 2 + piece.length)
            ends = ends.copyOf(starts.size)
        }
        for (c in piece) {
            starts[text.length] = start
            ends[text.length] = end
            text.append(c)
        }
    }

    /** Records the matches in the window, then drops the chars no later match can use. False when the matches ran out of room. */
    private fun scan(): Boolean {
        var at = text.indexOf(needle, maxOf(0, from - base))
        while (at >= 0) {
            if (hits.size == SEARCH_MAX_HITS) return false
            hits += hit(paragraph, starts[at], ends[at + needle.length - 1])
            from = base + at + needle.length
            at = text.indexOf(needle, from - base)
        }
        val keep = minOf(text.length, maxOf(from - base, text.length - (needle.length - 1), 0))
        System.arraycopy(starts, keep, starts, 0, text.length - keep)
        System.arraycopy(ends, keep, ends, 0, text.length - keep)
        text.delete(0, keep)
        base += keep
        scanAt = text.length + SEARCH_WINDOW_CHARS
        return true
    }
}

private fun hit(paragraph: Paragraph, from: Int, to: Int): SearchHit {
    val text = paragraph.text
    val contextStart = maxOf(0, from - SEARCH_CONTEXT_CHARS)
    val contextEnd = minOf(text.length, to + SEARCH_CONTEXT_CHARS)
    val context = text.substring(contextStart, contextEnd).replace(OBJECT_REPLACEMENT, ' ')
    val start = paragraph.sourceStart(from)
    // The halves of a くの字点 share one source offset, so the range reaches the first offset past the last match's.
    val last = paragraph.sourceStart(to - 1)
    var next = to
    while (next < text.length && paragraph.sourceStart(next) <= last) next++
    return SearchHit(start, paragraph.sourceStart(next), context, from - contextStart, to - contextStart)
}
