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
        resolve("zlib/LICENSE").writeText("Copyright zlib authors")
    }

    private fun spdxTexts(vararg ids: String): File = temp.newFolder("spdx").apply {
        ids.forEach { resolve("$it.txt").writeText("text of $it") }
    }

    @Test
    fun `maven artifacts get the SPDX text and native libraries their own license file`() {
        val report = buildLicenseReport(licensee("Apache-2.0"), thirdParty(), spdxTexts("Apache-2.0"))
        assertEquals(listOf("Lib 0", "zlib"), report.libraries.map { it.name })
        assertEquals("text of Apache-2.0", report.texts.getValue(report.libraries[0].texts.single()))
        assertEquals("Copyright zlib authors", report.texts.getValue(report.libraries[1].texts.single()))
    }

    @Test
    fun `a license without a bundled text fails the build`() {
        val error = assertFailsWith<GradleException> {
            buildLicenseReport(licensee("Apache-2.0", "MIT"), thirdParty(), spdxTexts("Apache-2.0"))
        }
        assertContains(error.message.orEmpty(), "MIT")
    }
}
