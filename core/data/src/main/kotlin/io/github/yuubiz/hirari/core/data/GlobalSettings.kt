@file:UseSerializers(ColorPaletteSerializer::class, RgbSerializer::class, ReaderBackgroundSerializer::class, TextTypefaceSerializer::class)

package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.ColorPalette
import io.github.yuubiz.hirari.core.model.AppTheme
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.BreathSensitivity
import io.github.yuubiz.hirari.core.model.LibrarySection
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.CoverAspect
import io.github.yuubiz.hirari.core.model.EntryOrder
import io.github.yuubiz.hirari.core.model.FallbackCharset
import io.github.yuubiz.hirari.core.model.GutterStyle
import io.github.yuubiz.hirari.core.model.HistorySort
import io.github.yuubiz.hirari.core.model.KeepAwake
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibraryTextSize
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.LoupeFrame
import io.github.yuubiz.hirari.core.model.LoupeZoom
import io.github.yuubiz.hirari.core.model.OverlayTextSize
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReopenLastBook
import io.github.yuubiz.hirari.core.model.Rgb
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.ScreenSpot
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.TextLatinMode
import io.github.yuubiz.hirari.core.model.TextLayout
import io.github.yuubiz.hirari.core.model.TextMargin
import io.github.yuubiz.hirari.core.model.TextPaper
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.VolumeKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The global settings of docs/spec.md section 4.10. Property names are the internal names of the spec and
 * the keys of the stored and exported JSON, so renaming one loses what users chose.
 */
@Serializable
data class GlobalSettings(
    val readingDirection: ReadingDirection = ReadingDirection.RightToLeft,
    val layoutMode: LayoutMode = LayoutMode.Spread,
    val coverAlone: Boolean = false,
    val entryOrder: EntryOrder = EntryOrder.Natural,
    val atBookEnd: BookEndAction = BookEndAction.Ask,

    val scaleMode: ScaleMode = ScaleMode.FitPage,
    val capAtOriginal: Boolean = false,
    val trimStrength: TrimStrength = TrimStrength.Off,
    val gutterStyle: GutterStyle = GutterStyle.Gap,
    val readerBackground: ReaderBackground = ReaderBackground.DarkGray,
    val adjustLevel: Int = 0,
    val adjustSharpen: Boolean = false,
    val adjustMono: Boolean = false,
    val adjustNegative: Boolean = false,
    val turnEffect: TurnEffect = TurnEffect.Cover,
    val turnDurationMs: Int = 150,
    val stopOrder: StopOrder = StopOrder.RowsFirst,
    val stopMaxStepPercent: Int = 35,
    val loupeZoom: LoupeZoom = LoupeZoom.View200,
    val loupeFrame: LoupeFrame = LoupeFrame.Small,
    val loupeHoldMs: Int = 800,

    val tapLayout: TapLayout = TapLayout.OneSideBand,
    val tapBandPercent: Int = 10,
    val mirrorControls: Boolean = false,
    val tapAdvancesStops: Boolean = true,
    val swipeToTurn: Boolean = true,
    val volumeKeys: VolumeKeys = VolumeKeys.DownAdvances,
    val turnHaptics: Boolean = false,
    val barHeightDp: Int = 60,
    @Serializable(with = CommandBarItemsSerializer::class)
    val commandBarItems: List<ReaderCommand> = ReaderCommand.Defaults,
    val autoPlaySeconds: Double = 1.5,

    val pageNumberSpot: ScreenSpot = ScreenSpot.Off,
    val statusSpot: ScreenSpot = ScreenSpot.Off,
    val overlayTextSize: OverlayTextSize = OverlayTextSize.Medium,
    val hideSystemBars: Boolean = true,
    val keepAwake: KeepAwake = KeepAwake.System,
    val readerOrientation: ReaderOrientation = ReaderOrientation.System,
    /** Percent, or `null` for the device setting. */
    val readerBrightness: Int? = null,

    val textTypeface: TextTypeface = TextTypeface.Mincho,
    val textSizeSp: Int = 20,
    val textLineGap: Double = 0.4,
    val textLetterGap: Double = 0.1,
    val textMargin: TextMargin = TextMargin.Medium,
    val textLayout: TextLayout = TextLayout.Auto,
    val textLatinMode: TextLatinMode = TextLatinMode.TateChuYoko,
    val textPaper: TextPaper = TextPaper.Cream,
    val textInkOverride: Rgb? = null,
    val textPaperOverride: Rgb? = null,

    val libraryView: LibraryView = LibraryView.Grid,
    val thumbSize: ThumbSize = ThumbSize.Medium,
    /** Columns of the file list's grid in a portrait window, or `null` to fit cells of [thumbSize] (自動). */
    val gridColumnsPortrait: Int? = null,
    /** As [gridColumnsPortrait], in a landscape window. */
    val gridColumnsLandscape: Int? = null,
    val gridNameLines: Int = 2,
    val gridNoteLine: Boolean = true,
    val coverAspect: CoverAspect = CoverAspect.Book,
    val libraryTextSize: LibraryTextSize = LibraryTextSize.Medium,
    val libraryGrouping: LibraryGrouping = LibraryGrouping.None,
    val librarySubgrouping: LibraryGrouping = LibraryGrouping.None,
    val librarySection: LibrarySection = LibrarySection.None,
    val mergeCircleAuthors: Boolean = true,
    val librarySort: LibrarySort = LibrarySort.Name,
    val foldersFirst: Boolean = true,
    val showDotFiles: Boolean = false,
    val showParentItem: Boolean = true,
    val allowFileEdits: Boolean = false,
    val reopenLastFolder: Boolean = false,
    val reopenLastBook: ReopenLastBook = ReopenLastBook.Off,
    /** 0 keeps no history. */
    val historyLimit: Int = 100,
    val historySort: HistorySort = HistorySort.Newest,
    val bookmarkSort: BookmarkSort = BookmarkSort.PathAscending,

    val appTheme: AppTheme = AppTheme.System,
    val colorPalette: ColorPalette = ColorPalette.Wallpaper,
    val pureBlack: Boolean = false,
    val fallbackCharset: FallbackCharset = FallbackCharset.Cp932,

    val breathControl: Boolean = false,
    val breathSensitivity: BreathSensitivity = BreathSensitivity.Medium,
) {
    /** Moves each number to the nearest allowed value and drops repeated command bar items. */
    fun normalized(): GlobalSettings = copy(
        adjustLevel = adjustLevel.snapTo(ADJUST_LEVEL),
        turnDurationMs = turnDurationMs.snapTo(TURN_DURATION_MS),
        stopMaxStepPercent = stopMaxStepPercent.snapTo(STOP_MAX_STEP_PERCENT),
        loupeHoldMs = loupeHoldMs.snapTo(LOUPE_HOLD_MS),
        tapBandPercent = tapBandPercent.snapTo(
            if (tapLayout == TapLayout.BothSideBands || tapLayout == TapLayout.TopAndBottomBands) TAP_TWO_BAND_PERCENT else TAP_BAND_PERCENT,
        ),
        barHeightDp = barHeightDp.snapTo(BAR_HEIGHT_DP),
        commandBarItems = commandBarItems.distinct().take(ReaderCommand.MAX_ITEMS),
        autoPlaySeconds = AUTO_PLAY_SECONDS.snap(autoPlaySeconds),
        readerBrightness = readerBrightness?.snapTo(READER_BRIGHTNESS),
        gridColumnsPortrait = gridColumnsPortrait?.coerceIn(GRID_COLUMNS_PORTRAIT.first, GRID_COLUMNS_PORTRAIT.last),
        gridColumnsLandscape = gridColumnsLandscape?.coerceIn(GRID_COLUMNS_LANDSCAPE.first, GRID_COLUMNS_LANDSCAPE.last),
        gridNameLines = gridNameLines.coerceIn(GRID_NAME_LINES.first, GRID_NAME_LINES.last),
        textSizeSp = textSizeSp.snapTo(TEXT_SIZE_SP),
        textLineGap = TEXT_LINE_GAP.snap(textLineGap),
        textLetterGap = TEXT_LETTER_GAP.snap(textLetterGap),
        historyLimit = HISTORY_LIMITS.minBy { abs(it.toLong() - historyLimit) },
    )

    companion object {
        val ADJUST_LEVEL = -5..5
        val TURN_DURATION_MS = 0..1000 step 50
        val STOP_MAX_STEP_PERCENT = 5..100 step 5
        val LOUPE_HOLD_MS = 200..3200 step 200
        val TAP_BAND_PERCENT = 10..90 step 10

        /** Each of two bands stays at or under 45% (docs/spec.md section 4.10). */
        val TAP_TWO_BAND_PERCENT = 10..40 step 10
        val BAR_HEIGHT_DP = 40..120 step 4
        val AUTO_PLAY_SECONDS = DecimalSteps(0.5, 30.0, 0.5)
        val READER_BRIGHTNESS = 0..100 step 10
        val TEXT_SIZE_SP = 6..62
        val TEXT_LINE_GAP = DecimalSteps(0.0, 2.5, 0.1)
        val TEXT_LETTER_GAP = DecimalSteps(0.0, 2.5, 0.05)
        val HISTORY_LIMITS = listOf(0, 20, 50, 100, 200, 500)
        val GRID_COLUMNS_PORTRAIT = 2..6
        val GRID_COLUMNS_LANDSCAPE = 2..12
        val GRID_NAME_LINES = 1..3
    }
}

/** Decimal settings in steps of whole hundredths, so a snapped value prints as the decimal it stands for. */
data class DecimalSteps(val min: Double, val max: Double, val step: Double) {
    fun snap(value: Double): Double {
        val low = (min * 100).roundToLong()
        val each = (step * 100).roundToLong()
        val count = if (value.isNaN()) 0 else ((value.coerceIn(min, max) * 100 - low) / each).roundToLong()
        return (low + count * each) / 100.0
    }
}

private fun Int.snapTo(allowed: IntProgression): Int {
    val offset = coerceIn(allowed.first, allowed.last) - allowed.first
    return allowed.first + (offset + allowed.step / 2) / allowed.step * allowed.step
}
