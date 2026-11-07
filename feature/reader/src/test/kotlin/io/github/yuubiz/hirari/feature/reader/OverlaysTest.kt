package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.ui.Battery
import io.github.yuubiz.hirari.core.ui.PowerSource
import io.github.yuubiz.hirari.core.ui.batteryOf
import io.github.yuubiz.hirari.core.ui.statusText

import android.content.Intent
import android.os.BatteryManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.HalfPart
import io.github.yuubiz.hirari.core.model.OverlayTextSize
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ScreenSpot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 重ねて表示する情報 of docs/spec.md section 4.8, placed by the settings of section 4.10. */
@RunWith(RobolectricTestRunner::class)
class OverlaysTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the page number counts from one and names both pages of a pair`() {
        assertEquals("12 / 200", pageNumberText(PageView.One(11), 200))
        assertEquals("12-13 / 200", pageNumberText(PageView.Two(11, 12), 200))
        assertEquals("12 / 200", pageNumberText(PageView.Half(11, HalfPart.entries.last()), 200))
    }

    @Test
    fun `time and battery show the charge and what charges it`() {
        assertEquals("21:05 [80%]", statusText("21:05", Battery(80, charging = null)))
        assertEquals("21:05 [80%] [USB]", statusText("21:05", Battery(80, PowerSource.Usb)))
        assertEquals("21:05 [80%] [AC]", statusText("21:05", Battery(80, PowerSource.Mains)))
        assertEquals("21:05", statusText("21:05", battery = null))
    }

    @Test
    fun `the battery broadcast gives the percent, and every charger but USB counts as AC`() {
        fun battery(level: Int, scale: Int, plugged: Int) = batteryOf(
            Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, level)
                .putExtra(BatteryManager.EXTRA_SCALE, scale)
                .putExtra(BatteryManager.EXTRA_PLUGGED, plugged),
        )
        assertEquals(Battery(80, null), battery(160, 200, 0))
        assertEquals(Battery(55, PowerSource.Usb), battery(55, 100, BatteryManager.BATTERY_PLUGGED_USB))
        assertEquals(Battery(55, PowerSource.Mains), battery(55, 100, BatteryManager.BATTERY_PLUGGED_AC))
        assertEquals(Battery(55, PowerSource.Mains), battery(55, 100, BatteryManager.BATTERY_PLUGGED_WIRELESS))
        assertNull(battery(-1, 100, 0))
    }

    @Test
    fun `the page number shows at its spot only when a spot is chosen`() {
        var overlays by mutableStateOf(Overlays())
        rule.setContent { Box(Modifier.size(400.dp, 800.dp)) { ReaderOverlays(overlays, "12 / 200") } }
        rule.onAllNodesWithText("12 / 200").assertCountEquals(0)

        overlays = Overlays(pageNumber = ScreenSpot.BottomCenter, textSize = OverlayTextSize.Large)
        val root = rule.onRoot().getBoundsInRoot()
        val shown = rule.onNodeWithText("12 / 200").getBoundsInRoot()
        assertTrue(shown.bottom > root.bottom - 60.dp, "at the bottom: $shown in $root")
        assertTrue(shown.left > root.left + 100.dp && shown.right < root.right - 100.dp, "in the middle: $shown in $root")

        overlays = Overlays(pageNumber = ScreenSpot.TopLeft)
        val moved = rule.onNodeWithText("12 / 200").getBoundsInRoot()
        assertTrue(moved.top < root.top + 60.dp && moved.left < root.left + 60.dp, "at the top left: $moved in $root")
    }
}
