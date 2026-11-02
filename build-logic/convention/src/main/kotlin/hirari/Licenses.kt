package hirari

import app.cash.licensee.LicenseeExtension
import app.cash.licensee.UnusedAction
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Licenses allowed in the distributed app (docs/spec.md section 8.5). Fonts may also use OFL-1.1. */
val allowedLicenses = listOf(
    "Apache-2.0",
    "MIT",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "ISC",
    "Zlib",
    "0BSD",
    "bzip2-1.0.6",
    "Unicode-3.0",
)

internal fun Project.checkMavenLicenses() {
    pluginManager.apply("app.cash.licensee")
    extensions.configure<LicenseeExtension> {
        allowedLicenses.forEach { allow(it) }
        unusedAction(UnusedAction.IGNORE)
        bundleAndroidAsset.set(true)
    }
}
