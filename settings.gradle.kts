pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Hirari"

include(
    ":app",
    ":core:model",
    ":core:source",
    ":core:archive",
    ":core:image",
    ":core:text",
    ":core:data",
    ":core:ui",
    ":feature:library",
    ":feature:reader",
    ":feature:text",
    ":feature:settings",
    ":tools:corpus",
)
