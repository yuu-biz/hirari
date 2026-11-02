package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.text.TextPage
import io.github.yuubiz.hirari.core.text.VerticalLayout

/**
 * The page number shown at the foot of each page and on the page bar, counted from 1. Until the pages before the
 * current one are laid out, [current] is an estimate, and until every page is laid out, so is [total]
 * (docs/spec.md section 4.9, ページ分割).
 */
data class PageNumber(val current: Int, val total: Int, val currentExact: Boolean, val totalExact: Boolean)

/**
 * The pages laid out so far under one set of layout params: a gap-free run of [pages]. [fromStart] says the run
 * begins at the first page of the book and [complete] that it ends at the last.
 */
class PageRun(
    val pages: List<TextPage>,
    val fromStart: Boolean,
    val complete: Boolean,
    private val sourceLength: Int,
) {
    init {
        require(pages.isNotEmpty())
    }

    /** The page whose range holds [offset], or the nearest end of the run when the run does not reach it. */
    fun indexOf(offset: Int): Int {
        var lo = 0
        var hi = pages.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (pages[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Whether the run holds the page of [offset], so a jump there needs no new layout. */
    fun covers(offset: Int): Boolean =
        (fromStart || offset >= pages.first().start) && (complete || offset < pages.last().end)

    fun number(index: Int): PageNumber {
        val laidChars = (pages.last().end - pages.first().start).coerceAtLeast(1)
        val charsPerPage = laidChars.toDouble() / pages.size
        val total = if (fromStart && complete) pages.size else maxOf(pages.size, Math.round(sourceLength / charsPerPage).toInt())
        val current = if (fromStart) index + 1 else (pages[index].start / charsPerPage).toInt() + 1
        return PageNumber(current.coerceIn(1, total), total, currentExact = fromStart, totalExact = fromStart && complete)
    }
}

/**
 * Lays out a book around [anchor] (docs/spec.md section 4.9, ページ分割). The pages start at the paragraph that holds
 * the anchor, and the pages before it are laid out separately from the start of the book. Each [step] does a
 * bounded amount: first the pages up to [LOOKAHEAD_PAGES] past the reading position, then the pages before the
 * anchor, then the rest. Not thread-safe; one dispatcher runs it.
 */
class Typesetter(private val layout: VerticalLayout, private val sourceLength: Int, anchor: Int) {
    private val anchorBlock = layout.blockIndexAt(anchor)
    private val tail = layout.pages(anchorBlock).iterator()
    private val head = if (anchorBlock == 0) null else layout.pages(0, anchorBlock).iterator()
    private val tailPages = ArrayList<TextPage>()
    private val headPages = ArrayList<TextPage>()

    /** Lays out the next part toward the reading position [reading]. Returns false when nothing was left. */
    fun step(reading: Int, chunkChars: Int = CHUNK_CHARS): Boolean {
        val needsTail = tail.hasNext() && (tailPages.isEmpty() || tailPages.last().end <= reading || pagesPast(reading) < LOOKAHEAD_PAGES)
        when {
            needsTail -> {
                while (tail.hasNext() && (tailPages.isEmpty() || tailPages.last().end <= reading)) tailPages += tail.next()
                while (tail.hasNext() && pagesPast(reading) < LOOKAHEAD_PAGES) tailPages += tail.next()
            }
            head?.hasNext() == true -> take(head, headPages, chunkChars)
            tail.hasNext() -> take(tail, tailPages, chunkChars)
            else -> return false
        }
        return true
    }

    /** The pages so far. Call it after a [step]: the first step always lays out a page. */
    fun snapshot(): PageRun {
        val headDone = head?.hasNext() != true
        val pages = if (headDone) headPages + tailPages else ArrayList(tailPages)
        return PageRun(pages, fromStart = headDone, complete = !tail.hasNext(), sourceLength)
    }

    private fun pagesPast(reading: Int): Int {
        val at = tailPages.indexOfLast { it.start <= reading }
        return if (at < 0) tailPages.size else tailPages.size - 1 - at
    }

    private fun take(pages: Iterator<TextPage>, into: MutableList<TextPage>, chunkChars: Int) {
        var chars = 0
        while (pages.hasNext() && chars < chunkChars) {
            val page = pages.next()
            into += page
            chars += page.end - page.start
        }
    }

    companion object {
        /** About this many characters per step, so a step stays short and a cancel lands soon. */
        const val CHUNK_CHARS = 10_000

        /** Pages laid out past the reading position before anything else: a spread and the spread after it. */
        const val LOOKAHEAD_PAGES = 4
    }
}
