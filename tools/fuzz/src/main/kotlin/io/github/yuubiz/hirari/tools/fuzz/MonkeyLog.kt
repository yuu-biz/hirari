package io.github.yuubiz.hirari.tools.fuzz

/**
 * What one `monkey` session did to the app. [abnormal] counts the sessions that did not end normally: see [abnormality].
 */
data class MonkeyCounts(
    val injected: Int,
    val crashes: Int,
    val nativeCrashes: Int,
    val anrs: Int,
    val aborted: Boolean,
    val abnormal: Int = 0,
) {
    val failures: Int get() = crashes + nativeCrashes + anrs

    /** Whether the test run these counts belong to has to fail. */
    val failed: Boolean get() = failures > 0 || aborted || abnormal > 0

    operator fun plus(other: MonkeyCounts) = MonkeyCounts(
        injected + other.injected, crashes + other.crashes, nativeCrashes + other.nativeCrashes, anrs + other.anrs,
        aborted || other.aborted, abnormal + other.abnormal,
    )

    /**
     * Why a session that asked `monkey` for [requested] events, and ended as [end], does not count as a normal one, or
     * null. A hang that `adb` gave up on prints none of the lines that [of] looks for, so without this it would read
     * as a quiet session with no events.
     */
    fun abnormality(requested: Int, end: AdbResult): String? = when {
        end.timedOut -> "monkey did not end in time and was stopped"
        end.exit != 0 -> "monkey ended with status ${end.exit}"
        injected != requested -> "monkey injected $injected of the $requested events asked for"
        else -> null
    }

    companion object {
        val None = MonkeyCounts(0, 0, 0, 0, false)

        /**
         * The crashes and ANRs of [packageName] in a session, from `monkey`'s own output and the logcat of the session.
         * Each kind takes the larger of the two counts, because `monkey` prints a line for each crash it sees, and the
         * log has one for each crash that happened, including those that came while `monkey` looked away.
         */
        fun of(packageName: String, monkeyOutput: String, logcat: String): MonkeyCounts {
            val lines = monkeyOutput.lines()
            val fromMonkey = lines.count { it.startsWith("// CRASH:") && packageName in it }
            val nativeFromMonkey = lines.count { it.startsWith("// NATIVE CRASH") || it.startsWith("// Native crash") }
            val anrFromMonkey = lines.count { it.startsWith("// NOT RESPONDING:") && packageName in it }
            val log = logcat.lines()
            val fromLog = log.count { "AndroidRuntime" in it && Regex("""Process: ${Regex.escape(packageName)}, PID""").containsMatchIn(it) }
            val nativeFromLog = log.count { line ->
                "Fatal signal" in line && Regex("""pid \d+ \(([^)]+)\)""").find(line)?.groupValues?.get(1)
                    ?.let { comm -> comm.length >= MIN_COMM_LENGTH && packageName.endsWith(comm) } == true
            }
            val anrFromLog = log.count { Regex("""ANR in ${Regex.escape(packageName)}\b""").containsMatchIn(it) }
            val injected = Regex("""Events injected: (\d+)""").find(monkeyOutput)?.groupValues?.get(1)?.toInt() ?: 0
            return MonkeyCounts(
                injected,
                // A native crash also prints a CRASH line in the output of some versions, so the Java count leaves them out.
                maxOf(fromMonkey - nativeFromMonkey, fromLog),
                maxOf(nativeFromMonkey, nativeFromLog),
                maxOf(anrFromMonkey, anrFromLog),
                "Monkey aborted" in monkeyOutput,
            )
        }

        /** The kernel keeps the last 15 characters of a process name in `comm`, which the tombstone prints. */
        private const val MIN_COMM_LENGTH = 8
    }
}
