package hirari

import org.gradle.api.GradleException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `maven artifacts get the SPDX text and native libraries also their notices`() {
        val report = buildLicenseReport(licensee("Apache-2.0"), thirdParty(), spdxTexts("Apache-2.0", "Zlib"))
        assertEquals(listOf("g:a0", "third_party/zlib"), report.libraries.map { it.id })
        assertEquals(listOf("text of Apache-2.0"), report.libraries[0].texts.map(report.texts::getValue))
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
            buildLicenseReport(licensee("Apache-2.0", "MIT"), thirdParty(), spdxTexts("Apache-2.0"))
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
        val report = buildLicenseReport(json, thirdParty(), spdxTexts("Apache-2.0", "Zlib"))
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
        val report = buildLicenseReport(json, thirdParty(), spdxTexts("MIT", "Zlib"))
        assertEquals(listOf("MIT"), report.libraries.single { it.id == "org.bouncycastle:bcprov-jdk18on" }.licenses)
    }

    @Test
    fun `an unknown license URL fails the build`() {
        val json = temp.newFile("unknown.json").apply {
            writeText("""[{"groupId":"g","artifactId":"a","version":"1","unknownLicenses":[{"name":"X","url":"https://example.org/x"}]}]""")
        }
        assertFailsWith<GradleException> { buildLicenseReport(json, thirdParty(), spdxTexts("Zlib")) }
    }
}
