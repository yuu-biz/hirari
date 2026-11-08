package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BarDisplay
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.GutterStyle
import io.github.yuubiz.hirari.core.model.KeepAwake
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LoupeFrame
import io.github.yuubiz.hirari.core.model.LoupeZoom
import io.github.yuubiz.hirari.core.model.OverlayTextSize
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.ScreenSpot
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.VolumeKeys
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class ReaderConfigTest {
    /** Each global setting the image reader takes through [ReaderConfig.of], moved away from its default. */
    private val readThroughConfig: Map<String, (GlobalSettings) -> GlobalSettings> = mapOf(
        "readingDirection" to { it.copy(readingDirection = ReadingDirection.LeftToRight) },
        "layoutMode" to { it.copy(layoutMode = LayoutMode.Single) },
        "coverAlone" to { it.copy(coverAlone = true) },
        "atBookEnd" to { it.copy(atBookEnd = BookEndAction.Stop) },
        "scaleMode" to { it.copy(scaleMode = ScaleMode.FitWidth) },
        "capAtOriginal" to { it.copy(capAtOriginal = true) },
        "trimStrength" to { it.copy(trimStrength = TrimStrength.Strong) },
        "gutterStyle" to { it.copy(gutterStyle = GutterStyle.Shadow) },
        "adjustLevel" to { it.copy(adjustLevel = 2) },
        "adjustSharpen" to { it.copy(adjustSharpen = true) },
        "adjustMono" to { it.copy(adjustMono = true) },
        "adjustNegative" to { it.copy(adjustNegative = true) },
        "turnEffect" to { it.copy(turnEffect = TurnEffect.Fade) },
        "turnDurationMs" to { it.copy(turnDurationMs = 300) },
        "stopOrder" to { it.copy(stopOrder = StopOrder.ColumnsFirst) },
        "stopMaxStepPercent" to { it.copy(stopMaxStepPercent = 50) },
        "loupeZoom" to { it.copy(loupeZoom = LoupeZoom.Source300) },
        "loupeFrame" to { it.copy(loupeFrame = LoupeFrame.Large) },
        "loupeHoldMs" to { it.copy(loupeHoldMs = 1600) },
        "tapLayout" to { it.copy(tapLayout = TapLayout.TopAndBottomBands) },
        "tapBandPercent" to { it.copy(tapBandPercent = 30) },
        "mirrorControls" to { it.copy(mirrorControls = true) },
        "tapAdvancesStops" to { it.copy(tapAdvancesStops = false) },
        "swipeToTurn" to { it.copy(swipeToTurn = false) },
        "volumeKeys" to { it.copy(volumeKeys = VolumeKeys.UpAdvances) },
        "turnHaptics" to { it.copy(turnHaptics = true) },
        "barHeightDp" to { it.copy(barHeightDp = 80) },
        "barDisplay" to { it.copy(barDisplay = BarDisplay.Always) },
        "commandBarItems" to { it.copy(commandBarItems = listOf(ReaderCommand.Settings)) },
        "autoPlaySeconds" to { it.copy(autoPlaySeconds = 2.5) },
        "readerOrientation" to { it.copy(readerOrientation = ReaderOrientation.Landscape) },
        "readerBrightness" to { it.copy(readerBrightness = 70) },
        "hideSystemBars" to { it.copy(hideSystemBars = false) },
        "keepAwake" to { it.copy(keepAwake = KeepAwake.TenMinutes) },
        "pageNumberSpot" to { it.copy(pageNumberSpot = ScreenSpot.BottomCenter) },
        "statusSpot" to { it.copy(statusSpot = ScreenSpot.BottomRight) },
        "overlayTextSize" to { it.copy(overlayTextSize = OverlayTextSize.Large) },
    )

    /** Global settings the image reader reads from the settings themselves, outside [ReaderConfig]. */
    private val readBesideConfig = setOf(
        "readerBackground",
        "historyLimit",
        "fallbackCharset",
        "showDotFiles",
        "breathControl",
        "breathSensitivity",
        "entryOrder",
    )

    /** Settings of the text reader, the lists, and the whole app. */
    private val otherScreens = setOf(
        "textTypeface", "textSizeSp", "textLineGap", "textLetterGap", "textMargin", "textLayout", "textLatinMode",
        "textPaper", "textInkOverride", "textPaperOverride",
        "libraryView", "thumbSize", "thumbnailParallelism", "gridColumnsPortrait", "gridColumnsLandscape", "gridNameLines", "gridNoteLine", "coverAspect",
        "libraryTextSize", "libraryGrouping", "librarySubgrouping", "librarySection", "mergeCircleAuthors", "librarySort", "foldersFirst", "showParentItem", "showSortStrip", "allowFileEdits", "reopenLastFolder",
        "reopenLastBook", "historySort", "bookmarkSort",
        "appTheme", "colorPalette", "pureBlack",
    )

    @Test
    fun theReaderStartsFromTheDefaultsOfTheGlobalSettings() {
        assertEquals(ReaderConfig.of(GlobalSettings()), ReaderConfig())
    }

    @Test
    fun everyGlobalSettingIsSortedIntoWhereTheReaderReadsIt() {
        val settings = GlobalSettings::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        val groups = listOf(readThroughConfig.keys, readBesideConfig, otherScreens)
        assertEquals(settings, groups.flatten().toSet(), "a setting is missing from the groups, or a group names one that is gone")
        assertEquals(groups.sumOf { it.size }, groups.flatten().toSet().size, "a setting is in two groups")
    }

    @Test
    fun eachSettingReadThroughTheConfigChangesTheConfig() {
        val defaults = GlobalSettings()
        for ((name, change) in readThroughConfig) {
            assertNotEquals(defaults, change(defaults).normalized(), "the change of $name is not a change")
            assertNotEquals(ReaderConfig.of(defaults), ReaderConfig.of(change(defaults)), "$name does not reach the reader")
        }
    }

    @Test
    fun capAtOriginalAndTheGutterComeFromTheSettings() {
        val config = ReaderConfig.of(GlobalSettings(capAtOriginal = true, gutterStyle = GutterStyle.None))
        assertEquals(true, config.capAtOriginal)
        assertEquals(GutterStyle.None, config.gutter)
        assertEquals(0f, config.gutterPx)
    }

    @Test
    fun tapsStepThroughStopsOnlyWhenTheSettingSaysSo() {
        assertFalse(ReaderConfig.of(GlobalSettings(tapAdvancesStops = false)).tapAdvancesStops)
        assertEquals(true, ReaderConfig.of(GlobalSettings(tapAdvancesStops = true)).tapAdvancesStops)
    }
}
