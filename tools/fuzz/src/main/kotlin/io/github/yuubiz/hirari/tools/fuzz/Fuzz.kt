package io.github.yuubiz.hirari.tools.fuzz

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """
Usage: ./gradlew :tools:fuzz:fuzz --args="[options]"
  --minutes N            time budget of this run (default 5)
  --seed N               seed of the input stream (default 1); input n of a seed is always the same
  --targets a,b          archive, book, image, jxl (default all four, in turn)
  --timeout-seconds N    an input that runs longer is a hang (default 20)
  --out DIR              reproducers, findings and logs (default tools/fuzz/build/fuzz)
  --time-log FILE        append this run to a log of fuzzing time and print the total of the log
  --goal-hours N         the total to reach, for the remaining time that is printed (default 24)
  --max-crashes N        stop after this many crashes and hangs (default 20)
  --minimize-rounds N    most rounds spent shrinking one reproducer, 0 to keep them whole (default 40)
  --serial S             the device, when more than one is attached
  --apk FILE             the :core:image test APK (default the one :core:image:assembleDebugAndroidTest builds)
  --replay FILE --target T [--seed N --iteration N]   run one reproducer and say whether it kills the process
"""

private val KNOWN = setOf(
    "minutes", "seed", "targets", "timeout-seconds", "out", "time-log", "goal-hours", "max-crashes", "minimize-rounds", "serial", "apk",
    "replay", "target", "iteration",
)

private const val DEFAULT_APK = "core/image/build/intermediates/apk/androidTest/debug/image-debug-androidTest.apk"

fun main(args: Array<String>) {
    val arguments = Arguments(args)
    if (arguments.flag("help") || arguments.unknown(KNOWN + "help").isNotEmpty()) {
        println(USAGE)
        exitProcess(if (arguments.flag("help")) 0 else 2)
    }
    val adb = ProcessAdb.locate(arguments.string("serial"))
    val apk = File(arguments.string("apk", DEFAULT_APK))
    check(apk.isFile) { "no test APK at ${apk.path}; run ./gradlew :core:image:assembleDebugAndroidTest" }
    val options = FuzzOptions(
        minutes = arguments.double("minutes", 5.0),
        seed = arguments.long("seed", 1),
        targets = arguments.string("targets", "archive,book,image,jxl"),
        timeoutSeconds = arguments.int("timeout-seconds", 20),
        out = File(arguments.string("out", "tools/fuzz/build/fuzz")).absoluteFile,
        maxCrashes = arguments.int("max-crashes", 20),
        minimizeRounds = arguments.int("minimize-rounds", 40),
    )
    adb.install(apk)
    val driver = FuzzDriver(adb, options)
    arguments.string("replay")?.let { replay ->
        val target = arguments.string("target") ?: error("--replay needs --target")
        println(driver.replay(File(replay).readBytes(), target, arguments.long("iteration", 0)))
        return
    }
    options.out.mkdirs()
    val report = driver.run()
    val summary = buildString {
        appendLine("fuzzed %.1f s in %d launches, %d inputs, seed %d, next iteration %d".format(report.seconds, report.launches, report.executions, options.seed, report.nextIteration))
        appendLine("%.0f inputs per second (%s)".format(report.executions / maxOf(report.seconds, 1.0), report.rates))
        appendLine("crashes ${report.reproducers.count { it.kind == "crash" }}, hangs ${report.reproducers.count { it.kind == "timeout" }}, uncaught exceptions ${report.exceptions}, slow inputs ${report.slow}")
        report.harnessError?.let { appendLine("ERROR: $it") }
        for (r in report.reproducers) appendLine("  ${r.kind} in ${r.target} at iteration ${r.iteration}: ${r.signature}; ${r.file.path} (${r.file.length()} of ${r.originalBytes} bytes)")
    }
    print(summary)
    File(options.out, "summary.txt").writeText(summary)
    arguments.string("time-log")?.let { path ->
        val file = File(path).absoluteFile.also { it.parentFile.mkdirs() }
        file.appendText(FuzzDriver.timeLogLine(report, options, commit()) + "\n")
        val total = FuzzDriver.accumulatedSeconds(file.readText())
        val goal = arguments.double("goal-hours", 24.0) * 3600
        val line = "accumulated fuzzing time %.2f h of %.0f h, %.2f h left".format(total / 3600, goal / 3600, maxOf(goal - total, 0.0) / 3600)
        println(line)
        File(options.out, "summary.txt").appendText(line + "\n")
    }
    exitProcess(if (report.failed) 1 else 0)
}

private fun commit(): String = try {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start()
    process.inputStream.bufferedReader().readText().trim().ifEmpty { "unknown" }
} catch (_: Exception) {
    "unknown"
}
