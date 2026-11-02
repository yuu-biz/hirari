plugins {
    id("hirari.jvm.library")
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

tasks.register<JavaExec>("generateCorpus") {
    val outputDir = layout.buildDirectory.dir("corpus")
    outputs.dir(outputDir)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "io.github.yuubiz.hirari.tools.corpus.CorpusKt"
    argumentProviders += CommandLineArgumentProvider { listOf(outputDir.get().asFile.absolutePath) }
}
