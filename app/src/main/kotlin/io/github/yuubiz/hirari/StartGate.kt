package io.github.yuubiz.hirari

/** How the activity came up, which says whether it is a start (docs/spec.md section 4.3, 起動時の動作). */
internal enum class StartKind {
    /** Nothing kept: a start that plans what to open. */
    Fresh,

    /** The process ended and the system brings back the screens it kept: a start that keeps them. */
    Restored,

    /** Rebuilt in the same process, as on rotation: the start in progress goes on. */
    Recreated,
}

/** [savedState] is whether the system handed back the activity's state; [startInProcess], whether this process began a start. */
internal fun startKind(savedState: Boolean, startInProcess: Boolean): StartKind = when {
    !savedState -> StartKind.Fresh
    !startInProcess -> StartKind.Restored
    else -> StartKind.Recreated
}

/**
 * When a start finishes (docs/spec.md section 4.3, 起動時の動作): once its plan is applied and the screen on top
 * has shown its content. Screens report by back stack entry id; the top is passed in, since the user may move on
 * before the planned screen settles. Main thread only.
 */
internal class StartGate(private val finish: () -> Unit) {
    private val settled = HashSet<String>()
    private var applied = false

    var done = false
        private set

    fun settled(entry: String, top: String?) {
        settled += entry
        check(top)
    }

    fun applied(top: String?) {
        applied = true
        check(top)
    }

    fun check(top: String?) {
        if (done || !applied || top == null || top !in settled) return
        done = true
        finish()
    }
}
