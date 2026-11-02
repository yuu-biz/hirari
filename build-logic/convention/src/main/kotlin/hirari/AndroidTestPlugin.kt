package hirari

import com.android.build.api.dsl.TestExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidTestPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("com.android.test")
        extensions.configure<TestExtension> {
            compileSdk = Sdk.COMPILE
            namespace = namespaceFor()
            defaultConfig.minSdk = Sdk.MIN
            defaultConfig.targetSdk = Sdk.TARGET
            compileOptions {
                sourceCompatibility = JAVA_TARGET
                targetCompatibility = JAVA_TARGET
            }
        }
        enforceModuleRules()
    }
}
