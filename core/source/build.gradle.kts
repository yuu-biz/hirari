plugins {
    id("hirari.android.library")
}

android {
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Robolectric reads FileDescriptor internals through jdk.internal.access.
            test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            // The SMB fixture tests read the fixture's address from -Dhirari.smb.* (tools/smb-fixture) and skip
            // without it.
            test.systemProperties(providers.systemPropertiesPrefixedBy("hirari.smb.").get())
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.smbj)
    implementation(libs.dcerpc)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.api)
}
