package io.github.yuubiz.hirari.feature.settings

import io.github.yuubiz.hirari.core.data.DecimalSteps
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.Rgb
import io.github.yuubiz.hirari.core.model.TapLayout
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTableTest {
    private val json = Json { encodeDefaults = true }

    private fun fields(settings: GlobalSettings): JsonObject =
        json.encodeToJsonElement(GlobalSettings.serializer(), settings) as JsonObject

    @Suppress("UNCHECKED_CAST")
    private fun candidates(setting: Setting, base: GlobalSettings): List<Any?> = when (setting) {
        is ToggleSetting -> listOf(false, true)
        is ChoiceSetting<*> -> setting.options.map { it.value }
        is SliderSetting<*> -> setting.values(base)
        is ColorSetting<*> -> (setting as ColorSetting<Any?>).let { color -> color.presets.map { it.value } + color.custom(Rgb(0x123456)) }
        is OrderedSetSetting -> listOf(
            emptyList<Any?>(),
            setting.choices.map { it.value }.take(setting.max),
            setting.choices.map { it.value }.take(3).reversed(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun write(setting: Setting, base: GlobalSettings, value: Any?): GlobalSettings = when (setting) {
        is ToggleSetting -> setting.write(base, value as Boolean)
        is ChoiceSetting<*> -> (setting as ChoiceSetting<Any?>).write(base, value)
        is SliderSetting<*> -> (setting as SliderSetting<Any?>).write(base, value)
        is ColorSetting<*> -> (setting as ColorSetting<Any?>).write(base, value)
        is OrderedSetSetting -> setting.write(base, value as List<ReaderCommand>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun read(setting: Setting, settings: GlobalSettings): Any? = when (setting) {
        is ToggleSetting -> setting.read(settings)
        is ChoiceSetting<*> -> setting.read(settings)
        is SliderSetting<*> -> setting.read(settings)
        is ColorSetting<*> -> setting.read(settings)
        is OrderedSetSetting -> setting.read(settings)
    }

    @Test
    fun `every field of GlobalSettings except file edits has a row`() {
        val fields = GlobalSettings.serializer().descriptor.elementNames.toSet()
        val keys = SettingsTable.map { it.key }
        assertEquals(keys.size, keys.toSet().size, "a key is listed twice")
        assertEquals(fields - "allowFileEdits", keys.toSet())
        assertEquals(70, keys.size)
    }

    @Test
    fun `the table lists the settings of docs spec 4-10 under the groups and in the order of the spec`() {
        val spec = specSettings()
        assertTrue(spec.size > 60, "the spec tables were not read: $spec")
        assertEquals(spec.map { it.second }, SettingsTable.map { it.key })
        for ((setting, listed) in SettingsTable.zip(spec)) {
            assertEquals(listed.first, setting.group.title, setting.key)
        }
    }

    @Test
    fun `file edits stay hidden while their stored value remains compatible`() {
        for (sdk in listOf(28, 30, 31, Int.MAX_VALUE)) {
            assertTrue(settingsFor(sdk).values.flatten().none { it.key == "allowFileEdits" })
        }
        assertEquals(false, GlobalSettings().allowFileEdits)
        assertEquals(false, json.decodeFromString(GlobalSettings.serializer(), "{}").allowFileEdits)
        for (allowed in listOf(false, true)) {
            val stored = json.decodeFromString(GlobalSettings.serializer(), """{"allowFileEdits":$allowed}""")
            assertEquals(allowed, stored.normalized().allowFileEdits)
            val encoded = json.encodeToString(GlobalSettings.serializer(), stored)
            assertTrue("\"allowFileEdits\":$allowed" in encoded)
            assertEquals(allowed, json.decodeFromString(GlobalSettings.serializer(), encoded).allowFileEdits)
        }
    }

    @Test
    fun `only turning on breath control asks for the microphone`() {
        assertEquals(listOf("breathControl"), SettingsTable.filterIsInstance<ToggleSetting>().filter { it.asksMicrophone }.map { it.key })
    }

    @Test
    fun `groups follow the order of the enum`() {
        assertEquals(SettingGroup.entries.toList(), settingsFor(Int.MAX_VALUE).keys.toList())
        assertEquals(SettingsTable.size, settingsFor(Int.MAX_VALUE).values.sumOf { it.size })
    }

    @Test
    fun `the default of every setting is something its row offers`() {
        val defaults = GlobalSettings()
        for (setting in SettingsTable) {
            val default = read(setting, defaults)
            when (setting) {
                is ColorSetting<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val color = setting as ColorSetting<Any?>
                    assertTrue(default in color.presets.map { it.value } || color.customOf(default) != null, setting.key)
                }
                is OrderedSetSetting -> assertTrue(setting.choices.map { it.value }.containsAll(defaults.commandBarItems), setting.key)
                else -> assertTrue(default in candidates(setting, defaults), "${setting.key} default $default is not offered")
            }
        }
    }

    @Test
    fun `each value a row offers is stored and read back, and changes no other setting`() {
        for (setting in SettingsTable) {
            val base = GlobalSettings()
            for (value in candidates(setting, base)) {
                val written = write(setting, base, value)
                assertEquals(value, read(setting, written), "${setting.key} = $value")
                val changed = fields(written).filter { (name, field) -> fields(base)[name] != field }.keys
                assertTrue(changed.all { it == setting.key }, "${setting.key} = $value also changed $changed")
            }
        }
    }

    @Test
    fun `each value a row offers survives the repository's normalization`() {
        for (layout in TapLayout.entries) {
            val base = GlobalSettings(tapLayout = layout)
            for (setting in SettingsTable) {
                for (value in candidates(setting, base)) {
                    val stored = write(setting, base, value).normalized()
                    assertEquals(value, read(setting, stored), "${setting.key} = $value under $layout")
                }
            }
        }
    }

    @Test
    fun `a row offers a value once, and every enum constant of a choice`() {
        for (setting in SettingsTable.filterIsInstance<ChoiceSetting<*>>()) {
            val labels = setting.options.map { it.label }
            assertEquals(labels.size, labels.toSet().size, "${setting.key} repeats a label")
            val values = setting.options.map { it.value }
            assertEquals(values.size, values.toSet().size, "${setting.key} repeats a value")
            val constants = (values.firstOrNull() as? Enum<*>)?.declaringJavaClass?.enumConstants?.toSet()
            if (constants != null) assertEquals(constants, values.toSet(), "${setting.key} leaves a constant out")
        }
        for (setting in SettingsTable.filterIsInstance<ColorSetting<*>>()) {
            val labels = setting.presets.map { it.label }
            assertEquals(labels.size, labels.toSet().size, "${setting.key} repeats a label")
        }
    }

    @Test
    fun `the tap band slider stops at 40 percent when both sides have bands`() {
        val band = SettingsTable.filterIsInstance<SliderSetting<*>>().single { it.key == "tapBandPercent" }
        assertEquals((10..90 step 10).toList(), band.values(GlobalSettings(tapLayout = TapLayout.OneSideBand)))
        assertEquals((10..40 step 10).toList(), band.values(GlobalSettings(tapLayout = TapLayout.BothSideBands)))
        assertEquals((10..40 step 10).toList(), band.values(GlobalSettings(tapLayout = TapLayout.TopAndBottomBands)))
    }

    @Test
    fun `the wallpaper color row is for Android 12 and later`() {
        fun keys(sdk: Int) = settingsFor(sdk).values.flatten().map { it.key }
        assertTrue("dynamicColor" !in keys(30))
        assertTrue("dynamicColor" in keys(31))
    }

    @Test
    fun `decimal values are listed without float noise`() {
        assertEquals(listOf(0.5, 1.0, 1.5, 2.0), DecimalSteps(0.5, 2.0, 0.5).toValues())
        val gaps = GlobalSettings.TEXT_LETTER_GAP.toValues()
        assertEquals(51, gaps.size)
        assertEquals(0.15, gaps[3])
        assertEquals(2.5, gaps.last())
        assertEquals("1.5", decimalText(1.5))
        assertEquals("0.05", decimalText(0.05))
        assertEquals("2", decimalText(2.0))
        assertEquals("0", decimalText(0.0))
    }

    @Test
    fun `colors are typed as hex with or without the hash`() {
        assertEquals(Rgb(0x616161), parseHex("#616161"))
        assertEquals(Rgb(0xABCDEF), parseHex(" abcdef "))
        assertEquals(Rgb(0), parseHex("#000000"))
        for (bad in listOf("", "#", "#12345", "#1234567", "#12345G", "12 456", "#-12345", "+12345")) assertNull(parseHex(bad), bad)
        assertEquals("#00FF7F", hexOf(Rgb(0x00FF7F)))
        assertNotNull(parseHex(hexOf(Rgb(0x0A0B0C))))
    }

    @Test
    fun `the default of each switch and choice is the default the spec gives`() {
        val defaults = GlobalSettings()
        val spec = specDefaults()
        val checked = mutableListOf<String>()
        for (setting in SettingsTable) {
            val written = spec.getValue(setting.key)
            val shown = when (setting) {
                is ToggleSetting -> if (setting.read(defaults)) "オン" else "オフ"
                is ChoiceSetting<*> -> setting.options.single { it.value == setting.read(defaults) }.label
                else -> continue
            }
            if (shown in written || written in shown) checked += setting.key else assertEquals(written, shown, setting.key)
        }
        assertTrue(listOf("layoutMode", "coverAlone", "scaleMode", "tapAdvancesStops").all { it in checked }, "checked $checked")
    }

    /** The default column of the tables of 4.10, by internal name. */
    private fun specDefaults(): Map<String, String> {
        val spec = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "docs/spec.md") }.first { it.isFile }
        val lines = spec.readLines()
        val section = lines.subList(lines.indexOfFirst { it.startsWith("### 4.10 ") }, lines.indexOfFirst { it.startsWith("### 4.11 ") })
        return section.mapNotNull { Regex("^\\| [^|]+ \\| `([A-Za-z]+)` \\| [^|]+ \\| ([^|]+) \\|").find(it)?.destructured }
            .associate { (name, default) -> name to default.trim() }
    }

    /** The internal names of the tables of 4.10, each with the heading of its table, in the order of the spec. */
    private fun specSettings(): List<Pair<String, String>> {
        val spec = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "docs/spec.md") }.first { it.isFile }
        val lines = spec.readLines()
        val from = lines.indexOfFirst { it.startsWith("### 4.10 ") }
        val to = lines.indexOfFirst { it.startsWith("### 4.11 ") }
        var group = ""
        val found = mutableListOf<Pair<String, String>>()
        for (line in lines.subList(from, to)) {
            if (line.startsWith("#### ")) group = line.removePrefix("#### ").replace(Regex("（M\\d）"), "")
            val name = Regex("^\\| [^|]+ \\| `([A-Za-z]+)` \\|").find(line)?.groupValues?.get(1)
            if (name != null && "まだ表示しない" !in line) found += group to name
        }
        return found
    }
}
