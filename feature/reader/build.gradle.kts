plugins {
    id("hirari.android.feature")
}

android {
    testOptions.unitTests {
        // The semantics tests of the Compose parts run on Robolectric against the merged resources.
        isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals through jdk.internal.access.
        all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:image"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.junit)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // ui-test brings Espresso 3.5.0, which calls an InputManager method that API 37 no longer has.
    testImplementation(libs.androidx.test.espresso.core)
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}
