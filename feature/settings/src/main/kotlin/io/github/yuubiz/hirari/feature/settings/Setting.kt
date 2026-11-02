package io.github.yuubiz.hirari.feature.settings

import io.github.yuubiz.hirari.core.data.DecimalSteps
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.Rgb
import java.math.BigDecimal
import kotlin.math.roundToInt

/** The groups of docs/spec.md section 4.10, in the order the screen shows them. */
enum class SettingGroup(val title: String) {
    Reading("読み方"),
    ImageDisplay("画像の表示"),
    Controls("操作"),
    Screen("画面"),
    Text("テキスト"),
    Library("ライブラリ"),
    Appearance("外観と文字コード"),
}

data class Option<T>(val value: T, val label: String)

/**
 * One row of the settings screen. [key] is the internal name of docs/spec.md section 4.10, which is also the name
 * of the [GlobalSettings] property. Each subclass says how its value is read from and written to [GlobalSettings],
 * and the screen draws a row from that alone.
 */
sealed class Setting(
    val key: String,
    val group: SettingGroup,
    val label: String,
    /** A line under the label that explains the limits of the value. */
    val note: String?,
    /** The row is left out below this API level. */
    val minSdk: Int,
)

class ToggleSetting(
    key: String,
    group: SettingGroup,
    label: String,
    note: String? = null,
    minSdk: Int = 0,
    val read: (GlobalSettings) -> Boolean,
    val write: (GlobalSettings, Boolean) -> GlobalSettings,
) : Setting(key, group, label, note, minSdk)

/** One of a few listed values. [describe] names the current value, including one that [options] does not offer. */
class ChoiceSetting<T>(
    key: String,
    group: SettingGroup,
    label: String,
    note: String? = null,
    minSdk: Int = 0,
    val options: List<Option<T>>,
    val read: (GlobalSettings) -> T,
    val write: (GlobalSettings, T) -> GlobalSettings,
    val describe: (T) -> String = { value -> options.firstOrNull { it.value == value }?.label ?: value.toString() },
) : Setting(key, group, label, note, minSdk) {
    /** This setting with [more] after its own options. */
    fun plusOptions(more: List<Option<T>>) = ChoiceSetting(key, group, label, note, minSdk, options + more, read, write, describe)
}

/** A number that moves along [values]. The values depend on the settings, as the tap band does on the tap layout. */
class SliderSetting<T>(
    key: String,
    group: SettingGroup,
    label: String,
    note: String? = null,
    minSdk: Int = 0,
    val values: (GlobalSettings) -> List<T>,
    val format: (T) -> String,
    val read: (GlobalSettings) -> T,
    val write: (GlobalSettings, T) -> GlobalSettings,
) : Setting(key, group, label, note, minSdk)

/** A color that is one of [presets] or a free `#RRGGBB`. */
class ColorSetting<T>(
    key: String,
    group: SettingGroup,
    label: String,
    note: String? = null,
    minSdk: Int = 0,
    val presets: List<Option<T>>,
    val read: (GlobalSettings) -> T,
    val write: (GlobalSettings, T) -> GlobalSettings,
    /** The free color inside [value], or `null` for a preset. */
    val customOf: (T) -> Rgb?,
    val custom: (Rgb) -> T,
) : Setting(key, group, label, note, minSdk) {
    fun describe(value: T): String =
        presets.firstOrNull { it.value == value }?.label ?: customOf(value)?.let(::hexOf) ?: value.toString()
}

/** An ordered choice of at most [max] distinct items out of [choices]. */
class OrderedSetSetting(
    key: String,
    group: SettingGroup,
    label: String,
    note: String? = null,
    minSdk: Int = 0,
    val choices: List<Option<ReaderCommand>>,
    val max: Int,
    val read: (GlobalSettings) -> List<ReaderCommand>,
    val write: (GlobalSettings, List<ReaderCommand>) -> GlobalSettings,
) : Setting(key, group, label, note, minSdk) {
    fun labelOf(command: ReaderCommand): String = choices.firstOrNull { it.value == command }?.label ?: command.name
}

/** `#RRGGBB`. */
fun hexOf(color: Rgb): String = "#%06X".format(color.value)

/** Reads `#RRGGBB` or `RRGGBB` in either case, as typed by hand. `null` for anything else. */
fun parseHex(text: String): Rgb? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6 || digits.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return Rgb(digits.toInt(16))
}

/** Every value [DecimalSteps.snap] allows, from the smallest. */
internal fun DecimalSteps.toValues(): List<Double> {
    val count = ((max - min) / step).roundToInt()
    return (0..count).map { snap(min + it * step) }
}

/** `1.5`, `0.05` and `2`: no trailing zeros, whatever the locale. */
internal fun decimalText(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
