plugins {
    id("hirari.android.application")
    id("hirari.android.compose")
}

android {
    defaultConfig {
        applicationId = "io.github.yuubiz.hirari"
        versionCode = 1
        versionName = "0.0.1"
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
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":feature:library"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    debugImplementation(project(":feature:reader"))
    "benchmarkImplementation"(project(":feature:reader"))
}
