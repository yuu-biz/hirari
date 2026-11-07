package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.GutterStyle
import io.github.yuubiz.hirari.core.model.Rgb
import io.github.yuubiz.hirari.core.model.TextLatinMode
import io.github.yuubiz.hirari.core.model.TextLayout
import io.github.yuubiz.hirari.core.model.TextPaper
import io.github.yuubiz.hirari.core.text.LatinMode
import io.github.yuubiz.hirari.core.text.LayoutParams

/** The screen the reader draws on, in pixels, with the density and font scale that turn dp and sp into pixels. */
data class Viewport(val width: Int, val height: Int, val density: Float, val fontScale: Float = 1f)

/** Information around the page, as the title and the page number, is drawn at this fraction of the body size. */
const val INFO_SCALE = 0.8f

/**
 * Where one page goes, in pixels (docs/spec.md section 4.9). A page is [pageWidth] by [pageHeight]. Its text area,
 * the size the engine lays out in, sits [margin] in from each side, below a band of [band] for the title and above
 * one for the page number. Both bands are zero while the screen's strips show that information.
 */
data class PageFrame(
    val pageWidth: Float,
    val pageHeight: Float,
    val margin: Float,
    val band: Float,
    /** The gap between neighboring pages, in the reader background. */
    val gutter: Float,
    val pagesPerView: Int,
    val continuous: Boolean,
    val params: LayoutParams,
) {
    val infoSize: Float get() = params.fontSize * INFO_SCALE
}

/** The page frame for [viewport] under the text settings. 自動 is a spread on a screen wider than tall. */
fun pageFrame(viewport: Viewport, settings: GlobalSettings): PageFrame {
    val regions = viewport.textRegions(settings)
    val pageArea = regions.pageArea
    val fontSize = settings.textSizeSp * viewport.density * viewport.fontScale
    val spread = when (settings.textLayout) {
        TextLayout.Spread -> true
        TextLayout.Auto -> viewport.width > viewport.height
        TextLayout.Single, TextLayout.Continuous -> false
    }
    val pages = if (spread) 2 else 1
    val continuous = settings.textLayout == TextLayout.Continuous
    val gutter = if ((spread && settings.gutterStyle == GutterStyle.Gap) || continuous) GUTTER_DP * viewport.density else 0f
    val pageWidth = (pageArea.width * viewport.density - gutter * (pages - 1)) / pages
    val pageHeight = (pageArea.height * viewport.density).coerceAtLeast(1f)
    var band = if (regions.commandRow != null) 0f else fontSize * INFO_SCALE * 2
    val minimum = fontSize * 2
    val margin = minOf(
        settings.textMargin.dp * viewport.density,
        ((pageWidth - minimum) / 2).coerceAtLeast(0f),
        ((pageHeight - 2 * band - minimum) / 2).coerceAtLeast(0f),
    )
    if (pageHeight - 2 * margin - 2 * band < minimum || pageWidth - 2 * margin < minimum) band = 0f
    val width = (pageWidth - 2 * margin).coerceAtLeast(1f)
    val height = (pageHeight - 2 * margin - 2 * band).coerceAtLeast(1f)
    val params = LayoutParams(
        width = width,
        height = height,
        fontSize = fontSize,
        oneLinePerPage = width < minimum || height < minimum,
        lineGap = settings.textLineGap.toFloat(),
        letterGap = settings.textLetterGap.toFloat(),
        latinMode = when (settings.textLatinMode) {
            TextLatinMode.TateChuYoko -> LatinMode.ShortTateChuYoko
            TextLatinMode.Sideways -> LatinMode.Sideways
            TextLatinMode.Upright -> LatinMode.Upright
        },
    )
    return PageFrame(pageWidth, pageHeight, margin, band, gutter, pages, continuous, params)
}

/** The gap between the pages of a spread, when the gutter setting shows one, and between pages in a row. */
const val GUTTER_DP = 8f

/** The colors of the page: the 紙の色 preset with its overrides (docs/spec.md section 4.9, 書体と色). */
data class PageColors(val paper: Rgb, val ink: Rgb, val highlight: Rgb)

fun pageColors(settings: GlobalSettings): PageColors {
    val (paper, ink) = when (settings.textPaper) {
        TextPaper.White -> 0xFFFFFF to 0x000000
        TextPaper.Cream -> 0xFFFFC8 to 0x000000
        TextPaper.Sepia -> 0xF4ECD8 to 0x5B4636
        TextPaper.Black -> 0x000000 to 0xD0D0D0
    }
    val paperColor = settings.textPaperOverride ?: Rgb(paper)
    return PageColors(paperColor, settings.textInkOverride ?: Rgb(ink), Rgb(if (isDark(paperColor)) DARK_HIGHLIGHT else LIGHT_HIGHLIGHT))
}

/** The search highlight on a light paper, and its counterpart on a dark one, which keeps light ink readable. */
private const val LIGHT_HIGHLIGHT = 0xEBC5E2
private const val DARK_HIGHLIGHT = 0x6B3F61

private fun isDark(color: Rgb): Boolean {
    val r = color.value shr 16 and 0xFF
    val g = color.value shr 8 and 0xFF
    val b = color.value and 0xFF
    return r * 299 + g * 587 + b * 114 < 128_000
}
