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

    private fun entry(spdx: String = "BSD-2-Clause", sha256: String = sha, extra: String = "") = """
        [[library]]
        name = "zlib"
        version = "1.3.1"
        directory = "zlib"
        url = "https://example.org/zlib.tar.gz"
        sha256 = "$sha256"
        spdx = "$spdx"
        license_file = "LICENSE"
    """.trimIndent() + extra

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

    private fun publicDomain(file: String) = "\npublic_domain = [\"$file\"]"

    @Test
    fun `public domain code passes with the files that hold it`() {
        val manifest = entry(spdx = "BSD-2-Clause AND $PUBLIC_DOMAIN", extra = publicDomain("src/ppmd.c"))
        val libraries = readThirdPartyManifest(tree(manifest, "zlib/LICENSE", "zlib/src/ppmd.c"))
        assertEquals(listOf("src/ppmd.c"), libraries.single().publicDomain)
    }

    @Test
    fun `public domain without the files that hold it fails`() {
        assertContains(problems(tree(entry(spdx = PUBLIC_DOMAIN), "zlib/LICENSE")), "go together")
    }

    @Test
    fun `public domain files without the public domain id fail`() {
        assertContains(problems(tree(entry(extra = publicDomain("ppmd.c")), "zlib/LICENSE", "zlib/ppmd.c")), "go together")
    }

    @Test
    fun `a public domain file that is not there fails`() {
        val manifest = entry(spdx = "BSD-2-Clause AND $PUBLIC_DOMAIN", extra = publicDomain("ppmd.c"))
        assertContains(problems(tree(manifest, "zlib/LICENSE")), "public domain file third_party/zlib/ppmd.c does not exist")
    }

    @Test
    fun `public domain under another spelling is not allowed`() {
        val manifest = entry(spdx = "BSD-2-Clause AND LicenseRef-Public-Domain-Like", extra = publicDomain("ppmd.c"))
        assertContains(problems(tree(manifest, "zlib/LICENSE", "zlib/ppmd.c")), "\"LicenseRef-Public-Domain-Like\" is not allowed")
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
