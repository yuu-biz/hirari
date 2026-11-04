plugins {
    id("hirari.android.application")
    id("hirari.android.compose")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

android {
    defaultConfig {
        applicationId = "io.github.yuubiz.hirari"
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            isProfileable = true
        }
    }
    sourceSets {
        for (buildType in listOf("debug", "benchmark")) {
            getByName(buildType).kotlin.directories += "src/prototype/kotlin"
        }
    }
    testOptions {
        unitTests.all { test ->
            // The SMB fixture tests read the fixture's address from -Dhirari.smb.* (tools/smb-fixture) and skip
            // without it.
            test.systemProperties(providers.systemPropertiesPrefixedBy("hirari.smb.").get())
        }
    }
}

// The profile goes to src/main, so the benchmark build and the release build both carry it.
baselineProfile {
    mergeIntoMain = true
    // The AndroidX libraries ship their own profiles, which the build merges in. Keeping only the rest holds the
    // file to a size that diffs.
    filter {
        include("io.github.yuubiz.hirari.**")
        include("kotlin.**")
        include("kotlinx.**")
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:image"))
    implementation(project(":core:source"))
    implementation(project(":core:ui"))
    implementation(project(":feature:library"))
    implementation(project(":feature:reader"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:text"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":benchmark"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    // ui-test brings Espresso 3.5.0, which calls an InputManager method that API 37 no longer has.
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}

// The device test reads a corpus book from its assets, as :core:image does. A task dependency keeps :tools:corpus
// off the module dependencies.
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
    onVariants { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(corpusAssets, CorpusAssets::outputDir)
    }
}
