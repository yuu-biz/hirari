plugins {
    id("hirari.jvm.library")
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

// Both tasks drive a device or an emulator over adb (docs/spec.md section 6.2). Arguments follow --args, for example
//   ./gradlew :tools:fuzz:fuzz --args="--minutes 10 --seed 1"
// -Pandroid.injected.build.abi=x86_64 builds the APKs for the emulator's ABI only, which is much faster.
fun adbTask(name: String, mainClass: String, vararg builds: String) = tasks.register<JavaExec>(name) {
    classpath = sourceSets.main.get().runtimeClasspath
    this.mainClass = mainClass
    dependsOn(*builds)
    workingDir = rootProject.projectDir
}

adbTask("fuzz", "io.github.yuubiz.hirari.tools.fuzz.FuzzKt", ":core:image:assembleDebugAndroidTest")
adbTask("monkey", "io.github.yuubiz.hirari.tools.fuzz.MonkeyKt", ":app:assembleDebug", ":tools:corpus:generateCorpus")
