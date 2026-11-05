package io.github.yuubiz.hirari.feature.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What このアプリについて shows (docs/spec.md section 4.11). A part that cannot be found is `null`, and the screen
 * says so in its place.
 */
data class AboutInfo(
    val version: String?,
    val builtOn: LocalDate?,
    val changes: String?,
    val versionCode: Long? = null,
    val commit: String = "unknown",
) {
    companion object {
        /** Written at build time: the commit identity and the commit time of HEAD, if available. */
        const val BUILD_INFO_ASSET = "about/build-info.json"

        /** The changes of this version, written by hand. */
        const val CHANGES_ASSET = "about/changes.txt"

        /** Builds the info from the app's package version and the two assets, any of which may be missing. */
        fun parse(version: String?, buildInfo: String?, changes: String?, versionCode: Long? = null): AboutInfo = AboutInfo(
            version = version?.trim()?.takeIf { it.isNotEmpty() },
            versionCode = versionCode,
            commit = buildInfo?.let(::buildCommit) ?: "unknown",
            builtOn = buildInfo?.let(::builtOn),
            changes = changes?.trim()?.takeIf { it.isNotEmpty() },
        )

        private val lenient = Json { ignoreUnknownKeys = true }

        private fun buildCommit(json: String): String = try {
            lenient.decodeFromString<BuildInfo>(json).commit?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        private fun builtOn(json: String): LocalDate? = try {
            lenient.decodeFromString<BuildInfo>(json).commitTime
                ?.let { OffsetDateTime.parse(it).toLocalDate() }
        } catch (_: Exception) {
            null
        }

        /** `2026年11月3日`, whatever the locale. */
        fun dateText(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.JAPAN))
    }

    @Serializable
    private class BuildInfo(val commitTime: String? = null, val commit: String? = null)
}
