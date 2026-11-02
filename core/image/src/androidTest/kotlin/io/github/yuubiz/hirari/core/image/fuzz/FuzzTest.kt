package io.github.yuubiz.hirari.core.image.fuzz

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yuubiz.hirari.core.image.ImageDecoding
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.SplittableRandom

/**
 * The device fuzzer of docs/spec.md section 6.2. It does nothing in a normal test run: both tests start only when
 * their instrumentation arguments are given, and `tools/fuzz` (`:tools:fuzz:fuzz`) does that and supervises them.
 *
 * `fuzz` runs inputs for `fuzzSeconds`. Input `n` is a function of `fuzzSeed` and `n` alone, so a run is repeated by
 * its seed and start. The input is written to `current.bin` before it is run, so that when libarchive or libjxl kill
 * the process the reproducer is already on disk, and `tools/fuzz` reads it from there. A run that takes longer than
 * `fuzzTimeoutSeconds` is stopped by a watchdog after it saves the stacks of all threads. Anything the code under test
 * throws is saved as a finding and the run goes on.
 *
 * `replay` runs each file of `fuzzReplayDir` in name order as the input of iteration `fuzzIteration`, which is how
 * `tools/fuzz` shrinks a reproducer without starting the process for every candidate.
 */
class FuzzTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val context = instrumentation.targetContext
    private val out = (context.getExternalFilesDir("fuzz") ?: File(context.filesDir, "fuzz")).apply { mkdirs() }
    private val findings = File(out, "findings").apply { mkdirs() }

    private val seed = arguments.getString("fuzzSeed")?.toLong() ?: 1L
    private val timeoutMillis = (arguments.getString("fuzzTimeoutSeconds")?.toDouble() ?: 20.0).times(1000).toLong()
    private val maxSeedBytes = arguments.getString("fuzzMaxSeedBytes")?.toInt() ?: (256 * 1024)
    private val maxInputBytes = arguments.getString("fuzzMaxInputBytes")?.toInt() ?: (512 * 1024)
    private val slowMillis = arguments.getString("fuzzSlowMillis")?.toLong() ?: 3000L
    private val targets = Target.parse(arguments.getString("fuzzTargets") ?: Target.defaults)

    private val counts = HashMap<String, Int>()
    private val signatures = HashSet<String>()
    private var slowSaved = 0

    @Test
    fun fuzz() {
        val seconds = arguments.getString("fuzzSeconds")?.toDouble()
        assumeTrue("fuzzSeconds is not given", seconds != null)
        val start = arguments.getString("fuzzStart")?.toLong() ?: 0L
        val limit = arguments.getString("fuzzMaxIterations")?.toLong() ?: Long.MAX_VALUE
        clearRunFiles()
        val seeds = Seeds(instrumentation.context.assets, maxSeedBytes)
        val runner = Runner(ImageDecoding.forDevice(context), File(context.cacheDir, "fuzz").apply { mkdirs() })
        val watchdog = Watchdog().apply { start() }
        val begun = System.nanoTime()
        var iteration = start
        var executed = 0L
        var slowest = 0L
        var statsAt = 0L
        val perTarget = HashMap<String, Long>()
        val perTargetMillis = HashMap<String, Long>()
        while (iteration - start < limit && (System.nanoTime() - begun) / 1e9 < seconds!!) {
            val target = targets[(iteration % targets.size).toInt()]
            val pool = seeds.of(target)
            val random = SplittableRandom(Mutator.mix(seed, iteration))
            val chosen = pool[random.nextInt(pool.size)]
            val input = if (random.nextInt(32) == 0) chosen.bytes else Mutator(random).mutate(chosen.bytes, pool[random.nextInt(pool.size)].bytes, maxInputBytes)
            val millis = execute(runner, watchdog, target, iteration, chosen.name, input)
            slowest = maxOf(slowest, millis)
            executed++
            perTarget.merge(target.id, 1L, Long::plus)
            perTargetMillis.merge(target.id, millis, Long::plus)
            iteration++
            val now = System.nanoTime()
            if (now - statsAt > 2_000_000_000L) {
                statsAt = now
                writeStats((now - begun) / 1_000_000, executed, iteration, slowest, perTarget, perTargetMillis)
            }
        }
        watchdog.stop = true
        writeStats((System.nanoTime() - begun) / 1_000_000, executed, iteration, slowest, perTarget, perTargetMillis)
        Log.i(TAG, "fuzzed $executed inputs from iteration $start with seed $seed, findings $counts")
        val failures = counts.filterKeys { it != "slow" }
        if (failures.isNotEmpty()) fail("fuzzing found $failures; see ${findings.absolutePath}")
    }

    @Test
    fun replay() {
        val directory = arguments.getString("fuzzReplayDir")
        assumeTrue("fuzzReplayDir is not given", directory != null)
        val target = Target.parse(arguments.getString("fuzzTarget") ?: error("fuzzTarget is needed")).single()
        val iteration = arguments.getString("fuzzIteration")?.toLong() ?: 0L
        clearRunFiles()
        val runner = Runner(ImageDecoding.forDevice(context), File(context.cacheDir, "fuzz").apply { mkdirs() })
        val watchdog = Watchdog().apply { start() }
        val files = File(directory!!).listFiles().orEmpty().sortedBy { it.name }
        for ((index, file) in files.withIndex()) {
            File(out, "replay-index.txt").writeText(index.toString())
            execute(runner, watchdog, target, iteration, file.name, file.readBytes())
        }
        watchdog.stop = true
        Log.i(TAG, "replayed ${files.size} inputs, findings $counts")
        if (counts.isNotEmpty()) fail("replay found $counts")
    }

    /** Runs [input] and returns the milliseconds it took. */
    private fun execute(runner: Runner, watchdog: Watchdog, target: Target, iteration: Long, from: String, input: ByteArray): Long {
        File(out, "current.txt").writeText("target=${target.id} seed=$seed iteration=$iteration from=$from bytes=${input.size}\n")
        File(out, "current.bin").writeBytes(input)
        val random = SplittableRandom(Mutator.mix(seed xor EXEC_STREAM, iteration))
        val started = System.nanoTime()
        watchdog.startedAt = started
        try {
            runner.run(target, input, random)
        } catch (e: Throwable) {
            watchdog.startedAt = 0
            record(if (e is OutOfMemoryError) "oom" else "exception", target, iteration, from, input, e.stackTraceToString(), e)
        }
        watchdog.startedAt = 0
        val millis = (System.nanoTime() - started) / 1_000_000
        if (millis > slowMillis && slowSaved < MAX_SLOW) {
            slowSaved++
            record("slow", target, iteration, from, input, "took $millis ms", null)
        }
        return millis
    }

    private fun record(kind: String, target: Target, iteration: Long, from: String, input: ByteArray, detail: String, error: Throwable?) {
        counts.merge(kind, 1, Int::plus)
        val frames = error?.stackTrace.orEmpty().take(4).joinToString("|") { "${it.className}.${it.methodName}:${it.lineNumber}" }
        val signature = "$kind ${error?.javaClass?.name} $frames ${target.id}"
        if (kind != "slow" && !signatures.add(signature)) return
        val stem = "$kind-${target.id}-s$seed-i$iteration-${hash(signature)}"
        File(findings, "$stem.bin").writeBytes(input)
        File(findings, "$stem.txt").writeText("target=${target.id} seed=$seed iteration=$iteration from=$from bytes=${input.size}\n$detail\n")
        Log.w(TAG, "$kind in ${target.id} at iteration $iteration: ${error?.javaClass?.name ?: detail}")
    }

    private fun writeStats(
        elapsedMillis: Long,
        executed: Long,
        nextIteration: Long,
        slowest: Long,
        perTarget: Map<String, Long>,
        perTargetMillis: Map<String, Long>,
    ) {
        File(out, "stats.txt").writeText(
            buildString {
                appendLine("seed=$seed")
                appendLine("elapsedMillis=$elapsedMillis")
                appendLine("executions=$executed")
                appendLine("nextIteration=$nextIteration")
                appendLine("slowestMillis=$slowest")
                for ((name, n) in perTarget) appendLine("executions.$name=$n")
                for ((name, n) in perTargetMillis) appendLine("millis.$name=$n")
                for ((name, n) in counts) appendLine("findings.$name=$n")
            },
        )
    }

    private fun clearRunFiles() {
        for (name in listOf("current.bin", "current.txt", "timeout.txt", "stats.txt", "replay-index.txt")) File(out, name).delete()
    }

    private fun hash(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(4).joinToString("") { "%02x".format(it) }

    /** Saves the stacks and ends the process when one input runs past the time limit. */
    private inner class Watchdog : Thread("fuzz-watchdog") {
        @Volatile var startedAt = 0L

        @Volatile var stop = false

        init {
            isDaemon = true
        }

        override fun run() {
            while (!stop) {
                sleep(250)
                val started = startedAt
                if (started != 0L && (System.nanoTime() - started) / 1_000_000 > timeoutMillis) {
                    File(out, "timeout.txt").writeText(
                        buildString {
                            appendLine("an input ran for more than $timeoutMillis ms; see current.txt")
                            for ((thread, frames) in getAllStackTraces()) {
                                appendLine("--- ${thread.name}")
                                frames.forEach { appendLine("    at $it") }
                            }
                        },
                    )
                    Process.killProcess(Process.myPid())
                }
            }
        }
    }

    private companion object {
        const val TAG = "HirariFuzz"
        const val EXEC_STREAM = 0x5deece66dL
        const val MAX_SLOW = 20
    }
}
