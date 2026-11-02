plugins {
    id("hirari.android.library")
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(kotlin("test-junit"))
}
