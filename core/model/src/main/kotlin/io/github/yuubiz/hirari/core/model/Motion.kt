package io.github.yuubiz.hirari.core.model

/**
 * A value that runs linearly from [from] to [to] over [durationMs] from [startMs]. Turn effects
 * and moves between stop points share it, because both end at once when the next input comes
 * (docs/spec.md section 4.8, めくり効果 and 停止点).
 */
data class Motion(val startMs: Long, val durationMs: Long, val from: Float = 0f, val to: Float = 1f) {
    init {
        require(durationMs >= 0) { "durationMs $durationMs must not be negative" }
    }

    fun valueAt(nowMs: Long): Float {
        if (durationMs == 0L) return to
        val elapsed = ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)
        return from + (to - from) * elapsed
    }

    fun isDoneAt(nowMs: Long): Boolean = nowMs - startMs >= durationMs

    /** The motion as it is after an input at [nowMs]: already at its end. */
    fun interruptedAt(nowMs: Long): Motion = Motion(nowMs, 0, to, to)
}
