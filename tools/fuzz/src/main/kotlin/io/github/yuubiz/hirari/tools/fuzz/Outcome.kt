package io.github.yuubiz.hirari.tools.fuzz

/** How one `am instrument` run of the fuzz test ended. */
enum class RunEnd {
    /** The test ran to the end and passed. */
    Passed,

    /** The test ran to the end and failed, because the code under test threw. */
    Failed,

    /** The process died before the test ended: a native crash, or the watchdog ended an input that hung. */
    Died,
    ;

    companion object {
        fun of(instrumentOutput: String): RunEnd = when {
            "FAILURES!!!" in instrumentOutput -> Failed
            Regex("""OK \(\d+ tests?\)""").containsMatchIn(instrumentOutput) -> Passed
            else -> Died
        }
    }
}

/** A native crash as the `crash` log buffer shows it, reduced to what tells two crashes apart. */
data class CrashSignature(val signal: String, val frames: List<String>) {
    override fun toString(): String = (listOf(signal) + frames).joinToString(" < ")

    companion object {
        val Unknown = CrashSignature("unknown", emptyList())

        /** The first `Fatal signal` block of [log], or [Unknown]. Frames are function names without offsets. */
        fun of(log: String): CrashSignature {
            val lines = log.lines()
            val at = lines.indexOfFirst { "Fatal signal" in it }
            if (at < 0) return Unknown
            val signal = Regex("""Fatal signal \d+ \((\w+)""").find(lines[at])?.groupValues?.get(1) ?: "signal"
            val frames = lines.drop(at).mapNotNull { FRAME.find(it)?.let { m -> m.groupValues[2].ifEmpty { m.groupValues[1] } } }
            return CrashSignature(signal, frames.take(4))
        }

        // "#00 pc 0000000000012345  /data/app/.../libhirari_archive.so (archive_read_next_header+123) (BuildId: ...)"
        private val FRAME = Regex("""#\d+ pc [0-9a-f]+\s+\S*?([^/\s]+\.so)(?: \(offset [^)]*\))?(?: \(([^)+]+)(?:\+\d+)?\))?""")
    }
}
