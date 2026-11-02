plugins {
    id("hirari.android.library")
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(kotlin("test-junit"))
}

// `-PupdateGoldens=true` rewrites the layout snapshots under src/test/resources/golden instead of comparing them.
tasks.withType<Test>().configureEach {
    systemProperty("hirari.updateGoldens", providers.gradleProperty("updateGoldens").getOrElse("false"))
    outputs.upToDateWhen { providers.gradleProperty("updateGoldens").orNull != "true" }
}
