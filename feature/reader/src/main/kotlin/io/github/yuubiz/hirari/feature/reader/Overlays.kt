package io.github.yuubiz.hirari.feature.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.yuubiz.hirari.core.model.OverlayTextSize
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ScreenSpot
import java.util.Date

/** 重ねて表示する情報 that the settings place (docs/spec.md sections 4.8 and 4.10): ページ番号 and 時刻と電池. */
data class Overlays(
    val pageNumber: ScreenSpot = ScreenSpot.Off,
    val status: ScreenSpot = ScreenSpot.Off,
    val textSize: OverlayTextSize = OverlayTextSize.Medium,
)

/** `12 / 200`, or `12-13 / 200` for a view of two pages. */
fun pageNumberText(view: PageView, pageCount: Int): String {
    val numbers = view.pages.map { it + 1 }.sorted().distinct().joinToString("-")
    return "$numbers / $pageCount"
}

/** What charges the device, as the battery reports it (`BatteryManager.EXTRA_PLUGGED`). */
enum class PowerSource { Usb, Mains }

/** The battery as 時刻と電池 shows it: its charge in percent, and what charges it, `null` when nothing does. */
data class Battery(val percent: Int, val charging: PowerSource?)

/** `21:05 [80%]`, with `[USB]` or `[AC]` after it while charging. [time] is already in the device's 12 or 24 hour form. */
fun statusText(time: String, battery: Battery?): String = buildString {
    append(time)
    if (battery == null) return@buildString
    append(" [${battery.percent}%]")
    when (battery.charging) {
        PowerSource.Usb -> append(" [USB]")
        PowerSource.Mains -> append(" [AC]")
        null -> Unit
    }
}

/** The battery in a sticky `ACTION_BATTERY_CHANGED`, or `null` when it reports no charge, as a device without one. */
internal fun batteryOf(intent: Intent): Battery? {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return null
    val charging = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        0 -> null
        BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.Usb
        else -> PowerSource.Mains
    }
    return Battery(level * 100 / scale, charging)
}

/** The sizes of 重ねる文字の大きさ, in sp. */
internal val OverlayTextSize.sp: Int
    get() = when (this) {
        OverlayTextSize.Small -> 12
        OverlayTextSize.Medium -> 16
        OverlayTextSize.Large -> 20
    }

private val ScreenSpot.alignment: Alignment?
    get() = when (this) {
        ScreenSpot.Off -> null
        ScreenSpot.TopLeft -> Alignment.TopStart
        ScreenSpot.TopCenter -> Alignment.TopCenter
        ScreenSpot.TopRight -> Alignment.TopEnd
        ScreenSpot.BottomLeft -> Alignment.BottomStart
        ScreenSpot.BottomCenter -> Alignment.BottomCenter
        ScreenSpot.BottomRight -> Alignment.BottomEnd
    }

/**
 * ページ番号 and 時刻と電池 at the spots [overlays] gives, inside the system bars and the display cutout. Two in one spot
 * stack, the page number first.
 */
@Composable
internal fun ReaderOverlays(overlays: Overlays, pageNumber: String) {
    if (overlays.pageNumber == ScreenSpot.Off && overlays.status == ScreenSpot.Off) return
    val status = if (overlays.status == ScreenSpot.Off) null else rememberStatusText()
    val texts = listOfNotNull(overlays.pageNumber.alignment?.let { it to pageNumber }, status?.let { text -> overlays.status.alignment?.let { it to text } })
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
        for ((alignment, shown) in texts.groupBy({ it.first }, { it.second })) {
            Column(Modifier.align(alignment), verticalArrangement = Arrangement.spacedBy(2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                for (text in shown) OutlinedText(text, Modifier, overlays.textSize.sp)
            }
        }
    }
}

/** 時刻と電池, updated as the minute, the clock, or the battery changes. */
@Composable
private fun rememberStatusText(): String {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    DisposableEffect(context) {
        var battery: Battery? = null
        fun show() {
            text = statusText(DateFormat.getTimeFormat(context).format(Date()), battery)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) battery = batteryOf(intent)
                show()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)?.let { battery = batteryOf(it) }
        show()
        onDispose { context.unregisterReceiver(receiver) }
    }
    return text
}
