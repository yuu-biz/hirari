plugins {
    id("hirari.android.library")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:source"))
    implementation(project(":core:archive"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
