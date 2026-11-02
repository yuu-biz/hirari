plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.compiler.gradle.plugin)
    compileOnly(libs.licensee.gradle.plugin)
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
        register("jvmLibrary") {
            id = "hirari.jvm.library"
            implementationClass = "hirari.JvmLibraryPlugin"
        }
    }
}
