plugins {
    id("hirari.android.library")
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(libs.junit)
}
