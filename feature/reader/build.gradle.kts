plugins {
    id("hirari.android.feature")
}

dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}
