plugins {
    id("hirari.android.test")
    alias(libs.plugins.baselineprofile)
}

android {
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.benchmark.traceprocessor)
    implementation(libs.androidx.test.ext.junit)
}

// The generator copies a corpus book to the device's storage and opens it through the app's own file list, as
// :app's device tests read theirs from assets. A task dependency keeps :tools:corpus off the module dependencies.
evaluationDependsOn(":tools:corpus")

abstract class CorpusAssets : DefaultTask() {
    @get:InputFiles
    abstract val corpus: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:javax.inject.Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.sync {
            from(corpus)
            into(outputDir)
        }
    }
}

val corpusAssets = tasks.register<CorpusAssets>("corpusAssets") {
    corpus.from(project(":tools:corpus").tasks.named("generateCorpus"))
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType in setOf("benchmark", "nonMinifiedRelease") }
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(corpusAssets, CorpusAssets::outputDir)
    }
}
