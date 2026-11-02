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

/**
 * One row of the license screen. [id] is unique: Maven coordinates or `third_party/<directory>`.
 * Names are not, as in androidx.annotation's `annotation` and `annotation-jvm`. `texts` are keys
 * into [LicenseReport.texts].
 */
data class LicenseReportEntry(
    val id: String,
    val name: String,
    val version: String,
    val url: String?,
    val licenses: List<String>,
    val texts: List<String>,
)

data class LicenseReport(val libraries: List<LicenseReportEntry>, val texts: Map<String, String>)

private val COMMENT_PREFIX = Regex("""^[\s/*#]*""")
private val ENDS_WITH_YEAR = Regex("""\d{4},?$""")

/**
 * The distinct copyright lines of the files under [dir]. BSD-style licenses require the notices of
 * every file, and a library's license file often carries only a template. A line that ends with the
 * year continues on the next line, as in "Copyright (c) 1993" followed by the holder.
 */
fun copyrightNotices(dir: File, exclude: File): List<String> =
    dir.walkTopDown().filter { it.isFile && it != exclude }.flatMap { file ->
        val lines = file.readLines().map { it.replace(COMMENT_PREFIX, "").trim() }
        lines.indices.filter { lines[it].startsWith("Copyright") }.map { i ->
            val line = lines[i]
            if (ENDS_WITH_YEAR.containsMatchIn(line) && i + 1 < lines.size) "$line ${lines[i + 1]}" else line
        }
    }.toSortedSet().toList()

/**
 * Joins the Licensee report of the Maven dependencies with third_party/manifest.toml.
 * Every SPDX id gets its canonical text from [spdxTextsDir]. Native libraries also get their own
 * license file followed by the copyright notices of their sources.
 */
fun buildLicenseReport(licenseeJson: File, thirdPartyDir: File, spdxTextsDir: File): LicenseReport {
    val texts = sortedMapOf<String, String>()
    val missingTexts = sortedSetOf<String>()

    @Suppress("UNCHECKED_CAST")
    val artifacts = JsonSlurper().parse(licenseeJson) as List<Map<String, Any?>>
    val maven = artifacts.map { artifact ->
        val coordinates = "${artifact["groupId"]}:${artifact["artifactId"]}"
        val spdx = (artifact["spdxLicenses"] as List<Map<String, Any?>>?).orEmpty().map { it["identifier"] as String }
        val unknown = (artifact["unknownLicenses"] as List<Map<String, Any?>>?).orEmpty().map {
            licenseUrlIds[it["url"]] ?: throw GradleException("$coordinates has a license without an SPDX id in the Licensee report")
        }
        val ids = (spdx + unknown).distinct()
        if (ids.isEmpty()) throw GradleException("$coordinates has no SPDX license in the Licensee report")
        ids.forEach { id ->
            val file = spdxTextsDir.resolve("$id.txt")
            if (file.isFile) texts[id] = file.readText() else missingTexts += id
        }
        LicenseReportEntry(
            id = coordinates,
            name = (artifact["name"] as String?) ?: coordinates,
            version = artifact["version"] as String,
            url = (artifact["scm"] as Map<*, *>?)?.get("url") as String?,
            licenses = ids,
            texts = ids,
        )
    }
    val native = readThirdPartyManifest(thirdPartyDir).map { library ->
        val key = "third_party/${library.directory}"
        val dir = thirdPartyDir.resolve(library.directory)
        val notices = copyrightNotices(dir, exclude = dir.resolve(library.licenseFile))
        val publicDomain = if (library.publicDomain.isEmpty()) "" else {
            library.publicDomain.joinToString("\n", prefix = "\n\nFiles that hold public domain code:\n\n")
        }
        texts[key] = dir.resolve(library.licenseFile).readText().trimEnd() +
            notices.joinToString("\n", prefix = "\n\nCopyright notices in the source files:\n\n") + publicDomain
        val ids = library.spdx.split(" AND ")
        ids.forEach { id ->
            val file = spdxTextsDir.resolve("$id.txt")
            if (file.isFile) texts[id] = file.readText() else missingTexts += id
        }
        LicenseReportEntry(
            id = key,
            name = library.name,
            version = library.version,
            url = library.url,
            licenses = ids,
            texts = listOf(key) + ids,
        )
    }
    if (missingTexts.isNotEmpty()) {
        throw GradleException("Add the license text for ${missingTexts.joinToString()} to ${spdxTextsDir.path}/<SPDX id>.txt")
    }

    val libraries = maven + native
    libraries.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach {
        throw GradleException("$it appears more than once in the license report")
    }
    return LicenseReport(libraries.sortedBy { it.name.lowercase() }, texts)
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
                    mapOf("id" to it.id, "name" to it.name, "version" to it.version, "url" to it.url, "licenses" to it.licenses, "texts" to it.texts)
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
