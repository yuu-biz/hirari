package hirari

import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class LicenseReportTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun licensee(vararg spdx: String): File = temp.newFile("artifacts.json").apply {
        writeText(
            spdx.mapIndexed { i, id ->
                """{"groupId":"g","artifactId":"a$i","version":"1.$i","name":"Lib $i","spdxLicenses":[{"identifier":"$id"}]}"""
            }.joinToString(",", "[", "]"),
        )
    }

    private fun thirdParty(): File = temp.newFolder("third_party").apply {
        resolve("manifest.toml").writeText(
            """
            [[library]]
            name = "zlib"
            version = "1.3.1"
            directory = "zlib"
            url = "https://example.org/zlib.tar.gz"
            sha256 = "${"b".repeat(64)}"
            spdx = "Zlib"
            license_file = "LICENSE"
            """.trimIndent(),
        )
        resolve("zlib").mkdirs()
        resolve("zlib/LICENSE").writeText("License template <author>")
        resolve("zlib/a.c").writeText("/*\n * Copyright (c) 1993\n *\tThe Regents of the University of California.\n */\n")
        resolve("zlib/b.h").writeText("// Copyright (c) 2020 Someone\n// Copyright (c) 2020 Someone\n")
    }

    private fun spdxTexts(vararg ids: String): File = temp.newFolder("spdx").apply {
        ids.forEach { resolve("$it.txt").writeText("text of $it") }
    }

    private fun pom(body: String = "<organization><name>POM holder</name></organization>"): File = temp.newFile().apply {
        writeText("""<project xmlns="http://maven.apache.org/POM/4.0.0">$body</project>""")
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, text) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(text)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun artifact(vararg entries: Pair<String, String>, holder: File? = pom()): MavenLicenseArtifact {
        val jar = temp.newFile("artifact-${temp.root.listFiles()!!.size}.jar").apply {
            writeBytes(zip(*entries.map { it.first to it.second.toByteArray() }.toTypedArray()))
        }
        return MavenLicenseArtifact("g:a0:1.0", listOf(jar), holder)
    }

    private fun sources(json: File): List<MavenLicenseArtifact> {
        @Suppress("UNCHECKED_CAST")
        val artifacts = JsonSlurper().parse(json) as List<Map<String, Any?>>
        return artifacts.map {
            MavenLicenseArtifact("${it["groupId"]}:${it["artifactId"]}:${it["version"]}", emptyList(), pom())
        }
    }

    private fun report(
        json: File,
        native: File,
        templates: File,
        artifacts: List<MavenLicenseArtifact> = sources(json),
    ): LicenseReport = buildLicenseReport(json, native, templates, artifacts)

    @Test
    fun `maven fallback gets POM attribution and native libraries keep their notices`() {
        val report = report(licensee("Apache-2.0"), thirdParty(), spdxTexts("Apache-2.0", "Zlib"))
        assertEquals(listOf("g:a0", "third_party/zlib"), report.libraries.map { it.id })
        assertEquals(listOf("Copyright (c) POM holder\n\ntext of Apache-2.0"), report.libraries[0].texts.map(report.texts::getValue))
        assertEquals(
            listOf(
                "License template <author>\n\nCopyright notices in the source files:\n\n" +
                    "Copyright (c) 1993 The Regents of the University of California.\n" +
                    "Copyright (c) 2020 Someone",
                "text of Zlib",
            ),
            report.libraries[1].texts.map(report.texts::getValue),
        )
    }

    @Test
    fun `a license without a bundled text fails the build`() {
        val error = assertFailsWith<GradleException> {
            report(licensee("Apache-2.0", "MIT"), thirdParty(), spdxTexts("Apache-2.0"))
        }
        assertContains(error.message.orEmpty(), "MIT")
    }

    @Test
    fun `artifacts with the same name and version keep distinct ids`() {
        val json = temp.newFile("same-name.json").apply {
            writeText(
                listOf("annotation", "annotation-jvm").joinToString(",", "[", "]") {
                    """{"groupId":"androidx.annotation","artifactId":"$it","version":"1.10.0","name":"Annotation","spdxLicenses":[{"identifier":"Apache-2.0"}]}"""
                },
            )
        }
        val report = report(json, thirdParty(), spdxTexts("Apache-2.0", "Zlib"))
        assertEquals(
            listOf("androidx.annotation:annotation", "androidx.annotation:annotation-jvm"),
            report.libraries.filter { it.name == "Annotation" }.map { it.id },
        )
    }

    @Test
    fun `a known license URL without an SPDX id gets the id of its text`() {
        val json = temp.newFile("url.json").apply {
            writeText(
                """[{"groupId":"org.bouncycastle","artifactId":"bcprov-jdk18on","version":"1.85.2","name":"Bouncy Castle",""" +
                    """"unknownLicenses":[{"name":"Bouncy Castle Licence","url":"https://www.bouncycastle.org/licence.html"}]}]""",
            )
        }
        val report = report(json, thirdParty(), spdxTexts("MIT", "Zlib"))
        assertEquals(listOf("MIT"), report.libraries.single { it.id == "org.bouncycastle:bcprov-jdk18on" }.licenses)
    }

    @Test
    fun `an unknown license URL fails the build`() {
        val json = temp.newFile("unknown.json").apply {
            writeText("""[{"groupId":"g","artifactId":"a","version":"1","unknownLicenses":[{"name":"X","url":"https://example.org/x"}]}]""")
        }
        assertFailsWith<GradleException> { report(json, thirdParty(), spdxTexts("Zlib")) }
    }

    @Test
    fun `MIT artifact displays its own LICENSE including its holder without needing a template or POM`() {
        val license = "Copyright (c) 2025 Library Authors\n\nPermission is hereby granted to use this library."
        val report = report(
            licensee("MIT"), thirdParty(), spdxTexts("Zlib"),
            listOf(artifact("META-INF/LICENSE.txt" to license, holder = null)),
        )
        assertEquals(listOf(license), report.libraries.first().texts.map(report.texts::getValue))
    }

    @Test
    fun `missing text uses the template body with the POM organization developers and year`() {
        val templates = spdxTexts("MIT", "Zlib").apply {
            resolve("MIT.txt").writeText("MIT License\nCopyright (c) <year> <copyright holders>\n\nPermission is hereby granted.")
        }
        val source = artifact(holder = pom(
            "<inceptionYear>2004</inceptionYear><organization><name>Library Org</name></organization>" +
                "<developers><developer><name>A. Developer</name><organization>Dev Org</organization></developer></developers>",
        ))
        val report = report(licensee("MIT"), thirdParty(), templates, listOf(source))
        val text = report.texts.getValue("g:a0")
        assertContains(text, "Copyright (c) 2004 Library Org, A. Developer, Dev Org")
        assertContains(text, "Permission is hereby granted.")
        assertFalse(text.contains("<year>"))
        assertFalse(text.contains("<copyright holders>"))
    }

    @Test
    fun `fallback uses a copyright notice from the POM license comments`() {
        val source = artifact(holder = pom(
            "<licenses><license><name>MIT</name><comments>Copyright (c) 2010 Original Holder</comments></license></licenses>",
        ))
        val report = report(licensee("MIT"), thirdParty(), spdxTexts("MIT", "Zlib"), listOf(source))
        assertContains(report.texts.getValue("g:a0"), "Copyright (c) 2010 Original Holder")
    }

    @Test
    fun `BSD ISC and 0BSD fallback bodies replace their attribution templates`() {
        val ids = arrayOf("BSD-2-Clause", "BSD-3-Clause", "ISC", "0BSD")
        val templates = spdxTexts(*ids, "Zlib").apply {
            ids.forEach { id ->
                val attribution = if (id == "0BSD") "Copyright (C) YEAR by AUTHOR EMAIL" else "Copyright (c) <year> <owner>"
                resolve("$id.txt").writeText("$attribution\nBody of $id")
            }
        }
        val report = report(licensee(*ids), thirdParty(), templates)
        ids.forEachIndexed { i, id ->
            val text = report.texts.getValue("g:a$i")
            assertContains(text, "Copyright (c) POM holder")
            assertContains(text, "Body of $id")
            assertFalse(COPYRIGHT_PLACEHOLDER.containsMatchIn(text))
        }
    }

    @Test
    fun `fallback without a POM holder fails instead of inventing attribution`() {
        val texts = spdxTexts("MIT", "Zlib").apply { resolve("MIT.txt").writeText("Copyright (c) <year> <copyright holders>\nMIT terms") }
        val error = assertFailsWith<GradleException> {
            report(licensee("MIT"), thirdParty(), texts, listOf(artifact(holder = pom(""))))
        }
        assertContains(error.message.orEmpty(), "g:a0:1.0")
        assertContains(error.message.orEmpty(), "copyright holder")
    }

    @Test
    fun `a license with nothing to fill needs no POM holder`() {
        val texts = spdxTexts("Apache-2.0", "Zlib").apply { resolve("Apache-2.0.txt").writeText("Apache License\nVersion 2.0") }
        val shown = report(licensee("Apache-2.0"), thirdParty(), texts, listOf(artifact(holder = pom(""))))
        assertContains(shown.texts.values.joinToString(), "Apache License")
    }

    @Test
    fun `Apache LICENSE and NOTICE are both displayed verbatim`() {
        val report = report(
            licensee("Apache-2.0"), thirdParty(), spdxTexts("Zlib"),
            listOf(artifact("LICENSE" to "Apache license body", "META-INF/NOTICE.txt" to "Copyright 2020 Notice Holder\nRequired notice")),
        )
        val text = report.texts.getValue("g:a0")
        assertContains(text, "Apache license body")
        assertContains(text, "Copyright 2020 Notice Holder\nRequired notice")
    }

    @Test
    fun `shipped license body without attribution is supplemented with the POM holder`() {
        val report = report(
            licensee("Apache-2.0"), thirdParty(), spdxTexts("Zlib"),
            listOf(artifact("LICENSE" to "Apache body\nCopyright [yyyy] [name of copyright owner]")),
        )
        assertContains(report.texts.getValue("g:a0"), "Apache body")
        assertContains(report.texts.getValue("g:a0"), "Copyright (c) POM holder")
    }

    @Test
    fun `NOTICE alone is retained alongside the fallback body`() {
        val report = report(
            licensee("Apache-2.0"), thirdParty(), spdxTexts("Apache-2.0", "Zlib"),
            listOf(artifact("NOTICE" to "Required notice")),
        )
        assertContains(report.texts.getValue("g:a0"), "Required notice")
        assertContains(report.texts.getValue("g:a0"), "text of Apache-2.0")
    }

    @Test
    fun `NOTICE copyright takes precedence over the POM holder for a fallback body`() {
        val report = report(
            licensee("MIT"), thirdParty(), spdxTexts("MIT", "Zlib"),
            listOf(artifact("NOTICE" to "Copyright (c) 2001\nActual Holder", holder = null)),
        )
        assertContains(report.texts.getValue("g:a0"), "Copyright (c) 2001 Actual Holder")
        assertContains(report.texts.getValue("g:a0"), "text of MIT")
    }

    @Test
    fun `AAR includes outer notices and LICENSE and NOTICE in its nested jars`() {
        val aar = temp.newFile("library.aar").apply {
            writeBytes(zip(
                "META-INF/NOTICE" to "Outer notice".toByteArray(),
                "classes.jar" to zip("META-INF/LICENSE-MIT" to "Copyright (c) Main Holder\nMIT body".toByteArray()),
                "libs/extra.jar" to zip("NOTICE.txt" to "Nested notice".toByteArray()),
            ))
        }
        val report = report(
            licensee("MIT"), thirdParty(), spdxTexts("Zlib"),
            listOf(MavenLicenseArtifact("g:a0:1.0", listOf(aar), null)),
        )
        val text = report.texts.getValue("g:a0")
        listOf("Outer notice", "Copyright (c) Main Holder\nMIT body", "Nested notice").forEach { assertContains(text, it) }
    }

    @Test
    fun `artifacts sharing an SPDX id keep their own holders`() {
        val first = artifact("LICENSE" to "Copyright (c) First Holder")
        val second = artifact("LICENSE" to "Copyright (c) Second Holder").copy(coordinates = "g:a1:1.1")
        val report = report(licensee("MIT", "MIT"), thirdParty(), spdxTexts("Zlib"), listOf(first, second))
        assertEquals("Copyright (c) First Holder", report.texts.getValue("g:a0"))
        assertEquals("Copyright (c) Second Holder", report.texts.getValue("g:a1"))
    }

    @Test
    fun `placeholder anywhere in a shipped LICENSE or NOTICE fails`() {
        val json = licensee("MIT")
        val native = thirdParty()
        val templates = spdxTexts("Zlib")
        listOf("LICENSE", "META-INF/NOTICE").forEach { path ->
            val error = assertFailsWith<GradleException> {
                report(json, native, templates, listOf(artifact("LICENSE.txt" to "MIT body", path to "Copyright (c) <year> <copyright holders>")))
            }
            assertContains(error.message.orEmpty(), "placeholder")
        }
    }

    @Test
    fun `a placeholder outside a template attribution line still fails`() {
        val templates = spdxTexts("MIT", "Zlib").apply { resolve("MIT.txt").writeText("MIT body\nUnfilled <owner>") }
        val error = assertFailsWith<GradleException> { report(licensee("MIT"), thirdParty(), templates) }
        assertContains(error.message.orEmpty(), "placeholder")
    }

    @Test
    fun `a placeholder in the POM holder still fails after substitution`() {
        val error = assertFailsWith<GradleException> {
            report(
                licensee("MIT"), thirdParty(), spdxTexts("MIT", "Zlib"),
                listOf(artifact(holder = pom("<organization><name>&lt;copyright holders&gt;</name></organization>"))),
            )
        }
        assertContains(error.message.orEmpty(), "placeholder")
    }

    @Test
    fun `native supplemental SPDX bodies use the native notices instead of placeholder holders`() {
        val templates = spdxTexts("Apache-2.0", "Zlib").apply {
            resolve("Zlib.txt").writeText("Copyright (c) <year> <owner>\nNative license body")
        }
        val report = report(licensee("Apache-2.0"), thirdParty(), templates)
        val text = report.texts.getValue("third_party/zlib/Zlib")
        assertContains(text, "Copyright (c) 1993 The Regents of the University of California.")
        assertContains(text, "Copyright (c) 2020 Someone")
        assertContains(text, "Native license body")
        assertFalse(text.contains("<owner>"))
    }
}
