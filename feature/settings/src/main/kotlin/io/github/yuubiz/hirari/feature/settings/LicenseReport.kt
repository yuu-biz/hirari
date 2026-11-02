package io.github.yuubiz.hirari.feature.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The asset `licenses.json`, generated at build time by build-logic's GenerateLicenseReportTask. */
@Serializable
data class LicenseReport(val libraries: List<LicensedLibrary>, val texts: Map<String, String>) {
    fun textsOf(library: LicensedLibrary): List<String> = library.texts.map { texts.getValue(it) }

    companion object {
        const val ASSET = "licenses.json"

        fun parse(json: String): LicenseReport {
            val report = Json.decodeFromString<LicenseReport>(json)
            val dangling = report.libraries.flatMap { it.texts }.filterNot { it in report.texts }
            require(dangling.isEmpty()) { "licenses.json has no text for $dangling" }
            val duplicates = report.libraries.groupBy { it.id }.filterValues { it.size > 1 }.keys
            require(duplicates.isEmpty()) { "licenses.json repeats $duplicates" }
            return report
        }
    }
}

/** [id] is unique (Maven coordinates or `third_party/<directory>`); [name] is not. */
@Serializable
data class LicensedLibrary(
    val id: String,
    val name: String,
    val version: String,
    val url: String? = null,
    val licenses: List<String>,
    val texts: List<String>,
)
