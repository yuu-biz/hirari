package hirari

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

internal object Sdk {
    const val COMPILE = 37
    const val TARGET = 37
    const val MIN = 28

    /** docs/spec.md section 8.1. */
    val ABIS = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
}

internal val JAVA_TARGET = JavaVersion.VERSION_17

internal fun Project.checkAndroidTestCompilation() {
    if (!file("src/androidTest").isDirectory) return
    val compilation = tasks.matching { it.name == "compileDebugAndroidTestKotlin" }
    tasks.named("check").configure { dependsOn(compilation) }
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.namespaceFor(): String =
    "io.github.yuubiz.hirari" + path.replace(':', '.').replace('-', '_')

internal fun ApplicationExtension.configureCommon() {
    compileSdk = Sdk.COMPILE
    defaultConfig.minSdk = Sdk.MIN
    defaultConfig.ndk.abiFilters += Sdk.ABIS
    compileOptions {
        sourceCompatibility = JAVA_TARGET
        targetCompatibility = JAVA_TARGET
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

internal fun LibraryExtension.configureCommon() {
    compileSdk = Sdk.COMPILE
    defaultConfig.minSdk = Sdk.MIN
    defaultConfig.ndk.abiFilters += Sdk.ABIS
    compileOptions {
        sourceCompatibility = JAVA_TARGET
        targetCompatibility = JAVA_TARGET
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}
