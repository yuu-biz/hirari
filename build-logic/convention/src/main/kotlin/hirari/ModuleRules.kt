package hirari

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/** The dependency table of docs/spec.md section 8.2: which project modules each module may depend on. */
private val allowedDependencies: Map<String, (String) -> Boolean> = mapOf(
    ":core:model" to { _ -> false },
    ":core:source" to { it == ":core:model" },
    ":core:archive" to { it in setOf(":core:model", ":core:source") },
    ":core:image" to { it in setOf(":core:model", ":core:source", ":core:archive") },
    ":core:text" to { it == ":core:model" },
    ":core:data" to { it == ":core:model" },
    ":core:ui" to { it == ":core:model" },
    ":tools:corpus" to { it == ":core:model" },
    ":benchmark" to { it == ":app" },
    ":app" to { _ -> true },
)

private fun isAllowed(from: String, to: String): Boolean {
    if (from == to) return true
    if (from.startsWith(":feature:")) return to.startsWith(":core:")
    val rule = allowedDependencies[from]
        ?: throw GradleException("$from is not in the module table of docs/spec.md section 8.2")
    return rule(to)
}

internal fun Project.enforceModuleRules() {
    configurations.configureEach {
        dependencies.whenObjectAdded {
            if (this is ProjectDependency && !isAllowed(this@enforceModuleRules.path, path)) {
                throw GradleException(
                    "${this@enforceModuleRules.path} must not depend on $path (docs/spec.md section 8.2)",
                )
            }
        }
    }
}
