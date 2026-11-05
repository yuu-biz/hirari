package hirari

import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Original runtime artifacts, before Android's resource/Java transforms, and their Maven POM. */
data class MavenLicenseArtifact(
    @get:Input val coordinates: String,
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val files: List<File>,
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val pom: File?,
)

private val LICENSE_PATH = Regex("""(?:META-INF/)?(?:LICENSE|NOTICE)[^/]*""", RegexOption.IGNORE_CASE)
private val ENDS_WITH_COPYRIGHT_YEAR = Regex("""\d{4},?$""")
internal val COPYRIGHT_PLACEHOLDER = Regex(
    """<\s*(?:year|copyright holders?|owner)\s*>|\bYEAR by AUTHOR EMAIL\b""",
    RegexOption.IGNORE_CASE,
)

/** Keep notices separate: a NOTICE alone is not the license body. */
private data class ArtifactText(val path: String, val text: String) {
    val isLicense: Boolean get() = path.substringAfterLast('/').startsWith("LICENSE", ignoreCase = true)
}

private fun artifactTexts(file: File): List<ArtifactText> = ZipFile(file).use { zip ->
    zip.entries().asSequence().filterNot { it.isDirectory }.sortedBy { it.name }.flatMap { entry ->
        when {
            LICENSE_PATH.matches(entry.name) -> listOf(
                ArtifactText(entry.name, zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }),
            )
            file.extension.equals("aar", ignoreCase = true) &&
                (entry.name == "classes.jar" || entry.name.startsWith("libs/")) && entry.name.endsWith(".jar") -> {
                ZipInputStream(zip.getInputStream(entry)).use { jar ->
                    buildList {
                        var nested = jar.nextEntry
                        while (nested != null) {
                            if (!nested.isDirectory && LICENSE_PATH.matches(nested.name)) {
                                add(ArtifactText(nested.name, jar.readBytes().toString(Charsets.UTF_8)))
                            }
                            nested = jar.nextEntry
                        }
                    }
                }
            }
            else -> emptyList()
        }.asSequence()
    }.filter { it.text.isNotBlank() }.toList()
}

private fun Element.children(name: String): List<Element> = (0 until childNodes.length)
    .mapNotNull { childNodes.item(it) as? Element }.filter { (it.localName ?: it.tagName) == name }

private fun Element.value(name: String): String? = children(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }

private data class PomNotices(val copyright: String?, val comments: List<String>)

private fun copyrightLines(text: String): List<String> {
    val lines = text.lines().map(String::trim)
    return lines.indices.filter {
        lines[it].startsWith("Copyright", ignoreCase = true) || lines[it].startsWith("©")
    }.map { i ->
        val line = lines[i]
        if (ENDS_WITH_COPYRIGHT_YEAR.containsMatchIn(line) && i + 1 < lines.size && lines[i + 1].isNotBlank()) {
            "$line ${lines[i + 1]}"
        } else line
    }
}

private fun pomNotices(file: File?): PomNotices {
    if (file == null) return PomNotices(null, emptyList())
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    }
    val root = factory.newDocumentBuilder().parse(file).documentElement
    val comments = root.children("licenses").flatMap { it.children("license") }.mapNotNull { it.value("comments") }
    val copyright = comments.flatMap(::copyrightLines)
    if (copyright.isNotEmpty()) return PomNotices(copyright.joinToString("\n"), comments)
    val holders = (root.children("organization").mapNotNull { it.value("name") } +
        root.children("developers").flatMap { it.children("developer") }.flatMap {
            listOfNotNull(it.value("name"), it.value("organization"))
        }).distinct()
    val year = root.value("inceptionYear")?.let { "$it " }.orEmpty()
    return PomNotices(holders.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Copyright (c) $year"), comments)
}

/** Replace only attribution template lines, never invent a year or use the current build year. */
internal fun fillCopyright(text: String, copyright: String): String = text.lineSequence().joinToString("\n") { line ->
    if (line.contains("Copyright", ignoreCase = true) && COPYRIGHT_PLACEHOLDER.containsMatchIn(line)) copyright else line
}

internal fun mavenLicenseText(artifact: MavenLicenseArtifact, ids: List<String>, spdxTextsDir: File): String {
    val shipped = artifact.files.sortedBy { it.name }.flatMap(::artifactTexts)
    val parts = shipped.map { it.text }.toMutableList()
    val needsBody = shipped.none { it.isLicense }
    val shippedCopyright = shipped.flatMap { copyrightLines(it.text) }
        // This is the Apache license's example in its appendix, not a library's attribution.
        .filterNot { it.contains("[yyyy] [name of copyright owner]") }.distinct()
        .takeIf { it.isNotEmpty() }?.joinToString("\n")
    val pom = if (needsBody || shippedCopyright == null) pomNotices(artifact.pom) else PomNotices(null, emptyList())
    if (shippedCopyright == null && pom.copyright != null) parts += pom.copyright
    parts += pom.comments
    if (needsBody) {
        val copyright = shippedCopyright ?: pom.copyright
        copyright?.let { parts += it }
        ids.forEach { id ->
            val template = spdxTextsDir.resolve("$id.txt")
            if (!template.isFile) throw GradleException("Add the license text for $id to ${spdxTextsDir.path}/<SPDX id>.txt")
            // A license such as Apache-2.0 has no attribution line to fill, so it needs no holder.
            val body = copyright?.let { fillCopyright(template.readText(), it) } ?: template.readText()
            if (COPYRIGHT_PLACEHOLDER.containsMatchIn(body)) throw GradleException(
                if (copyright == null) "${artifact.coordinates} has no license text or copyright holder in its POM for $id"
                else "${artifact.coordinates} still has a placeholder in its $id text",
            )
            parts += body
        }
    }
    return parts.distinct().joinToString("\n\n")
}
