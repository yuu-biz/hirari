package hirari

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.tomlj.Toml
import java.io.File

/** One entry of third_party/manifest.toml (docs/spec.md section 8.5). */
data class ThirdPartyLibrary(
    val name: String,
    val version: String,
    val directory: String,
    val url: String,
    val sha256: String,
    /** SPDX identifiers joined by " AND ". For a dual license, only the chosen side. */
    val spdx: String,
    /** Path of the license text, relative to the library's directory. */
    val licenseFile: String,
)

private val SHA256 = Regex("[0-9a-f]{64}")

/** Parses the manifest and checks it against the tree; throws with every problem found. */
fun readThirdPartyManifest(thirdPartyDir: File): List<ThirdPartyLibrary> {
    val manifest = thirdPartyDir.resolve("manifest.toml")
    val problems = mutableListOf<String>()
    val parsed = Toml.parse(manifest.toPath())
    parsed.errors().forEach { problems += "manifest.toml: $it" }

    val entries = parsed.getArrayOrEmpty("library")
    val libraries = (0 until entries.size()).mapNotNull { i ->
        val table = entries.getTable(i)
        fun field(key: String): String? =
            table.getString(key).also { if (it.isNullOrBlank()) problems += "library #${i + 1}: missing \"$key\"" }
        val library = ThirdPartyLibrary(
            name = field("name") ?: return@mapNotNull null,
            version = field("version") ?: return@mapNotNull null,
            directory = field("directory") ?: return@mapNotNull null,
            url = field("url") ?: return@mapNotNull null,
            sha256 = field("sha256") ?: return@mapNotNull null,
            spdx = field("spdx") ?: return@mapNotNull null,
            licenseFile = field("license_file") ?: return@mapNotNull null,
        )
        library
    }

    for (library in libraries) {
        val where = "library \"${library.name}\""
        if (!SHA256.matches(library.sha256)) problems += "$where: sha256 must be 64 lowercase hex digits"
        library.spdx.split(" AND ").filterNot { it in allowedLicenses }.forEach {
            problems += "$where: license \"$it\" is not allowed (docs/spec.md section 8.5)"
        }
        val dir = thirdPartyDir.resolve(library.directory)
        if (!dir.isDirectory) problems += "$where: directory third_party/${library.directory} does not exist"
        else if (!dir.resolve(library.licenseFile).isFile) {
            problems += "$where: license file third_party/${library.directory}/${library.licenseFile} does not exist"
        }
    }
    libraries.groupBy { it.directory }.filterValues { it.size > 1 }.keys.forEach {
        problems += "third_party/$it is listed more than once"
    }

    val listed = libraries.map { it.directory }.toSet()
    thirdPartyDir.listFiles { file -> file.isDirectory }.orEmpty()
        .filter { it.name !in listed }
        .forEach { problems += "third_party/${it.name} is not listed in manifest.toml" }

    if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n", prefix = "third_party/manifest.toml:\n"))
    return libraries
}

abstract class VerifyThirdPartyTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val thirdPartyDir: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val libraries = readThirdPartyManifest(thirdPartyDir.get().asFile)
        report.get().asFile.writeText(libraries.joinToString("") { "${it.name} ${it.version} ${it.spdx}\n" })
    }
}
