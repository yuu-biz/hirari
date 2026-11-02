package io.github.yuubiz.hirari.tools.fuzz

/** What one `monkey` session did to the app. */
data class MonkeyCounts(val injected: Int, val crashes: Int, val nativeCrashes: Int, val anrs: Int, val aborted: Boolean) {
    val failures: Int get() = crashes + nativeCrashes + anrs

    operator fun plus(other: MonkeyCounts) = MonkeyCounts(
        injected + other.injected, crashes + other.crashes, nativeCrashes + other.nativeCrashes, anrs + other.anrs, aborted || other.aborted,
    )

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
