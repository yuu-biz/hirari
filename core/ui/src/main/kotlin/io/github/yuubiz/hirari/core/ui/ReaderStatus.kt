package io.github.yuubiz.hirari.core.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.yuubiz.hirari.core.model.OverlayTextSize
import java.util.Date

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
fun batteryOf(intent: Intent): Battery? {
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
val OverlayTextSize.sp: Int
    get() = when (this) {
        OverlayTextSize.Small -> 12
        OverlayTextSize.Medium -> 16
        OverlayTextSize.Large -> 20
    }

/** 時刻と電池, updated as the minute, the clock, or the battery changes. */
@Composable
fun rememberStatusText(): String {
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
