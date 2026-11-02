plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    compileOnly(libs.licensee.gradle.plugin)
    implementation(libs.tomlj)
    testImplementation(kotlin("test-junit"))
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "hirari.android.application"
            implementationClass = "hirari.AndroidApplicationPlugin"
        }
        register("androidLibrary") {
            id = "hirari.android.library"
            implementationClass = "hirari.AndroidLibraryPlugin"
        }
        register("androidCompose") {
            id = "hirari.android.compose"
            implementationClass = "hirari.AndroidComposePlugin"
        }
        register("androidFeature") {
            id = "hirari.android.feature"
            implementationClass = "hirari.AndroidFeaturePlugin"
        }
        register("androidTest") {
            id = "hirari.android.test"
            implementationClass = "hirari.AndroidTestPlugin"
        }
        register("root") {
            id = "hirari.root"
            implementationClass = "hirari.RootPlugin"
        }
        register("jvmLibrary") {
            id = "hirari.jvm.library"
            implementationClass = "hirari.JvmLibraryPlugin"
        }
    }
}
