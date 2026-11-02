plugins {
    id("hirari.android.feature")
}

android {
    testOptions {
        unitTests.all { test ->
            // The SMB fixture tests read the fixture's address from -Dhirari.smb.* (tools/smb-fixture) and skip
            // without it.
            test.systemProperties(providers.systemPropertiesPrefixedBy("hirari.smb.").get())
        }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:source"))
    implementation(project(":core:archive"))
    implementation(project(":core:image"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
}
