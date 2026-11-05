package hirari

import app.cash.licensee.LicenseeExtension
import app.cash.licensee.UnusedAction
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedArtifactResult
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import org.gradle.maven.MavenModule
import org.gradle.maven.MavenPomArtifact

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

/**
 * The id of public domain code whose origin is clear, allowed only in the native sources under third_party/,
 * where the manifest lists the files that carry it (docs/spec.md section 8.5). SPDX has no id for it.
 */
const val PUBLIC_DOMAIN = "LicenseRef-Public-Domain"

/**
 * License URLs in POMs that Licensee does not map to an SPDX id, and the id of the text they carry. Both the
 * check and the license screen use this table (docs/spec.md section 8.5).
 */
val licenseUrlIds = mapOf(
    "https://www.bouncycastle.org/licence.html" to "MIT",
    "https://opensource.org/license/mit" to "MIT",
)

/** Checks the Maven licenses and bundles the license screen's data as the asset `licenses.json`. */
internal fun Project.checkAndBundleLicenses() {
    pluginManager.apply("app.cash.licensee")
    extensions.configure<LicenseeExtension> {
        allowedLicenses.forEach { allow(it) }
        licenseUrlIds.forEach { (url, id) -> allowUrl(url) { because("the text at this URL is $id") } }
        unusedAction(UnusedAction.IGNORE)
    }
    extensions.configure<ApplicationAndroidComponentsExtension> {
        onVariants { variant ->
            val name = variant.name.replaceFirstChar { it.uppercase() }
            val runtime = configurations.named("${variant.name}RuntimeClasspath")
            val licenseInputs = providers.provider {
                val configuration = runtime.get()
                val artifacts = configuration.incoming.artifactView {
                    componentFilter { it is ModuleComponentIdentifier }
                    isLenient = true
                }.artifacts.artifacts.groupBy { it.id.componentIdentifier }
                val components = configuration.incoming.resolutionResult.allComponents.mapNotNull {
                    it.id as? ModuleComponentIdentifier
                }
                val poms = dependencies.createArtifactResolutionQuery()
                    .forComponents(components)
                    .withArtifacts(MavenModule::class.java, MavenPomArtifact::class.java)
                    .execute().resolvedComponents.associate { component ->
                        component.id to component.getArtifacts(MavenPomArtifact::class.java)
                            .filterIsInstance<ResolvedArtifactResult>().singleOrNull()?.file
                    }
                components.map { component ->
                    MavenLicenseArtifact(
                        coordinates = "${component.group}:${component.module}:${component.version}",
                        files = artifacts[component].orEmpty().map { it.file }.filter {
                            it.extension.equals("jar", ignoreCase = true) || it.extension.equals("aar", ignoreCase = true)
                        },
                        pom = poms[component],
                    )
                }.sortedBy { it.coordinates }
            }
            val task = tasks.register<GenerateLicenseReportTask>("generate${name}LicenseReport") {
                dependsOn("licenseeAndroid$name")
                licenseeReport.set(layout.buildDirectory.file("reports/licensee/android$name/artifacts.json"))
                thirdPartyDir.set(rootProject.layout.projectDirectory.dir("third_party"))
                spdxTextsDir.set(rootProject.layout.projectDirectory.dir("build-logic/spdx"))
                mavenArtifacts.set(licenseInputs)
            }
            variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateLicenseReportTask::outputDir)
        }
    }
}
