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
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:ui"))
    implementation(project(":feature:library"))
    implementation(project(":feature:settings"))
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
}
