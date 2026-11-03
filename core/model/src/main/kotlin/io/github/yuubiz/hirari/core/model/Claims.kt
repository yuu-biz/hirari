package io.github.yuubiz.hirari.core.model

/**
 * One thing of the whole screen that a screen holds while it is up, such as the activity's keys or the window's
 * system bars. A screen that replaces another comes in while the other is still going out, so the old one lets go
 * after the new one has claimed. The newest claim still held decides; letting go of an older one changes nothing.
 * [apply] gets the value that decides each time it changes, and `null` once nothing holds a claim. Used from one
 * thread, the main one.
 */
class Claims<T : Any>(private val apply: (T?) -> Unit = {}) {
    private val held = ArrayList<Claim>()

    /** The value of the newest claim still held. */
    val current: T? get() = held.lastOrNull()?.value

    fun claim(value: T): Claim = changing { Claim(value).also { held += it } }

    inner class Claim internal constructor(value: T) {
        var value: T = value
            set(value) = changing { field = value }

        /** Lets go of the claim. Once let go, it stays so. */
        fun release() = changing { held.remove(this) }
    }

    private fun <R> changing(change: () -> R): R {
        val before = current
        return change().also { if (current != before) apply(current) }
    }
}
