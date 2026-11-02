package io.github.yuubiz.hirari.core.image.fuzz

/** One input that is being run: the text of `current.txt`, the bytes of `current.bin`, and when it began. */
class RunningInput(val startedAtNanos: Long, val description: String, val bytes: ByteArray)

/**
 * What the runner and the watchdog of [FuzzTest] agree on. The watchdog judges an input only by the one that the
 * runner has registered and that is still running, and it does so under the same lock that the runner takes to move
 * on, so it cannot judge input B by the start time it read for input A. Once the watchdog [claim]s an input, the
 * runner is refused its next [begin] and [end], and `current.txt`, `current.bin` and `timeout.txt` stay those of the
 * claimed input until the process is killed.
 */
class InputGuard {
    private var running: RunningInput? = null
    private var claimed = false

    /** Registers [input] and calls [save] with it, under the lock. Returns false when the watchdog has claimed an input. */
    @Synchronized
    fun begin(input: RunningInput, save: (RunningInput) -> Unit): Boolean {
        if (claimed) return false
        save(input)
        running = input
        return true
    }

    /** Marks the registered input as done. Returns false when the watchdog has claimed it first. */
    @Synchronized
    fun end(): Boolean {
        if (claimed) return false
        running = null
        return true
    }

    /** Takes the running input when it began more than [limitNanos] before [nowNanos]; null when none is overdue. */
    @Synchronized
    fun claim(nowNanos: Long, limitNanos: Long): RunningInput? {
        val input = running ?: return null
        if (nowNanos - input.startedAtNanos <= limitNanos) return null
        claimed = true
        running = null
        return input
    }
}
