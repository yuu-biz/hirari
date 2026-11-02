package io.github.yuubiz.hirari.core.text

/**
 * A text book after notation parsing. Every offset named `start` or `end` indexes the decoded source string of
 * [decodeText] in UTF-16 units, so a position survives any change of layout. Display indices index
 * [Paragraph.text] instead.
 */
class TextDocument(
    val sourceLength: Int,
    val blocks: List<Block>,
    val headings: List<Heading>,
    /** The index in [blocks] of the first block of each source line that made blocks, ascending. */
    val lineStartBlocks: IntArray = IntArray(0),
) {
    /** The first line, which docs/spec.md section 4.9 treats as the title. */
    val title: String? get() = (blocks.firstOrNull() as? Paragraph)?.text?.takeIf { it.isNotBlank() }
}

enum class HeadingLevel { Large, Medium, Small }

data class Heading(val level: HeadingLevel, val text: String, val start: Int)

sealed interface Block {
    /** The source range the block stands for, notations and the line break included. */
    val start: Int
    val end: Int
}

/** One source line. */
class Paragraph(
    /** What is drawn. An inline image is one U+FFFC. */
    val text: String,
    /**
     * The source offset where each display unit starts. Unit `i` stands for the source up to the next unit's start,
     * so the notations that follow a character belong to it. The first unit starts at [start].
     */
    private val offsets: IntArray,
    override val start: Int,
    override val end: Int,
    val spans: List<Span> = emptyList(),
    /** In ems of the body size, from the column start. */
    val indent: Int = 0,
    val wrapIndent: Int = indent,
    val bottom: BottomAlign? = null,
    val isTitle: Boolean = false,
) : Block {
    init {
        require(offsets.size == text.length && (offsets.isEmpty() || offsets[0] == start))
    }

    /** Where display unit [displayIndex] starts in the source; [end] for `text.length`. */
    fun sourceStart(displayIndex: Int): Int = if (displayIndex < offsets.size) offsets[displayIndex] else end

    /** The display unit whose source range holds [offset], which must lie in `start until end`. */
    fun displayIndexOf(offset: Int): Int {
        var lo = 0
        var hi = offsets.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (offsets[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}

/** 地付き and 地から N 字上げ: the text from display index [from] ends [raise] ems above the column end. */
data class BottomAlign(val from: Int, val raise: Int)

/** 改ページ, 改丁, and 改見開き. */
data class PageBreak(override val start: Int, override val end: Int) : Block

/** ページの左右中央: the page this falls on centers its lines across the page. */
data class CenterPage(override val start: Int, override val end: Int) : Block

/** A 挿絵 on a line of its own. [path] is relative to the text file. */
data class Figure(val path: String, override val start: Int, override val end: Int) : Block

enum class MarkSide { Right, Left }

enum class EmphasisMark(val glyph: String) {
    Sesame("﹅"),
    WhiteSesame("﹆"),
    Circle("●"),
    WhiteCircle("○"),
    Triangle("▲"),
    WhiteTriangle("△"),
    Bullseye("◎"),
    Fisheye("◉"),
}

enum class LineStyle { Solid, Double, Chain, Dashed, Wavy }

/** Styling over display indices `start until end` of one [Paragraph]. */
sealed interface Span {
    val start: Int
    val end: Int

    data class Ruby(override val start: Int, override val end: Int, val text: String) : Span
    data class Emphasis(override val start: Int, override val end: Int, val mark: EmphasisMark, val side: MarkSide) : Span
    data class SideLine(override val start: Int, override val end: Int, val style: LineStyle, val side: MarkSide) : Span
    data class Bold(override val start: Int, override val end: Int) : Span
    data class HeadingText(override val start: Int, override val end: Int, val level: HeadingLevel) : Span
    data class TateChuYoko(override val start: Int, override val end: Int) : Span

    /** A 挿絵 in the middle of a line, drawn one em square. Covers its U+FFFC. */
    data class InlineImage(override val start: Int, val path: String) : Span {
        override val end: Int get() = start + 1
    }
}
