package io.github.yuubiz.hirari

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
