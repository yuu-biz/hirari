package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.ColorPalette
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.Rgb
import io.github.yuubiz.hirari.core.model.TextTypeface
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The JSON of the stored settings and of the settings file (docs/spec.md sections 4.11 and 7). */
internal val SettingsJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * Decodes [fields] one key at a time, so a value of the wrong type falls back to its default without taking
 * the other keys with it. Unknown keys and unknown enum constants are ignored by [SettingsJson] already.
 */
internal fun <T> Json.decodeFields(serializer: KSerializer<T>, fields: JsonObject): T {
    val readable = fields.filter { (name, value) ->
        try {
            decodeFromJsonElement(serializer, JsonObject(mapOf(name to value)))
            true
        } catch (_: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException, and so is a failed `require` in a model type.
            false
        }
    }
    return decodeFromJsonElement(serializer, JsonObject(readable))
}

internal abstract class StringFormSerializer<T : Any>(name: String) : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    abstract fun format(value: T): String

    /** `null` for a string that names no value. */
    abstract fun parse(text: String): T?

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(format(value))

    override fun deserialize(decoder: Decoder): T {
        val text = decoder.decodeString()
        return parse(text) ?: throw SerializationException("unknown ${descriptor.serialName} '$text'")
    }
}

/** `#RRGGBB`. */
internal object RgbSerializer : StringFormSerializer<Rgb>("io.github.yuubiz.hirari.Rgb") {
    override fun format(value: Rgb) = "#%06X".format(value.value)

    override fun parse(text: String): Rgb? =
        if (text.length == 7 && text[0] == '#') text.substring(1).toIntOrNull(16)?.takeIf { it >= 0 }?.let(::Rgb) else null
}

/** The constant name, or `#RRGGBB` for a chosen color. */
internal object ReaderBackgroundSerializer :
    StringFormSerializer<ReaderBackground>("io.github.yuubiz.hirari.ReaderBackground") {
    private val named = listOf(ReaderBackground.Black, ReaderBackground.DarkGray, ReaderBackground.White, ReaderBackground.Theme)
        .associateBy { it.toString() }

    override fun format(value: ReaderBackground) = when (value) {
        is ReaderBackground.Custom -> RgbSerializer.format(value.color)
        else -> value.toString()
    }

    override fun parse(text: String): ReaderBackground? =
        named[text] ?: RgbSerializer.parse(text)?.let(ReaderBackground::Custom)
}

/** `Mincho`, `Gothic`, or `font:` and the file name of an imported font. */
internal object TextTypefaceSerializer : StringFormSerializer<TextTypeface>("io.github.yuubiz.hirari.TextTypeface") {
    private const val IMPORTED = "font:"

    override fun format(value: TextTypeface) = when (value) {
        TextTypeface.Mincho -> "Mincho"
        TextTypeface.Gothic -> "Gothic"
        is TextTypeface.Imported -> IMPORTED + value.fileName
    }

    override fun parse(text: String): TextTypeface? = when {
        text == "Mincho" -> TextTypeface.Mincho
        text == "Gothic" -> TextTypeface.Gothic
        text.startsWith(IMPORTED) && text.length > IMPORTED.length -> TextTypeface.Imported(text.substring(IMPORTED.length))
        else -> null
    }
}

/** Skips the names of commands this version does not know, so a file from a newer version keeps the rest. */
internal object CommandBarItemsSerializer : KSerializer<List<ReaderCommand>> {
    private val names = ListSerializer(String.serializer())
    private val byName = ReaderCommand.entries.associateBy { it.name }

    override val descriptor: SerialDescriptor = names.descriptor

    override fun serialize(encoder: Encoder, value: List<ReaderCommand>) =
        names.serialize(encoder, value.map { it.name })

    override fun deserialize(decoder: Decoder): List<ReaderCommand> {
        if (decoder !is JsonDecoder) return names.deserialize(decoder).mapNotNull(byName::get)
        val array = decoder.decodeJsonElement() as? JsonArray ?: throw SerializationException("commandBarItems is not a list")
        return array.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.contentOrNull?.let(byName::get) }
    }
}

/** The preset name, or a seed in #RRGGBB form. */
internal object ColorPaletteSerializer : StringFormSerializer<ColorPalette>("io.github.yuubiz.hirari.ColorPalette") {
    private val named = ColorPalette.presets.associateBy { it.toString() }
    private val rgb = Regex("#[0-9A-Fa-f]{6}")
    override fun format(value: ColorPalette): String = when (value) {
        is ColorPalette.Custom -> RgbSerializer.format(value.color)
        else -> value.toString()
    }
    override fun parse(text: String): ColorPalette? =
        named[text] ?: text.takeIf(rgb::matches)?.let(RgbSerializer::parse)?.let(ColorPalette::Custom)
}
