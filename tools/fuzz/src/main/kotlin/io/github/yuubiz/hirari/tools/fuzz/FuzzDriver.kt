package io.github.yuubiz.hirari.tools.fuzz

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/** What a [FuzzDriver] run settings are. [targets] is the harness's `fuzzTargets`. */
class FuzzOptions(
    val minutes: Double,
    val seed: Long,
    val targets: String,
    val timeoutSeconds: Int,
    val out: File,
    val maxCrashes: Int,
    val minimizeRounds: Int,
)

/** One reproducer the run saved. [file] is the smallest input that still fails, or the original. */
class Reproducer(val kind: String, val target: String, val iteration: Long, val signature: String, val file: File, val originalBytes: Int, val minimized: Boolean)

class FuzzReport(
    val seconds: Double,
    val launches: Int,
    val executions: Long,
    val reproducers: List<Reproducer>,
    val exceptions: Int,
    val slow: Int,
    val nextIteration: Long,
    val harnessError: String? = null,
    /** Inputs per second of each target while it ran, for example `archive 900/s, image 400/s`. */
    val rates: String = "",
) {
    /** A crash, a hang or an exception the code under test did not handle. Slow inputs alone are not. */
    val failed: Boolean get() = reproducers.isNotEmpty() || exceptions > 0 || harnessError != null
}

/**
 * Runs the device fuzzer (core/image `FuzzTest`) for a time budget, and picks up what it leaves. The harness
 * runs inside the instrumented process, so a native crash ends the run. The driver reads the input that was running
 * from the file the harness wrote before it ran it, saves it with the crash log, shrinks it with the harness's
 * `replay` test, and starts again at the next iteration of the same seed.
 */
class FuzzDriver(private val adb: Adb, private val options: FuzzOptions, private val log: (String) -> Unit = ::println) {
    private val device = "/sdcard/Android/data/$PACKAGE/files"
    private val reproducers = ArrayList<Reproducer>()
    private val seen = HashSet<String>()
    private var exceptions = 0
    private var slow = 0
    private var harnessError: String? = null
    private val perTarget = HashMap<String, Long>()

    fun run(): FuzzReport {
        waitForBoot()
        val started = System.nanoTime()
        val budgetNanos = (options.minutes * 60e9).toLong()
        var next = 0L
        var launches = 0
        var executions = 0L
        var crashes = 0
        while (true) {
            val left = budgetNanos - (System.nanoTime() - started)
            if (left < 3_000_000_000L || crashes >= options.maxCrashes) break
            launches++
            adb.shell("rm -rf $device/fuzz")
            adb.clearLogcat()
            val seconds = left / 1e9
            val command = "am instrument -w -e class $CLASS#fuzz -e fuzzSeconds ${"%.1f".format(seconds)} -e fuzzSeed ${options.seed} " +
                "-e fuzzStart $next -e fuzzTargets ${options.targets} -e fuzzTimeoutSeconds ${options.timeoutSeconds} $PACKAGE/$RUNNER"
            val output = adb.shell(command, timeoutSeconds = (seconds + options.timeoutSeconds + 120).toLong())
            val end = RunEnd.of(output)
            val stats = readStats()
            for ((key, value) in stats) {
                if (key.startsWith("executions.") || key.startsWith("millis.")) perTarget.merge(key, value, Long::plus)
            }
            collectFindings()
            if (end != RunEnd.Died) {
                executions += stats["executions"] ?: 0
                next = stats["nextIteration"] ?: next
                log("launch $launches ended: $end, ${stats["executions"] ?: "?"} inputs")
                if (end == RunEnd.Failed) {
                    log(output.lines().take(12).joinToString("\n"))
                    if (exceptions == 0) harnessError = "the fuzz test failed without a finding, so the harness itself is broken"
                }
                break
            }
            crashes++
            val current = readCurrent()
            if (current == null) {
                log("the process died and left no current input; output:\n$output")
                harnessError = "the process died before it wrote an input"
                break
            }
            executions += current.iteration - next + 1
            next = current.iteration + 1
            handleDeath(current, crashes)
        }
        val elapsed = (System.nanoTime() - started) / 1e9
        val rates = perTarget.keys.filter { it.startsWith("executions.") }.sorted().joinToString(", ") { key ->
            val name = key.removePrefix("executions.")
            val millis = perTarget["millis.$name"] ?: 0
            "$name %.0f/s".format(perTarget.getValue(key) * 1000.0 / maxOf(millis, 1))
        }
        return FuzzReport(elapsed, launches, executions, reproducers, exceptions, slow, next, harnessError, rates)
    }

    /** Runs one saved reproducer as iteration [iteration] of the harness and says whether it kills the process. */
    fun replay(input: ByteArray, target: String, iteration: Long): String {
        waitForBoot()
        val died = replayUntilDeath(Current(target, iteration, "replay", input, null), listOf(input), 0)
        if (died == null) return "the process survived"
        return if (pullText("$device/fuzz/timeout.txt") != null) "timeout" else "crash: ${CrashSignature.of(adb.logcat("crash"))}"
    }

    private class Current(val target: String, val iteration: Long, val from: String, val bytes: ByteArray, val timeout: String?)

    private fun readCurrent(): Current? {
        val text = pullText("$device/fuzz/current.txt") ?: return null
        val fields = text.trim().split(' ').mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }.associate { it[0] to it[1] }
        val file = File(options.out, "tmp/current.bin").also { it.delete() }
        if (!adb.pull("$device/fuzz/current.bin", file)) return null
        val bytes = file.readBytes()
        return Current(fields["target"] ?: return null, fields["iteration"]?.toLongOrNull() ?: return null, fields["from"].orEmpty(), bytes, pullText("$device/fuzz/timeout.txt"))
    }

    private fun handleDeath(current: Current, number: Int) {
        val kind = if (current.timeout != null) "timeout" else "crash"
        val crashLog = adb.logcat("crash")
        val signature = if (kind == "crash") CrashSignature.of(crashLog) else CrashSignature("timeout", listOf(current.target))
        val stem = "$kind-${current.target}-s${options.seed}-i${current.iteration}"
        val dir = File(options.out, "reproducers").apply { mkdirs() }
        val original = File(dir, "$stem.bin").also { it.writeBytes(current.bytes) }
        File(dir, "$stem.logcat.txt").writeText(crashLog)
        current.timeout?.let { File(dir, "$stem.stacks.txt").writeText(it) }
        log("$kind #$number in ${current.target} at iteration ${current.iteration} (${current.bytes.size} bytes from ${current.from}): $signature")
        var saved = original
        var minimized = false
        if (options.minimizeRounds > 0 && seen.add(signature.toString())) {
            val smaller = shrink(current, kind, signature)
            if (smaller != null && smaller.size < current.bytes.size) {
                saved = File(dir, "$stem.min.bin").also { it.writeBytes(smaller) }
                minimized = true
                log("  shrunk ${current.bytes.size} to ${smaller.size} bytes: ${saved.path}")
            }
        }
        File(dir, "$stem.txt").writeText(
            "kind=$kind target=${current.target} seed=${options.seed} iteration=${current.iteration} from=${current.from}\n" +
                "signature=$signature\noriginal=${original.name} bytes=${current.bytes.size}\nreproducer=${saved.name}\n" +
                "replay: ./gradlew :tools:fuzz:fuzz --args=\"--replay ${saved.path} --target ${current.target} --seed ${options.seed} --iteration ${current.iteration}\"\n",
        )
        reproducers += Reproducer(kind, current.target, current.iteration, signature.toString(), saved, current.bytes.size, minimized)
    }

    /** The smallest input found that dies the same way, or null when [current] does not die alone. */
    private fun shrink(current: Current, kind: String, signature: CrashSignature): ByteArray? {
        fun diesTheSame(candidates: List<ByteArray>): Int? {
            var offset = 0
            while (offset < candidates.size) {
                val index = replayUntilDeath(current, candidates, offset) ?: return null
                val timeout = pullText("$device/fuzz/timeout.txt") != null
                val sameKind = timeout == (kind == "timeout")
                val sameCrash = kind == "timeout" || CrashSignature.of(adb.logcat("crash")).let { it.signal == signature.signal && it.frames.firstOrNull() == signature.frames.firstOrNull() }
                if (sameKind && sameCrash) return index
                offset = index + 1
            }
            return null
        }
        if (diesTheSame(listOf(current.bytes)) == null) {
            log("  it does not die when run alone, so it is kept as it is")
            return null
        }
        return Minimizer(maxRounds = options.minimizeRounds, firstFailing = ::diesTheSame).minimize(current.bytes)
    }

    /** Runs candidates from [offset] in one process. Returns the index of the one that killed it, or null. */
    private fun replayUntilDeath(current: Current, candidates: List<ByteArray>, offset: Int): Int? {
        val local = File(options.out, "tmp/replay").apply { deleteRecursively(); mkdirs() }
        for (i in offset until candidates.size) File(local, "%06d".format(i - offset)).writeBytes(candidates[i])
        adb.shell("rm -rf $device/fuzz-replay; mkdir -p $device/fuzz-replay")
        adb.push(File(local.path, "."), "$device/fuzz-replay/")
        adb.clearLogcat()
        val output = adb.shell(
            "am instrument -w -e class $CLASS#replay -e fuzzReplayDir $device/fuzz-replay -e fuzzTarget ${current.target} " +
                "-e fuzzSeed ${options.seed} -e fuzzIteration ${current.iteration} -e fuzzTimeoutSeconds ${options.timeoutSeconds} $PACKAGE/$RUNNER",
            timeoutSeconds = (candidates.size - offset) * options.timeoutSeconds.toLong() + 120,
        )
        if (RunEnd.of(output) != RunEnd.Died) return null
        val index = pullText("$device/fuzz/replay-index.txt")?.trim()?.toIntOrNull() ?: return null
        return offset + index
    }

    private fun collectFindings() {
        val local = File(options.out, "tmp/findings").apply { deleteRecursively() }
        val dir = File(options.out, "findings").apply { mkdirs() }
        if (adb.pull("$device/fuzz/findings", local.parentFile)) {
            for (file in local.listFiles().orEmpty()) {
                Files.move(file.toPath(), File(dir, file.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        exceptions = dir.list().orEmpty().count { it.endsWith(".bin") && (it.startsWith("exception-") || it.startsWith("oom-")) }
        slow = dir.list().orEmpty().count { it.endsWith(".bin") && it.startsWith("slow-") }
    }

    private fun readStats(): Map<String, Long> = (pullText("$device/fuzz/stats.txt") ?: "").lines()
        .mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
        .mapNotNull { (k, v) -> v.toLongOrNull()?.let { k to it } }
        .toMap()

    private fun pullText(remote: String): String? {
        val file = File(options.out, "tmp/${remote.substringAfterLast('/')}")
        file.delete()
        return if (adb.pull(remote, file)) file.readText() else null
    }

    private fun waitForBoot() {
        repeat(60) {
            if (adb.shell("getprop sys.boot_completed").trim() == "1") return
            Thread.sleep(3000)
        }
        error("no device finished booting; start an emulator, or give --serial when several devices are attached")
    }

    companion object {
        const val PACKAGE = "io.github.yuubiz.hirari.core.image.test"
        const val RUNNER = "androidx.test.runner.AndroidJUnitRunner"
        const val CLASS = "io.github.yuubiz.hirari.core.image.fuzz.FuzzTest"

        /** One line of the time log, which `Fuzz` sums to report the accumulated time (docs/spec.md section 6.2). */
        fun timeLogLine(report: FuzzReport, options: FuzzOptions, commit: String, now: Instant = Instant.now()): String =
            "$now seed=${options.seed} seconds=${"%.1f".format(report.seconds)} executions=${report.executions} nextIteration=${report.nextIteration} " +
                "crashes=${report.reproducers.count { it.kind == "crash" }} timeouts=${report.reproducers.count { it.kind == "timeout" }} " +
                "exceptions=${report.exceptions} slow=${report.slow} commit=$commit"

        /** The seconds summed over the lines of a time log. */
        fun accumulatedSeconds(log: String): Double =
            log.lines().sumOf { line -> Regex("""\bseconds=([0-9.]+)""").find(line)?.groupValues?.get(1)?.toDouble() ?: 0.0 }
    }
}
