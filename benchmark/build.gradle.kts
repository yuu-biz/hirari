plugins {
    id("hirari.android.test")
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

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.benchmark.traceprocessor)
    implementation(libs.androidx.test.ext.junit)
}

androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}
