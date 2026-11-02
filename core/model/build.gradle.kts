plugins {
    id("hirari.jvm.library")
}

dependencies {
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
}
