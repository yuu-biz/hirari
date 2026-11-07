package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.AppTheme
import io.github.yuubiz.hirari.core.model.BarDisplay
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.BreathSensitivity
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

/** Every setting away from its default, at an allowed value. */
val everything = GlobalSettings(
    readingDirection = ReadingDirection.LeftToRight,
    layoutMode = LayoutMode.SplitWide,
    coverAlone = true,
    entryOrder = EntryOrder.Stored,
    atBookEnd = BookEndAction.NextBook,
    scaleMode = ScaleMode.FitWidth,
    capAtOriginal = true,
    trimStrength = TrimStrength.Strong,
    gutterStyle = GutterStyle.Shadow,
    readerBackground = ReaderBackground.Custom(Rgb(0x123ABC)),
    adjustLevel = -3,
    adjustSharpen = true,
    adjustMono = true,
    adjustNegative = true,
    turnEffect = TurnEffect.Fade,
    turnDurationMs = 300,
    stopOrder = StopOrder.ColumnsFirst,
    stopMaxStepPercent = 50,
    loupeZoom = LoupeZoom.Source300,
    loupeFrame = LoupeFrame.Large,
    loupeHoldMs = 1600,
    tapLayout = TapLayout.TopAndBottomBands,
    tapBandPercent = 30,
    mirrorControls = true,
    tapAdvancesStops = false,
    swipeToTurn = false,
    volumeKeys = VolumeKeys.UpAdvances,
    turnHaptics = true,
    barHeightDp = 80,
    commandBarItems = listOf(ReaderCommand.Settings, ReaderCommand.AddBookmark),
    autoPlaySeconds = 2.5,
    barDisplay = BarDisplay.Always,
    pageNumberSpot = ScreenSpot.BottomCenter,
    statusSpot = ScreenSpot.TopRight,
    overlayTextSize = OverlayTextSize.Large,
    hideSystemBars = false,
    keepAwake = KeepAwake.Always,
    readerOrientation = ReaderOrientation.Landscape,
    readerBrightness = 70,
    textTypeface = TextTypeface.Imported("my font.otf"),
    textSizeSp = 31,
    textLineGap = 1.3,
    textLetterGap = 0.35,
    textMargin = TextMargin.Wide,
    textLayout = TextLayout.Continuous,
    textLatinMode = TextLatinMode.Upright,
    textPaper = TextPaper.Sepia,
    textInkOverride = Rgb(0x000000),
    textPaperOverride = Rgb(0xFFFFFF),
    libraryView = LibraryView.List,
    thumbSize = ThumbSize.Large,
    gridColumnsPortrait = 3,
    gridColumnsLandscape = 7,
    gridNameLines = 3,
    gridNoteLine = false,
    libraryGrouping = LibraryGrouping.Author,
    librarySubgrouping = LibraryGrouping.Series,
    librarySection = io.github.yuubiz.hirari.core.model.LibrarySection.AddedTime,
    mergeCircleAuthors = false,
    coverAspect = CoverAspect.Square,
    libraryTextSize = LibraryTextSize.Large,
    librarySort = LibrarySort.Oldest,
    foldersFirst = false,
    showDotFiles = true,
    showParentItem = false,
    allowFileEdits = true,
    reopenLastFolder = true,
    reopenLastBook = ReopenLastBook.Ask,
    historyLimit = 500,
    historySort = HistorySort.PathDescending,
    bookmarkSort = BookmarkSort.NameDescending,
    appTheme = AppTheme.Dark,
    colorPalette = io.github.yuubiz.hirari.core.model.ColorPalette.Standard,
    pureBlack = true,
    fallbackCharset = FallbackCharset.EucJp,
    breathControl = true,
    breathSensitivity = BreathSensitivity.High,
)
