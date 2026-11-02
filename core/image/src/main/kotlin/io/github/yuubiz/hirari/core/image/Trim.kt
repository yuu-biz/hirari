package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.TrimStrength
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A grayscale image, one unsigned byte a pixel, row by row. */
class LumaImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height) {
            "${pixels.size} pixels for ${width}x$height"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    companion object {
        /** [argb] by the BT.601 weights. Alpha is ignored. */
        fun fromArgb(width: Int, height: Int, argb: IntArray): LumaImage {
            val luma = ByteArray(argb.size)
            for (i in argb.indices) {
                val c = argb[i]
                luma[i] = ((((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8).toByte()
            }
            return LumaImage(width, height, luma)
        }
    }
}

enum class Edge { Left, Top, Right, Bottom }

/** Pixels to cut from each edge. */
data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    operator fun get(edge: Edge): Int = when (edge) {
        Edge.Left -> left
        Edge.Top -> top
        Edge.Right -> right
        Edge.Bottom -> bottom
    }

    companion object {
        val None = Insets(0, 0, 0, 0)

        internal inline fun of(cut: (Edge) -> Int): Insets = Insets(cut(Edge.Left), cut(Edge.Top), cut(Edge.Right), cut(Edge.Bottom))
    }
}

/** The rules of docs/spec.md section 4.8 that keep a trim from cutting too much. */
enum class TrimRule {
    /**
     * The art reaches the paper's edge, so that edge is not cut: its outer band varies, or a faint picture runs
     * inward from it.
     */
    BleedEdge,

    /** A wide page cuts its left and right by the smaller of the two, so that the fold stays in the middle. */
    WideSymmetric,

    /** The pages of a pair cut their top and bottom by the smaller share of the two, so that they stay level. */
    PairHeight,

    /** Vetoes the trim: the width or height left would be 20 px or less. */
    TooSmall,

    /** Vetoes the trim: the width or height left would be under half of the page's. */
    UnderHalf,

    /** Vetoes the trim: the aspect ratio would change by a factor of 1.3 or more. */
    AspectChanged,
}

/** The side of the copy the margins are read from (docs/spec.md section 4.8). */
const val TRIM_ANALYSIS_SIDE: Int = 512

/** The size of the analysis copy of a page of [page] pixels: the longer side at 512, never enlarged. */
fun trimAnalysisSize(page: PixelSize): PixelSize {
    val longer = max(page.width, page.height)
    if (longer <= TRIM_ANALYSIS_SIDE) return page
    fun scaled(side: Int) = ((side.toLong() * TRIM_ANALYSIS_SIDE + longer / 2) / longer).toInt().coerceAtLeast(1)
    return PixelSize(scaled(page.width), scaled(page.height))
}

/**
 * What docs/spec.md section 4.8 reads from one page, for every strength at once. Cuts come in pixels of [page].
 * The reader keeps one per page and asks it for the trim of whichever strength is set.
 */
class MarginScan internal constructor(val page: PixelSize, private val lines: Map<Edge, EdgeLines?>) {
    /** The edges whose band varies, which no strength cuts. */
    val bleedEdges: Set<Edge> = lines.filterValues { it == null }.keys

    fun trim(strength: TrimStrength): PageTrim {
        val level = strength.level() ?: return PageTrim.settled(page, Insets.None, emptyList())
        val adjustments = mutableListOf<TrimRule>()
        val cuts = Edge.entries.associateWith { edge ->
            val side = if (edge == Edge.Left || edge == Edge.Right) page.width else page.height
            lines[edge]?.cut(level, side)
        }
        if (null in cuts.values) adjustments += TrimRule.BleedEdge
        var proposed = Insets.of { cuts[it] ?: 0 }
        if (page.width > page.height && proposed.left != proposed.right) {
            val side = min(proposed.left, proposed.right)
            proposed = proposed.copy(left = side, right = side)
            adjustments += TrimRule.WideSymmetric
        }
        return PageTrim.settled(page, proposed, adjustments)
    }
}

/**
 * The trim of one page. [cut] is what to take off each edge of the page, and [fired] the rules that changed it,
 * in the order they applied. A rule that vetoes leaves [cut] at [Insets.None].
 */
class PageTrim private constructor(
    val page: PixelSize,
    internal val proposed: Insets,
    internal val adjustments: List<TrimRule>,
    private val vetoes: List<TrimRule>,
) {
    val cut: Insets get() = if (vetoes.isEmpty()) proposed else Insets.None
    val fired: List<TrimRule> get() = adjustments + vetoes
    val content: PixelRect get() = PixelRect(cut.left, cut.top, page.width - cut.right, page.height - cut.bottom)

    internal val isVetoed: Boolean get() = vetoes.isNotEmpty()

    override fun toString(): String = "PageTrim(page=$page, cut=$cut, fired=$fired)"

    internal companion object {
        fun settled(page: PixelSize, proposed: Insets, adjustments: List<TrimRule>): PageTrim {
            val keptWidth = page.width - proposed.left - proposed.right
            val keptHeight = page.height - proposed.top - proposed.bottom
            val vetoes = if (proposed == Insets.None) {
                emptyList()
            } else {
                pageVetoes.filter { it.rejects(page, keptWidth, keptHeight) }.map { it.rule }
            }
            return PageTrim(page, proposed, adjustments, vetoes)
        }
    }
}

/**
 * The trims of the two pages of a pair, from the [trim][MarginScan.trim] of each (docs/spec.md section 4.8). Both
 * cut their top and bottom by the smaller share of their height. When a page is then vetoed, it cuts nothing, so
 * the other aligns to that and is checked again.
 */
fun trimPair(first: PageTrim, second: PageTrim): Pair<PageTrim, PageTrim> {
    var vetoedFirst: PageTrim? = null
    var vetoedSecond: PageTrim? = null
    while (true) {
        val firstCut = if (vetoedFirst == null) first.proposed else Insets.None
        val secondCut = if (vetoedSecond == null) second.proposed else Insets.None
        val top = levelled(firstCut.top, first.page.height, secondCut.top, second.page.height)
        val bottom = levelled(firstCut.bottom, first.page.height, secondCut.bottom, second.page.height)
        val a = vetoedFirst ?: aligned(first, top.first, bottom.first)
        val b = vetoedSecond ?: aligned(second, top.second, bottom.second)
        val newlyVetoed = (vetoedFirst == null && a.isVetoed) || (vetoedSecond == null && b.isVetoed)
        if (!newlyVetoed) return a to b
        if (a.isVetoed) vetoedFirst = a
        if (b.isVetoed) vetoedSecond = b
    }
}

private fun aligned(page: PageTrim, top: Int, bottom: Int): PageTrim {
    val proposed = page.proposed.copy(top = top, bottom = bottom)
    val adjustments = if (proposed == page.proposed) page.adjustments else page.adjustments + TrimRule.PairHeight
    return PageTrim.settled(page.page, proposed, adjustments)
}

/** Cuts of [a] of [aSide] and [b] of [bSide], each lowered to the smaller share of its side, rounded down. */
private fun levelled(a: Int, aSide: Int, b: Int, bSide: Int): Pair<Int, Int> =
    min(a.toLong(), b.toLong() * aSide / bSide).toInt() to min(b.toLong(), a.toLong() * bSide / aSide).toInt()

private class Veto(val rule: TrimRule, val rejects: (page: PixelSize, keptWidth: Int, keptHeight: Int) -> Boolean)

private val pageVetoes = listOf(
    Veto(TrimRule.TooSmall) { _, width, height -> width <= MIN_KEPT_SIDE || height <= MIN_KEPT_SIDE },
    Veto(TrimRule.UnderHalf) { page, width, height -> 2L * width < page.width || 2L * height < page.height },
    Veto(TrimRule.AspectChanged) { page, width, height ->
        val wider = width.toLong() * page.height
        val taller = height.toLong() * page.width
        width > 0 && height > 0 && 10 * max(wider, taller) >= 13 * min(wider, taller)
    },
)

private const val MIN_KEPT_SIDE = 20

/** One row of the strength table of docs/spec.md section 4.8. */
internal class TrimLevel(val thresholdPermille: Int, val rangePercent: Int, val cutPercent: Int)

private fun TrimStrength.level(): TrimLevel? = when (this) {
    TrimStrength.Off -> null
    TrimStrength.Weak -> TrimLevel(thresholdPermille = 5, rangePercent = 25, cutPercent = 60)
    TrimStrength.Medium -> TrimLevel(thresholdPermille = 6, rangePercent = 30, cutPercent = 80)
    TrimStrength.Strong -> TrimLevel(thresholdPermille = 7, rangePercent = 45, cutPercent = 90)
    TrimStrength.Strongest -> TrimLevel(thresholdPermille = 8, rangePercent = 100, cutPercent = 100)
}

/**
 * The lines of one edge from the edge inward: [differing] counts, for each, the pixels that differ from the
 * edge's background by more than 32, out of the [span] pixels counted in every line, and [toned] tells the lines
 * whose median is more than 8 levels off the background.
 */
internal class EdgeLines(private val span: Int, private val differing: IntArray, private val toned: BooleanArray) {
    /**
     * The pixels of a side of [side] pixels to cut at [level], rounded down, or null when the edge is not cut:
     * when two toned lines in a row come before the content, a faint picture runs to the edge.
     */
    fun cut(level: TrimLevel, side: Int): Int? {
        val start = contentStart(level)
        if ((0 until start - 1).any { toned[it] && toned[it + 1] }) return null
        val found = min(start, differing.size * level.rangePercent / 100)
        val inPage = found.toLong() * side / differing.size
        return (inPage * level.cutPercent / 100).toInt()
    }

    /** The first of the first two content lines in a row on the whole side, or the side's length when there are none. */
    private fun contentStart(level: TrimLevel): Int {
        fun isMargin(line: Int) = differing[line].toLong() * 1000 < level.thresholdPermille.toLong() * span
        for (line in differing.indices) {
            if (!isMargin(line) && (line + 1 == differing.size || !isMargin(line + 1))) return line
        }
        return differing.size
    }
}

/**
 * Reads [image], an analysis copy of a page of [page] pixels, by steps 2 and 3 of docs/spec.md section 4.8. A line
 * is counted without the bands of the two edges across it, so that a shadow or a scan line along one edge does
 * not stop the edges next to it.
 */
fun scanMargins(image: LumaImage, page: PixelSize): MarginScan {
    val bandRows = band(image.height)
    val bandColumns = band(image.width)
    val columns = inner(image.width, bandColumns)
    val rows = inner(image.height, bandRows)
    val last = image.width - 1
    val bottom = image.height - 1
    return MarginScan(
        page,
        mapOf(
            Edge.Top to edgeLines(image.height, bandRows, columns) { line, x -> image[x, line] },
            Edge.Bottom to edgeLines(image.height, bandRows, columns) { line, x -> image[x, bottom - line] },
            Edge.Left to edgeLines(image.width, bandColumns, rows) { line, y -> image[line, y] },
            Edge.Right to edgeLines(image.width, bandColumns, rows) { line, y -> image[last - line, y] },
        ),
    )
}

/** The lines of the outer 2% band of a side of [side] lines, at least one. */
private fun band(side: Int): Int = ((side * 2 + 99) / 100).coerceAtLeast(1)

/** The pixels of a line of [length] without a band of [band] at each end, or all of them when nothing would be left. */
private fun inner(length: Int, band: Int): IntRange = if (length > 2 * band) band until length - band else 0 until length

/**
 * The lines of one edge, or null when its band varies: when the middle 80% of the band's pixels spans more than
 * 32 levels, or the medians of the eight parts of the band along the edge span more than 8. The background is the
 * band's median.
 */
private inline fun edgeLines(lineCount: Int, bandLines: Int, span: IntRange, pixel: (line: Int, at: Int) -> Int): EdgeLines? {
    val length = span.last - span.first + 1
    val histogram = IntArray(256)
    for (line in 0 until bandLines) for (at in span) histogram[pixel(line, at)]++
    val total = bandLines * length
    if (percentile(histogram, total, 90) - percentile(histogram, total, 10) > DIFFERENT) return null
    val background = percentile(histogram, total, 50)
    val parts = min(BAND_PARTS, length)
    val partMedians = IntArray(parts) { part ->
        val from = span.first + part * length / parts
        val until = span.first + (part + 1) * length / parts
        val partHistogram = IntArray(256)
        for (line in 0 until bandLines) for (at in from until until) partHistogram[pixel(line, at)]++
        percentile(partHistogram, bandLines * (until - from), 50)
    }
    if (partMedians.max() - partMedians.min() > TONE) return null
    val differs = BooleanArray(256) { abs(it - background) > DIFFERENT }
    val counts = IntArray(lineCount)
    val toned = BooleanArray(lineCount)
    val lineHistogram = IntArray(256)
    for (line in 0 until lineCount) {
        lineHistogram.fill(0)
        var n = 0
        for (at in span) {
            val value = pixel(line, at)
            if (differs[value]) n++
            lineHistogram[value]++
        }
        counts[line] = n
        toned[line] = abs(percentile(lineHistogram, length, 50) - background) > TONE
    }
    return EdgeLines(length, counts, toned)
}

/** The smallest level at or below which [percent]% of the [total] pixels of [histogram] lie. */
private fun percentile(histogram: IntArray, total: Int, percent: Int): Int {
    val rank = ((total.toLong() * percent + 99) / 100).coerceAtLeast(1)
    var seen = 0L
    for (level in histogram.indices) {
        seen += histogram[level]
        if (seen >= rank) return level
    }
    return histogram.lastIndex
}

/** Levels of luma that tell content from background (docs/spec.md section 4.8). */
private const val DIFFERENT = 32

/** Levels by which the median of a part of a band, or of a line, tells a faint picture from the paper. */
private const val TONE = 8

private const val BAND_PARTS = 8
