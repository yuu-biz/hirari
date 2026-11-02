plugins {
    id("hirari.android.library")
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    testOptions.unitTests {
        isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals through jdk.internal.access.
        all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.room.runtime)
    implementation(libs.sqlite.framework)
    ksp(libs.room.compiler)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
}
