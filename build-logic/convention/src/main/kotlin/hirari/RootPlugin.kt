package hirari

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

class RootPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("base")
        val verify = tasks.register<VerifyThirdPartyTask>("verifyThirdParty") {
            thirdPartyDir.set(layout.projectDirectory.dir("third_party"))
            report.set(layout.buildDirectory.file("third-party/verified.txt"))
        }
        tasks.named("check") {
            dependsOn(verify)
            dependsOn(gradle.includedBuild("build-logic").task(":convention:check"))
        }
    }
}
