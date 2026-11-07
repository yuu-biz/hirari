plugins {
    id("hirari.android.library")
    id("hirari.android.compose")
}

android {
    testOptions.unitTests {
        // The theme test runs a composition on Robolectric against the merged resources.
        isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals through jdk.internal.access.
        all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.material.color.utilities)
    implementation(libs.androidx.activity.compose)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // ui-test brings Espresso 3.5.0, which calls an InputManager method that API 37 no longer has.
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}
