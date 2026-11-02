plugins {
    id("hirari.android.library")
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(kotlin("test-junit"))
}

// The JVM tests read the text corpus. A task dependency keeps :tools:corpus out of the module dependencies, which
// docs/spec.md section 8.2 does not allow here.
evaluationDependsOn(":tools:corpus")

tasks.withType<Test>().configureEach {
    val corpus = project(":tools:corpus").tasks.named("generateTextCorpus")
    inputs.files(corpus).withPropertyName("textCorpus")
    systemProperty("hirari.textCorpus", corpus.get().outputs.files.singleFile.absolutePath)
    // `-PupdateGoldens=true` rewrites the layout snapshots under src/test/resources/golden instead of comparing them.
    systemProperty("hirari.updateGoldens", providers.gradleProperty("updateGoldens").getOrElse("false"))
    outputs.upToDateWhen { providers.gradleProperty("updateGoldens").orNull != "true" }
}
