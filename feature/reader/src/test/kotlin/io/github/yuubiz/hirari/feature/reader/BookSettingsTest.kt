package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.LayoutSettings
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.TrimStrength
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookSettingsTest {
    private val settings = GlobalSettings()
    private val global = ReaderConfig.of(settings)
    private val overrides = BookOverrides(
        direction = ReadingDirection.LeftToRight,
        layoutMode = LayoutMode.Single,
        coverAlone = false,
        scaleMode = ScaleMode.FitWidth,
        trim = TrimStrength.Strong,
        rotation = Rotation.Quarter,
        adjust = ImageAdjust(level = 2, sharpen = true),
        nameCharset = Charset.forName("UTF-8"),
    )
    private val book = ReaderConfig.of(settings, overrides)

    @Test
    fun theCommandOpensTheSheetOnTheValuesItStartsFrom() {
        assertEquals(CommandEffect.Show(ReaderSheet.BookSettings(book)), book.effectOf(ReaderCommand.BookSettings))
    }

    @Test
    fun backToTheGlobalSettingsStoresNoOverrideOfTheSheetsValuesAndKeepsTheOthers() {
        val session = book.copy(orientation = ReaderOrientation.Landscape)
        val back = session.withBookValues(global)
        assertEquals(ReaderOrientation.Landscape, back.orientation)
        assertEquals(BookOverrides(nameCharset = overrides.nameCharset), overrides.changedBy(session, back, global))
    }

    @Test
    fun undoGoesBackToTheValuesTheSheetOpenedOn() {
        val changed = book.copy(scaleMode = ScaleMode.Original, rotation = Rotation.None)
        val undone = changed.withBookValues(book)
        assertEquals(book, undone)
        assertEquals(overrides, overrides.changedBy(book, changed, global).changedBy(changed, undone, global))
    }

    @Test
    fun makingTheBooksValuesTheDefaultMovesThemIntoTheGlobalSettingsButTheRotation() {
        val adopted = settings.adopting(book)
        assertEquals(ReadingDirection.LeftToRight, adopted.readingDirection)
        assertEquals(LayoutMode.Single, adopted.layoutMode)
        assertEquals(false, adopted.coverAlone)
        assertEquals(ScaleMode.FitWidth, adopted.scaleMode)
        assertEquals(TrimStrength.Strong, adopted.trimStrength)
        assertEquals(ImageAdjust(level = 2, sharpen = true), adopted.adjust)
        val left = overrides.adopted()
        assertEquals(BookOverrides(rotation = Rotation.Quarter, nameCharset = overrides.nameCharset), left)
        assertEquals(book, ReaderConfig.of(adopted, left))
    }

    @Test
    fun theBrightnessFollowsTheDeviceUnlessSetAndNeverGoesBlack() {
        assertNull(windowBrightness(null))
        assertEquals(0.5f, windowBrightness(50))
        assertEquals(0.01f, windowBrightness(0))
        assertEquals(1f, windowBrightness(100))
        assertEquals(30, ReaderConfig.of(settings.copy(readerBrightness = 30)).brightness)
    }

    @Test
    fun theStripTakesNoCoverAloneScaleModeOrTrimSoTheSheetOffersThemOnlyForTheOtherLayouts() {
        for (mode in LayoutMode.entries) {
            val config = ReaderConfig(layout = LayoutSettings(mode, ReadingDirection.RightToLeft, coverAlone = true))
            assertEquals(mode != LayoutMode.Vertical, config.pageFitApplies, "$mode")
        }
    }
}
