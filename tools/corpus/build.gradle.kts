plugins {
    id("hirari.jvm.library")
}

dependencies {
    implementation(project(":core:model"))
    testImplementation(kotlin("test-junit"))
}

tasks.register<JavaExec>("generateCorpus") {
    val outputDir = layout.buildDirectory.dir("corpus")
    outputs.dir(outputDir)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.yuubiz.hirari.tools.corpus.CorpusKt"
    systemProperty("java.awt.headless", "true")
    argumentProviders += CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath) }
}

// The standard corpus of docs/spec.md section 5.1 is about 300 MB, so it stays out of the corpus the device
// tests carry and is generated only when asked for.
tasks.register<JavaExec>("generateStandardCorpus") {
    val outputDir = layout.buildDirectory.dir("standard-corpus")
    outputs.dir(outputDir)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.yuubiz.hirari.tools.corpus.StandardCorpus"
    systemProperty("java.awt.headless", "true")
    argumentProviders += CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath) }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
