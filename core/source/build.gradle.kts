plugins {
    id("hirari.android.library")
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
    implementation(project(":core:model"))
    implementation(libs.smbj)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.api)
}
