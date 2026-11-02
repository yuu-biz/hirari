plugins {
    id("hirari.jvm.library")
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.xz)
    implementation(libs.commons.compress)
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

// The standard corpus of docs/spec.md section 5.1 is about 350 MB, so it stays out of the corpus the device
// tests carry and is generated only when asked for.
tasks.register<JavaExec>("generateStandardCorpus") {
    val outputDir = layout.buildDirectory.dir("standard-corpus")
    outputs.dir(outputDir)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.yuubiz.hirari.tools.corpus.StandardCorpus"
    systemProperty("java.awt.headless", "true")
    argumentProviders += CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath) }
}

// The text corpus of docs/spec.md section 6.2 holds a 10 MB file, so it stays out of the corpus the archive device
// tests carry. :core:text reads it in its JVM tests.
tasks.register<JavaExec>("generateTextCorpus") {
    val outputDir = layout.buildDirectory.dir("text-corpus")
    outputs.dir(outputDir)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.yuubiz.hirari.tools.corpus.TextCorpus"
    argumentProviders += CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath) }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
}
