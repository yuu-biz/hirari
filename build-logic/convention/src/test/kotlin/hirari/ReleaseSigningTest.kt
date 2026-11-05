package hirari

import org.gradle.api.GradleException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReleaseSigningTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun writeProperties(values: Map<String, String>) {
        temp.root.resolve("keystore.properties").outputStream().use { stream ->
            Properties().apply { putAll(values) }.store(stream, null)
        }
    }

    private fun values(storeFile: String = "keys/release.jks") = mapOf(
        "storeFile" to storeFile,
        "storePassword" to "test-store-secret",
        "keyAlias" to "test-alias",
        "keyPassword" to "test-key-secret",
    )

    @Test
    fun missingFileDoesNotRequireSigning() {
        assertNull(loadReleaseSigning(temp.root))
    }

    @Test
    fun relativeStoreFileIsResolvedFromRepositoryRoot() {
        writeProperties(values())
        val signing = assertNotNull(loadReleaseSigning(temp.root))
        assertEquals(temp.root.resolve("keys/release.jks"), signing.storeFile)
        assertEquals("test-store-secret", signing.storePassword)
        assertEquals("test-alias", signing.keyAlias)
        assertEquals("test-key-secret", signing.keyPassword)
    }

    @Test
    fun absoluteStoreFileIsPreserved() {
        val key = File(temp.newFolder("elsewhere"), "release.jks")
        writeProperties(values(key.absolutePath))
        assertEquals(key, assertNotNull(loadReleaseSigning(temp.root)).storeFile)
    }

    @Test
    fun everyMissingOrBlankKeyFailsWithoutDisclosingValues() {
        for (key in values().keys) {
            for (properties in listOf(values() - key, values() + (key to "  "))) {
                writeProperties(properties)
                val error = assertFailsWith<GradleException> { loadReleaseSigning(temp.root) }
                assertEquals("Missing release signing key: $key", error.message)
            }
        }
    }
}
