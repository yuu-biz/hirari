plugins {
    id("hirari.android.feature")
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals through jdk.internal.access.
        unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }
    }
}

dependencies {
    implementation(project(":core:text"))
    implementation(project(":core:data"))
    implementation(project(":core:source"))
    implementation(project(":core:archive"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.junit)
}

// The model tests open texts sized against the heap (docs/spec.md section 3.11), so the heap is pinned.
tasks.withType<Test>().configureEach {
    maxHeapSize = "512m"
}
