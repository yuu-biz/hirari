package hirari

import org.gradle.api.GradleException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ThirdPartyManifestTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val sha = "a".repeat(64)

    private fun tree(manifest: String, vararg files: String): File {
        val root = temp.newFolder("third_party")
        root.resolve("manifest.toml").writeText(manifest)
        files.forEach { root.resolve(it).apply { parentFile.mkdirs() }.writeText("x") }
        return root
    }

    private fun entry(spdx: String = "BSD-2-Clause", sha256: String = sha) = """
        [[library]]
        name = "zlib"
        version = "1.3.1"
        directory = "zlib"
        url = "https://example.org/zlib.tar.gz"
        sha256 = "$sha256"
        spdx = "$spdx"
        license_file = "LICENSE"
    """.trimIndent()

    private fun problems(root: File): String =
        assertFailsWith<GradleException> { readThirdPartyManifest(root) }.message.orEmpty()

    @Test
    fun `a listed directory with its license file passes`() {
        val libraries = readThirdPartyManifest(tree(entry(spdx = "Zlib AND BSD-3-Clause"), "zlib/LICENSE"))
        assertEquals(listOf("zlib"), libraries.map { it.directory })
    }

    @Test
    fun `an empty manifest passes when there are no directories`() {
        assertEquals(emptyList(), readThirdPartyManifest(tree("# nothing yet\n")))
    }

    @Test
    fun `a directory missing from the manifest fails`() {
        assertContains(problems(tree(entry(), "zlib/LICENSE", "bzip2/LICENSE")), "third_party/bzip2 is not listed")
    }

    @Test
    fun `a missing license file fails`() {
        assertContains(problems(tree(entry(), "zlib/README")), "license file third_party/zlib/LICENSE does not exist")
    }

    @Test
    fun `a license outside the allowlist fails`() {
        assertContains(problems(tree(entry(spdx = "BSD-2-Clause AND LGPL-2.1-only"), "zlib/LICENSE")), "\"LGPL-2.1-only\" is not allowed")
    }

    @Test
    fun `a malformed checksum fails`() {
        assertContains(problems(tree(entry(sha256 = "ABC"), "zlib/LICENSE")), "sha256 must be 64 lowercase hex digits")
    }

    @Test
    fun `a missing field fails`() {
        val manifest = entry().lines().filterNot { it.startsWith("url") }.joinToString("\n")
        assertContains(problems(tree(manifest, "zlib/LICENSE")), "missing \"url\"")
    }
}
