package io.github.yuubiz.hirari.core.data

import io.github.yuubiz.hirari.core.model.BookOverrides
import io.github.yuubiz.hirari.core.model.ImageAdjust
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.TrimStrength
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.charset.Charset

/**
 * The stored and exported form of [BookOverrides] (docs/spec.md section 2.9). The keys are the property names
 * of [BookOverrides], `null` or a missing key follows the global setting, and the adjustment keys use the
 * names and ranges of the global `adjust*` settings.
 */
@Serializable
internal data class BookOverridesJson(
    val direction: ReadingDirection? = null,
    val layoutMode: LayoutMode? = null,
    val coverAlone: Boolean? = null,
    val scaleMode: ScaleMode? = null,
    val trim: TrimStrength? = null,
    val rotation: Rotation? = null,
    val reverseOrder: Boolean? = null,
    /** A Java charset name; one this device does not know follows the global setting. */
    val nameCharset: String? = null,
    val adjustLevel: Int? = null,
    val adjustSharpen: Boolean? = null,
    val adjustMono: Boolean? = null,
    val adjustNegative: Boolean? = null,
) {
    fun toModel(): BookOverrides = BookOverrides(
        direction = direction,
        layoutMode = layoutMode,
        coverAlone = coverAlone,
        scaleMode = scaleMode,
        trim = trim,
        rotation = rotation,
        reverseOrder = reverseOrder,
        nameCharset = nameCharset?.let { runCatching { Charset.forName(it) }.getOrNull() },
        adjust = if (listOf(adjustLevel, adjustSharpen, adjustMono, adjustNegative).all { it == null }) {
            null
        } else {
            ImageAdjust(
                level = (adjustLevel ?: 0).coerceIn(GlobalSettings.ADJUST_LEVEL),
                sharpen = adjustSharpen ?: false,
                mono = adjustMono ?: false,
                negative = adjustNegative ?: false,
            )
        },
    )

    companion object {
        fun of(overrides: BookOverrides) = BookOverridesJson(
            direction = overrides.direction,
            layoutMode = overrides.layoutMode,
            coverAlone = overrides.coverAlone,
            scaleMode = overrides.scaleMode,
            trim = overrides.trim,
            rotation = overrides.rotation,
            reverseOrder = overrides.reverseOrder,
            nameCharset = overrides.nameCharset?.name(),
            adjustLevel = overrides.adjust?.level,
            adjustSharpen = overrides.adjust?.sharpen,
            adjustMono = overrides.adjust?.mono,
            adjustNegative = overrides.adjust?.negative,
        )
    }
}

internal fun BookOverrides.toJson(): JsonObject =
    SettingsJson.encodeToJsonElement(BookOverridesJson.serializer(), BookOverridesJson.of(this)).jsonObject

internal fun JsonObject.toBookOverrides(): BookOverrides =
    SettingsJson.decodeFields(BookOverridesJson.serializer(), this).toModel()

internal fun String.toBookOverrides(): BookOverrides =
    (runCatching { SettingsJson.parseToJsonElement(this) }.getOrNull() as? JsonObject)?.toBookOverrides() ?: BookOverrides()
