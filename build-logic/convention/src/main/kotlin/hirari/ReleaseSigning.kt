package hirari

import org.gradle.api.GradleException
import java.io.File
import java.util.Properties

// Do not use a data class: its generated toString would expose passwords.
class ReleaseSigning(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
)

fun loadReleaseSigning(root: File): ReleaseSigning? {
    val file = root.resolve("keystore.properties")
    if (!file.exists()) return null
    val properties = Properties().apply { file.inputStream().use { load(it) } }
    fun required(key: String): String = properties.getProperty(key)?.takeIf { it.isNotBlank() }
        ?: throw GradleException("Missing release signing key: $key")

    val storeFile = File(required("storeFile")).let { if (it.isAbsolute) it else root.resolve(it.path) }
    return ReleaseSigning(storeFile, required("storePassword"), required("keyAlias"), required("keyPassword"))
}
