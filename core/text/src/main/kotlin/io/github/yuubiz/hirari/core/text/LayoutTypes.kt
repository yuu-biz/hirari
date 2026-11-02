package io.github.yuubiz.hirari.core.text

/** docs/spec.md Q11: the engine takes the direction as a parameter. v1 implements vertical only. */
enum class WritingDirection { Vertical }

/** The `textLatinMode` setting of docs/spec.md section 4.10. */
enum class LatinMode { ShortTateChuYoko, Sideways, Upright }

const val RUBY_SCALE = 0.6f
const val HEADING_SCALE = 1.2f

/** How far a ruby longer than its base may reach over a neighbor without ruby, in ems of the body size. */
const val RUBY_OVERHANG_EMS = 0.5f

/**
 * The text area of one page and the type settings. [width] and [height] exclude the margins and the bands for the
 * title and page number, which the screen draws.
 */
data class LayoutParams(
    val width: Float,
    val height: Float,
    val fontSize: Float,
    val lineGap: Float = 0.4f,
    val letterGap: Float = 0.1f,
    val latinMode: LatinMode = LatinMode.ShortTateChuYoko,
    val direction: WritingDirection = WritingDirection.Vertical,
) {
    init {
        require(width > 0 && height > 0 && fontSize > 0)
    }

    val rubySize: Float get() = fontSize * RUBY_SCALE
}

/** Horizontal advance widths. Upright glyphs advance one em down the column, so only sideways text is measured. */
fun interface GlyphMetrics {
    fun width(text: String, size: Float, bold: Boolean): Float
}

/** Half an em for ASCII and half-width forms, one em for the rest. Deterministic, for tests. */
object FixedGlyphMetrics : GlyphMetrics {
    override fun width(text: String, size: Float, bold: Boolean): Float {
        var ems = 0f
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            ems += if (cp < 0x80 || cp in 0xFF61..0xFF9F) 0.5f else 1f
            i += Character.charCount(cp)
        }
        return ems * size
    }
}

enum class Orientation { Upright, Sideways, TateChuYoko }

/** What a [Glyph] draws. */
sealed interface GlyphContent {
    data class Chars(val text: String, val orientation: Orientation) : GlyphContent

    /** `――`: one unbroken rule over [ems] ems. */
    data class Dash(val ems: Int) : GlyphContent

    /** `……`: one even row of dots over [ems] ems. */
    data class Leader(val ems: Int) : GlyphContent

    data class Image(val path: String) : GlyphContent
}

/**
 * One drawn unit in a column. [y] is its top from the column top and [advance] its extent down the column. [start]
 * and [end] are its source range.
 */
data class Glyph(
    val content: GlyphContent,
    val y: Float,
    val advance: Float,
    val size: Float,
    val bold: Boolean,
    val start: Int,
    val end: Int,
)

/** Ruby beside its base, from [y] over [length], at [size]. Drawn on the right of the column. */
data class RubyRun(val text: String, val y: Float, val length: Float, val size: Float)

/** Emphasis marks, one centered on each of [centers]. */
data class EmphasisRun(val mark: EmphasisMark, val side: Side, val centers: List<Float>, val size: Float)

data class SideLineRun(val style: LineStyle, val side: Side, val y0: Float, val y1: Float)

/**
 * One column. [x] is the center of its body; ruby and right-side marks sit in a band of [rightBand] beyond
 * `x + bodyWidth / 2`, and left-side marks in [leftBand] beyond `x - bodyWidth / 2`.
 */
data class TextLine(
    val x: Float,
    val bodyWidth: Float,
    val rightBand: Float,
    val leftBand: Float,
    val glyphs: List<Glyph>,
    val rubies: List<RubyRun>,
    val emphasis: List<EmphasisRun>,
    val sideLines: List<SideLineRun>,
    val start: Int,
    val end: Int,
)

sealed interface PageContent {
    data class Lines(val lines: List<TextLine>) : PageContent

    /** A 挿絵 on its own line, fitted to the page. */
    data class Picture(val path: String) : PageContent
}

/**
 * One page. Consecutive pages of one layout cover consecutive source ranges with no gap, so every offset falls on
 * exactly one page.
 */
data class TextPage(val content: PageContent, val start: Int, val end: Int) {
    operator fun contains(offset: Int): Boolean = offset in start until end
}
