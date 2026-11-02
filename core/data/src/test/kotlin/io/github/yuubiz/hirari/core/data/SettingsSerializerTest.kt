package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.VolumeKeys
import androidx.datastore.core.CorruptionException
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.StopOrder
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.Rgb
import io.github.yuubiz.hirari.core.model.TextPaper
import io.github.yuubiz.hirari.core.model.TextTypeface
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsSerializerTest {
    private val changed = GlobalSettings(
        readingDirection = ReadingDirection.LeftToRight,
        layoutMode = LayoutMode.Spread,
        coverAlone = false,
        scaleMode = ScaleMode.FitWidth,
        turnEffect = TurnEffect.Fade,
        turnDurationMs = 300,
        stopOrder = StopOrder.ColumnsFirst,
        stopMaxStepPercent = 50,
        tapLayout = TapLayout.TopAndBottomBands,
        tapBandPercent = 30,
        mirrorControls = true,
        swipeToTurn = false,
        volumeKeys = VolumeKeys.UpAdvances,
        barHeightDp = 80,
    )

    private suspend fun read(text: String) = SettingsSerializer.readFrom(ByteArrayInputStream(text.encodeToByteArray()))

    private suspend fun roundTrip(settings: GlobalSettings): GlobalSettings {
        val out = ByteArrayOutputStream()
        SettingsSerializer.writeTo(settings, out)
        return read(out.toByteArray().decodeToString())
    }

    @Test
    fun `settings read back unchanged`() = runTest {
        assertEquals(GlobalSettings(), roundTrip(GlobalSettings()))
        assertEquals(changed, roundTrip(changed))
    }

    @Test
    fun `a file written by an earlier version keeps its meaning`() = runTest {
        val stored = """
            {"readingDirection":"LeftToRight","layoutMode":"Spread","coverAlone":false,"scaleMode":"FitWidth",
            "turnEffect":"Fade","turnDurationMs":300,"stopOrder":"ColumnsFirst","stopMaxStepPercent":50,
            "tapLayout":"TopAndBottomBands","tapBandPercent":30,"mirrorControls":true,"swipeToTurn":false,
            "volumeKeys":"UpAdvances","barHeightDp":80}
        """.trimIndent()
        assertEquals(changed, read(stored))
    }

    @Test
    fun `two tap bands stay at or under 45 percent each`() {
        val wide = GlobalSettings(tapLayout = TapLayout.BothSideBands, tapBandPercent = 90).normalized()
        assertEquals(40, wide.tapBandPercent)
        assertEquals(90, GlobalSettings(tapBandPercent = 90).normalized().tapBandPercent)
    }

    @Test
    fun `missing settings take their defaults`() = runTest {
        assertEquals(GlobalSettings(), read("{}"))
        assertEquals(GlobalSettings(coverAlone = false), read("""{"coverAlone":false}"""))
    }

    @Test
    fun `unknown keys are ignored and unknown values fall back to the default`() = runTest {
        val stored = """{"scaleMode":"Zoom","futureSetting":3,"coverAlone":false}"""
        assertEquals(GlobalSettings(coverAlone = false), read(stored))
    }

    @Test
    fun `numbers outside their range move to the nearest allowed value`() = runTest {
        val stored = """{"tapBandPercent":95,"turnDurationMs":73,"barHeightDp":2,"stopMaxStepPercent":101}"""
        assertEquals(
            GlobalSettings(tapBandPercent = 90, turnDurationMs = 50, barHeightDp = 40, stopMaxStepPercent = 100),
            read(stored),
        )
    }

    @Test
    fun `an unreadable file is reported as corrupt`() = runTest {
        assertFailsWith<CorruptionException> { read("not json") }
        assertFailsWith<CorruptionException> { read("[1, 2]") }
    }

    @Test
    fun `a value of the wrong type falls back to its default and spares the other keys`() = runTest {
        val stored = """{"coverAlone":"yes","tapBandPercent":30,"commandBarItems":7,"readerBrightness":"dim","textPaper":"Black"}"""
        assertEquals(GlobalSettings(tapBandPercent = 30, textPaper = TextPaper.Black), read(stored))
    }

    @Test
    fun `the settings are the 63 of the spec and the fixture changes each of them`() {
        val defaults = SettingsJson.encodeToJsonElement(GlobalSettings.serializer(), GlobalSettings()).jsonObject
        val changed = SettingsJson.encodeToJsonElement(GlobalSettings.serializer(), everything).jsonObject
        assertEquals(63, defaults.size)
        assertEquals(defaults.keys, defaults.keys.filter { defaults[it] != changed[it] }.toSet())
    }

    @Test
    fun `every setting reads back unchanged`() = runTest {
        assertEquals(everything, roundTrip(everything))
    }

    @Test
    fun `colors and typefaces are stored as strings`() = runTest {
        val stored = """{"readerBackground":"#00ff80","textTypeface":"Gothic","textInkOverride":"#ABCDEF","textPaperOverride":null}"""
        assertEquals(
            GlobalSettings(
                readerBackground = ReaderBackground.Custom(Rgb(0x00FF80)),
                textTypeface = TextTypeface.Gothic,
                textInkOverride = Rgb(0xABCDEF),
            ),
            read(stored),
        )
        assertEquals(GlobalSettings(), read("""{"readerBackground":"#12345","textTypeface":"font:","textInkOverride":"red"}"""))
        assertEquals(GlobalSettings(readerBackground = ReaderBackground.White), read("""{"readerBackground":"White"}"""))
    }

    @Test
    fun `decimals move to the nearest step and print as that decimal`() = runTest {
        val stored = """{"autoPlaySeconds":0.1,"textLineGap":0.34,"textLetterGap":0.33,"readerBrightness":104,"textSizeSp":100}"""
        val settings = read(stored)
        assertEquals(0.5, settings.autoPlaySeconds)
        assertEquals(0.3, settings.textLineGap)
        assertEquals(0.35, settings.textLetterGap)
        assertEquals(100, settings.readerBrightness)
        assertEquals(62, settings.textSizeSp)
        assertEquals(30.0, GlobalSettings(autoPlaySeconds = Double.POSITIVE_INFINITY).normalized().autoPlaySeconds)
        assertEquals(0.5, GlobalSettings(autoPlaySeconds = Double.NaN).normalized().autoPlaySeconds)
    }

    @Test
    fun `the history limit moves to the nearest choice`() = runTest {
        assertEquals(50, read("""{"historyLimit":60}""").historyLimit)
        assertEquals(0, read("""{"historyLimit":-4}""").historyLimit)
        assertEquals(500, read("""{"historyLimit":100000}""").historyLimit)
    }

    @Test
    fun `command bar items skip unknown names, repeats and anything past eight`() = runTest {
        assertEquals(
            listOf(ReaderCommand.Trim, ReaderCommand.Layout),
            read("""{"commandBarItems":["Trim","Teleport","Trim",3,"Layout"]}""").commandBarItems,
        )
        val many = ReaderCommand.entries.joinToString(",") { "\"${it.name}\"" }
        assertEquals(ReaderCommand.entries.take(8), read("""{"commandBarItems":[$many]}""").commandBarItems)
        assertEquals(emptyList(), read("""{"commandBarItems":[]}""").commandBarItems)
    }
}
