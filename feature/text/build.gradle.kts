plugins {
    id("hirari.android.feature")
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
    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
