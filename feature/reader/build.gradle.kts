plugins {
    id("hirari.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:image"))
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}
