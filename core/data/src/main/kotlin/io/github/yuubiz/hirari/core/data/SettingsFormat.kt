package io.github.yuubiz.hirari.core.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The version of the stored settings and of the settings file (docs/spec.md sections 4.11 and 7). A newer file
 * still reads; keys this version does not know are ignored.
 */
internal const val SETTINGS_FORMAT = 2

/** A default that a format changed: a setting stored as [before] was most likely never chosen, and reads as [after]. */
private class ChangedDefault(val key: String, val before: JsonPrimitive, val after: JsonPrimitive)

/** The defaults each format changed, by the format that changed them. */
private val changedDefaults: Map<Int, List<ChangedDefault>> = mapOf(
    2 to listOf(
        ChangedDefault("layoutMode", JsonPrimitive("Auto"), JsonPrimitive("Spread")),
        ChangedDefault("coverAlone", JsonPrimitive(true), JsonPrimitive(false)),
        ChangedDefault("scaleMode", JsonPrimitive("FitScreen"), JsonPrimitive("FitPage")),
        ChangedDefault("tapAdvancesStops", JsonPrimitive(false), JsonPrimitive(true)),
    ),
)

/**
 * These settings, written in [format], as the current format reads them: a setting that still holds a default a
 * later format changed takes the new default. Settings chosen away from the old default stay as they are.
 */
internal fun JsonObject.upgradedFrom(format: Int): JsonObject {
    val changes = changedDefaults.filterKeys { it > format }.toSortedMap().values.flatten()
    return changes.fold(this) { fields, change ->
        if (fields[change.key] == change.before) JsonObject(fields + (change.key to change.after)) else fields
    }
}
