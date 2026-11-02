package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.text.MarkedPage
import io.github.yuubiz.hirari.core.text.PageMark
import io.github.yuubiz.hirari.core.text.TextPage
import io.github.yuubiz.hirari.core.text.VerticalLayout

/**
 * The page number shown at the foot of each page and on the page bar, counted from 1. Until the pages before the
 * current one are laid out, [current] is an estimate, and until every page is laid out, so is [total]
 * (docs/spec.md section 4.9, ページ分割).
 */
data class PageNumber(val current: Int, val total: Int, val currentExact: Boolean, val totalExact: Boolean)

/**
 * The pages laid out so far under one set of layout params: a gap-free run of [size] pages, each from its start to
 * the next one's, the last to [end]. Only where the pages start is kept; their content is laid out again for the
 * screen. [fromStart] says the run begins at the first page of the book and [complete] that it ends at the last.
 */
class PageRun(
    private val starts: IntArray,
    private val end: Int,
    val fromStart: Boolean,
    val complete: Boolean,
    private val sourceLength: Int,
) {
    init {
        require(starts.isNotEmpty())
    }

    val size: Int get() = starts.size

    val lastIndex: Int get() = starts.size - 1

    fun start(index: Int): Int = starts[index]

    fun end(index: Int): Int = if (index < lastIndex) starts[index + 1] else end

    /** The page whose range holds [offset], or the nearest end of the run when the run does not reach it. */
    fun indexOf(offset: Int): Int = lastAtOrBefore(starts.size, offset, ::start).coerceAtLeast(0)

    /** Whether the run holds the page of [offset], so a jump there needs no new layout. */
    fun covers(offset: Int): Boolean = (fromStart || offset >= starts[0]) && (complete || offset < end)

    fun number(index: Int): PageNumber {
        val laidChars = (end - starts[0]).coerceAtLeast(1)
        val charsPerPage = laidChars.toDouble() / size
        val total = if (fromStart && complete) size else maxOf(size, Math.round(sourceLength / charsPerPage).toInt())
        val current = if (fromStart) index + 1 else (starts[index] / charsPerPage).toInt() + 1
        return PageNumber(current.coerceIn(1, total), total, currentExact = fromStart, totalExact = fromStart && complete)
    }
}

/** The last of [count] ascending values `valueAt(i)` that is at most [value], or -1 when none is. */
private inline fun lastAtOrBefore(count: Int, value: Int, valueAt: (Int) -> Int): Int {
    var lo = 0
    var hi = count - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (valueAt(mid) <= value) {
            found = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return found
}

/**
 * Lays out a book around [anchor] (docs/spec.md section 4.9, ページ分割). The pages start at the paragraph that holds
 * the anchor, and the pages before it are laid out separately from the start of the book. Each [step] does a
 * bounded amount: first the pages up to [LOOKAHEAD_PAGES] past the reading position, then the pages before the
 * anchor, then the rest. Only each page's mark is kept, and [page] lays a page out again from it. Not thread-safe;
 * one dispatcher runs it.
 */
class Typesetter(private val layout: VerticalLayout, private val sourceLength: Int, anchor: Int) {
    private val anchorBlock = layout.blockIndexAt(anchor)
    private val tail = layout.markedPages(anchorBlock).iterator()
    private val head = if (anchorBlock == 0) null else layout.markedPages(0, anchorBlock).iterator()
    private val tailMarks = ArrayList<PageMark>()
    private val headMarks = ArrayList<PageMark>()
    private var tailEnd = 0

    /** The layout [page] made its last page with, which goes on to the page after it, and that page's start. */
    private var resumed: Iterator<MarkedPage>? = null
    private var resumedAt = -1

    /**
     * Lays out the next part toward the reading position [reading]. Returns the last few pages it laid out up to the
     * lookahead, for the screen to keep, or null when nothing was left.
     */
    fun step(reading: Int, chunkChars: Int = CHUNK_CHARS): List<TextPage>? {
        val needsTail = tail.hasNext() && (tailMarks.isEmpty() || tailEnd <= reading || pagesPast(reading) < LOOKAHEAD_PAGES)
        val near = ArrayDeque<TextPage>()
        fun keep(page: TextPage) {
            near.addLast(page)
            if (near.size > NEAR_PAGES) near.removeFirst()
        }
        when {
            needsTail -> {
                while (tail.hasNext() && (tailMarks.isEmpty() || tailEnd <= reading)) keep(takeTail())
                while (tail.hasNext() && pagesPast(reading) < LOOKAHEAD_PAGES) keep(takeTail())
            }
            head?.hasNext() == true -> take(head, headMarks, chunkChars)
            tail.hasNext() -> {
                var chars = 0
                while (tail.hasNext() && chars < chunkChars) takeTail().let { chars += it.end - it.start }
            }
            else -> return null
        }
        return near.toList()
    }

    /** The pages so far. Call it after a [step]: the first step always lays out a page. */
    fun snapshot(): PageRun {
        val headDone = head?.hasNext() != true
        val before = if (headDone) headMarks else emptyList()
        val starts = IntArray(before.size + tailMarks.size)
        before.forEachIndexed { i, mark -> starts[i] = mark.start }
        tailMarks.forEachIndexed { i, mark -> starts[before.size + i] = mark.start }
        return PageRun(starts, tailEnd, fromStart = headDone, complete = !tail.hasNext(), sourceLength)
    }

    /**
     * The page laid out so far that starts at [start], laid out again from its mark. The page after the one asked
     * for last comes from the same layout, so reading on through a long paragraph costs a page and not the paragraph.
     */
    fun page(start: Int): TextPage {
        val next = resumed?.takeIf { start == resumedAt && it.hasNext() } ?: layout.resume(markAt(start)).iterator()
        val page = next.next().page
        check(page.start == start) { "page at $start came out at ${page.start}" }
        resumed = next
        resumedAt = page.end
        return page
    }

    private fun markAt(start: Int): PageMark {
        for (marks in listOf(headMarks, tailMarks)) {
            val at = lastAtOrBefore(marks.size, start) { marks[it].start }
            if (at >= 0 && marks[at].start == start) return marks[at]
        }
        throw IllegalArgumentException("no page starts at $start")
    }

    private fun takeTail(): TextPage {
        val next = tail.next()
        tailMarks += next.mark
        tailEnd = next.page.end
        return next.page
    }

    private fun pagesPast(reading: Int): Int {
        val at = lastAtOrBefore(tailMarks.size, reading) { tailMarks[it].start }
        return if (at < 0) tailMarks.size else tailMarks.size - 1 - at
    }

    private fun take(pages: Iterator<MarkedPage>, into: MutableList<PageMark>, chunkChars: Int) {
        var chars = 0
        while (pages.hasNext() && chars < chunkChars) {
            val next = pages.next()
            into += next.mark
            chars += next.page.end - next.page.start
        }
    }

    companion object {
        /** About this many characters per step, so a step stays short and a cancel lands soon. */
        const val CHUNK_CHARS = 10_000

        /** Pages laid out past the reading position before anything else: a spread and the spread after it. */
        const val LOOKAHEAD_PAGES = 4

        /** The most pages a step hands back, which holds the pages the screen keeps around a spread. */
        const val NEAR_PAGES = 8
    }
}
