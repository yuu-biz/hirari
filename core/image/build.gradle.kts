import javax.inject.Inject

plugins {
    id("hirari.android.library")
}

android {
    ndkVersion = "28.2.13676358"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:source"))
    implementation(project(":core:archive"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}

// The corpus reaches the device tests as assets, as in :core:archive. A task dependency keeps :tools:corpus out
// of the module dependencies, which docs/spec.md section 8.2 does not allow here.
evaluationDependsOn(":tools:corpus")

abstract class CorpusAssets : DefaultTask() {
    @get:InputFiles
    abstract val corpus: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
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
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(corpusAssets, CorpusAssets::outputDir)
    }
}
