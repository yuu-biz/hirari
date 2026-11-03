plugins {
    id("hirari.android.library")
    id("hirari.android.compose")
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(kotlin("test-junit"))
}
