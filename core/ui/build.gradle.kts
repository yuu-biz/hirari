plugins {
    id("hirari.android.library")
    id("hirari.android.compose")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.androidx.activity.compose)
    testImplementation(kotlin("test-junit"))
}
