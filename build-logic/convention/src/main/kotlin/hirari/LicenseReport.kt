package hirari

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/** One row of the license screen. `texts` are keys into [LicenseReport.texts]. */
data class LicenseReportEntry(
    val name: String,
    val version: String,
    val url: String?,
    val licenses: List<String>,
    val texts: List<String>,
)

data class LicenseReport(val libraries: List<LicenseReportEntry>, val texts: Map<String, String>)

/**
 * Joins the Licensee report of the Maven dependencies with third_party/manifest.toml.
 * Maven artifacts get the canonical text of each SPDX id from [spdxTextsDir]; native libraries
 * get their own license file, which carries the copyright notice.
 */
fun buildLicenseReport(licenseeJson: File, thirdPartyDir: File, spdxTextsDir: File): LicenseReport {
    val texts = sortedMapOf<String, String>()
    val missingTexts = sortedSetOf<String>()

    @Suppress("UNCHECKED_CAST")
    val artifacts = JsonSlurper().parse(licenseeJson) as List<Map<String, Any?>>
    val maven = artifacts.map { artifact ->
        val ids = (artifact["spdxLicenses"] as List<Map<String, Any?>>? ).orEmpty().map { it["identifier"] as String }
        val unknown = (artifact["unknownLicenses"] as List<*>?).orEmpty()
        val coordinates = "${artifact["groupId"]}:${artifact["artifactId"]}"
        if (ids.isEmpty() || unknown.isNotEmpty()) throw GradleException("$coordinates has no SPDX license in the Licensee report")
        ids.forEach { id ->
            val file = spdxTextsDir.resolve("$id.txt")
            if (file.isFile) texts[id] = file.readText() else missingTexts += id
        }
        LicenseReportEntry(
            name = (artifact["name"] as String?) ?: coordinates,
            version = artifact["version"] as String,
            url = (artifact["scm"] as Map<*, *>?)?.get("url") as String?,
            licenses = ids,
            texts = ids,
        )
    }
    if (missingTexts.isNotEmpty()) {
        throw GradleException("Add the license text for ${missingTexts.joinToString()} to ${spdxTextsDir.path}/<SPDX id>.txt")
    }

    val native = readThirdPartyManifest(thirdPartyDir).map { library ->
        val key = "third_party/${library.directory}"
        texts[key] = thirdPartyDir.resolve(library.directory).resolve(library.licenseFile).readText()
        LicenseReportEntry(
            name = library.name,
            version = library.version,
            url = library.url,
            licenses = library.spdx.split(" AND "),
            texts = listOf(key),
        )
    }

    return LicenseReport((maven + native).sortedBy { it.name.lowercase() }, texts)
}

abstract class GenerateLicenseReportTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseeReport: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyDir: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val spdxTextsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val report = buildLicenseReport(
            licenseeReport.get().asFile,
            thirdPartyDir.get().asFile,
            spdxTextsDir.get().asFile,
        )
        val json = JsonOutput.toJson(
            mapOf(
                "libraries" to report.libraries.map {
                    mapOf("name" to it.name, "version" to it.version, "url" to it.url, "licenses" to it.licenses, "texts" to it.texts)
                },
                "texts" to report.texts,
            ),
        )
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        out.resolve(LICENSE_ASSET).writeText(json)
    }

    companion object {
        const val LICENSE_ASSET = "licenses.json"
    }
}
