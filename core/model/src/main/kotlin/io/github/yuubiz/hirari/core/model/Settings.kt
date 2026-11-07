package io.github.yuubiz.hirari.core.model

// The value types of the global settings of docs/spec.md section 4.10.

enum class EntryOrder { Natural, Stored }

/** The `atBookEnd` setting (docs/spec.md section 4.8, 本の端での動作). */
enum class BookEndAction { Ask, NextBook, Close, Stop }

enum class GutterStyle { None, Gap, Shadow }

/** Both readers' `barDisplay` setting (docs/spec.md section 4.8, バーを出す表示). */
enum class BarDisplay {
    Hidden, Portrait, Always;

    /** [barHeight] is in the unit of [screen]. Three strips that would leave the page less than half the height stay hidden. */
    fun shown(screen: Size, barHeight: Float): Boolean =
        (this == Always || this == Portrait && screen.width < screen.height) && screen.height - 3 * barHeight >= screen.height / 2
}

/** An opaque color, `0xRRGGBB` in the low 24 bits. */
@JvmInline
value class Rgb(val value: Int) {
    init {
        require(value in 0..0xFFFFFF) { "color ${value.toString(16)} is not 0xRRGGBB" }
    }
}

sealed interface ReaderBackground {
    data object Black : ReaderBackground
    data object DarkGray : ReaderBackground
    data object White : ReaderBackground
    data object Theme : ReaderBackground
    data class Custom(val color: Rgb) : ReaderBackground
}

enum class LoupeZoom { View150, View200, View250, View300, Source100, Source200, Source300 }

enum class LoupeFrame { Small, Medium, Large }

/** The commands the command bar can hold (docs/spec.md section 4.8, コマンドバー). */
enum class ReaderCommand {
    Layout,
    ShiftPairing,
    ScaleMode,
    Trim,
    Direction,
    Rotate,
    Orientation,
    AddBookmark,
    Bookmarks,
    AutoPlay,
    ReverseOrder,
    ImageAdjust,
    MirrorControls,
    StopOrder,
    Gutter,
    EditCommandBar,
    Breath,
    BookSettings,
    SetCover,
    SharePage,
    Settings,
    ;

    companion object {
        val Defaults = listOf(Orientation, Trim, Layout, ScaleMode)
        const val MAX_ITEMS = 8
    }
}

enum class ScreenSpot { Off, TopLeft, TopCenter, TopRight, BottomLeft, BottomCenter, BottomRight }

enum class OverlayTextSize { Small, Medium, Large }

enum class KeepAwake { System, TenMinutes, Always }

enum class ReaderOrientation { System, Portrait, Landscape }

sealed interface TextTypeface {
    data object Mincho : TextTypeface
    data object Gothic : TextTypeface

    /** A font the user imported into `filesDir/fonts/`. */
    data class Imported(val fileName: String) : TextTypeface
}

enum class TextMargin(val dp: Int) { Narrow(16), Medium(32), Wide(48) }

enum class TextLayout { Single, Spread, Continuous, Auto }

enum class TextLatinMode { TateChuYoko, Sideways, Upright }

enum class TextPaper { White, Cream, Sepia, Black }

enum class LibraryView { Grid, ThumbnailList, List }

/** Grouping of the folder file list (docs/spec.md section 4.3). */
enum class LibraryGrouping { None, Author, Series, Original, Event }

/** Headers of the visible list, independent of pseudo folders. */
enum class LibrarySection { None, ReadState, AddedTime }

enum class ThumbSize { Small, Medium, Large }

/** Width over height of the cover box in the file list (docs/spec.md section 4.3, 表紙の枠). */
enum class CoverAspect(val ratio: Float) { Book(0.7f), Square(1f) }

/** What the file list multiplies the device's text size by (docs/spec.md section 4.3, 文字の大きさ). */
enum class LibraryTextSize(val scale: Float) { Small(0.85f), Medium(1f), Large(1.15f) }

enum class LibrarySort { Name, Newest, Oldest }

enum class ReopenLastBook { Off, Ask, Immediately }

enum class AppTheme { System, Light, Dark }

/** A whole palette, or a seed color, rather than independently chosen roles. */
sealed interface ColorPalette {
    data object Wallpaper : ColorPalette
    data object Standard : ColorPalette
    data object Indigo : ColorPalette
    data object Bamboo : ColorPalette
    data object Sakura : ColorPalette
    data object Orange : ColorPalette
    data object Ink : ColorPalette
    data class Custom(val color: Rgb) : ColorPalette

    companion object {
        val presets: List<ColorPalette> = listOf(Wallpaper, Standard, Indigo, Bamboo, Sakura, Orange, Ink)
    }
}

/** [charsetName] is the Java name of the charset. */
enum class FallbackCharset(val charsetName: String) {
    Cp932("windows-31j"),
    Utf8("UTF-8"),
    EucJp("EUC-JP"),
    Gbk("GBK"),
    Big5("Big5"),
    EucKr("EUC-KR"),
}

enum class BreathSensitivity { Low, Medium, High }

/** The orders of the history tab (docs/spec.md section 4.6). */
enum class HistorySort { PathAscending, PathDescending, Oldest, Newest }

/** The orders of the bookmarks tab (docs/spec.md section 4.6). */
enum class BookmarkSort { PathAscending, PathDescending, Oldest, Newest, NameAscending, NameDescending }
