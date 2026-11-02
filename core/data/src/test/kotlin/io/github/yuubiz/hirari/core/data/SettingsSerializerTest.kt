package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.TurnEffect
import io.github.yuubiz.hirari.core.model.VolumeKeys
import androidx.datastore.core.CorruptionException
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.StopOrder
import kotlinx.coroutines.test.runTest
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
        assertFailsWith<CorruptionException> { read("""{"coverAlone":"yes"}""") }
    }
}
