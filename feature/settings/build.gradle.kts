import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    id("hirari.android.feature")
    alias(libs.plugins.kotlin.serialization)
}

android {
    testOptions.unitTests {
        isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals through jdk.internal.access.
        all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

// The build date of このアプリについて is the commit time of HEAD (docs/spec.md section 4.11), so one commit builds
// to one date. Without the tool, or outside a repository, the date is left out.
abstract class CommitTime : ValueSource<String, CommitTime.Parameters> {
    interface Parameters : ValueSourceParameters {
        val directory: DirectoryProperty
    }

    @get:Inject
    abstract val exec: ExecOperations

    override fun obtain(): String {
        val out = ByteArrayOutputStream()
        return try {
            val result = exec.exec {
                workingDir = parameters.directory.get().asFile
                commandLine("git", "log", "-1", "--format=%cI")
                standardOutput = out
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
            if (result.exitValue == 0) out.toString(Charsets.UTF_8).trim() else ""
        } catch (_: Exception) {
            ""
        }
    }
}

abstract class GenerateBuildInfo : DefaultTask() {
    @get:Input
    abstract val commitTime: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val time = commitTime.get().takeIf { it.isNotEmpty() }?.let { "\"$it\"" } ?: "null"
        out.resolve("about").apply { mkdirs() }.resolve("build-info.json").writeText("{\"commitTime\":$time}")
    }
}

val headCommitTime = providers.of(CommitTime::class) {
    parameters.directory.set(rootProject.layout.projectDirectory)
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<GenerateBuildInfo>("generate${name}BuildInfo") {
            commitTime.set(headCommitTime)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateBuildInfo::outputDir)
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:archive"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // ui-test brings Espresso 3.5.0, which calls an InputManager method that API 37 no longer has.
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}
