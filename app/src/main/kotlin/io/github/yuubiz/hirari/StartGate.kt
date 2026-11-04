package io.github.yuubiz.hirari

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/**
 * When a start that has shown its content counts as finished (docs/spec.md section 4.3, 起動時の動作): once the app
 * has stayed up for [SURVIVAL_MILLIS] after it, or as soon as the activity is stopped, whichever comes first. A
 * process that dies in between, as while it makes the covers of the folder it opened, counts as an unfinished start.
 * A stop is not a death: the user left, or the system took the screen. Main thread only.
 */
internal class StartWatch(private val scope: CoroutineScope, private val finish: () -> Unit) {
    private val stopped = MutableStateFlow(false)
    private var watching: Job? = null

    fun settled() {
        if (watching != null) return
        watching = scope.launch {
            withTimeoutOrNull(SURVIVAL_MILLIS) { stopped.first { it } }
            finish()
        }
    }

    /** The activity was stopped, other than to be made again for a new configuration, or started again. */
    fun stopped(now: Boolean) {
        stopped.value = now
    }

    companion object {
        const val SURVIVAL_MILLIS = 30_000L
    }
}

/** What a restoring start does with the screens the system kept (docs/spec.md section 4.3, 起動時の動作). */
private enum class Verdict { Pending, Adopted, Discarded }

/**
 * The screens the system kept, which wait until the start's plan adopts or discards them: a kept folder or book
 * that made the app fail must not load before then. A discarded screen never loads, even while it is composed
 * to leave. Screens are known by back stack entry id. Main thread only.
 */
internal class KeptScreens(restoring: Boolean) {
    private var verdict by mutableStateOf(if (restoring) Verdict.Pending else Verdict.Adopted)

    /** The screens shown before the verdict; only screens the system kept are on the back stack then. */
    private val restored = HashSet<String>()

    /** Whether the screen of [entry] may load now. A screen asking before the verdict is one the system kept. */
    fun loads(entry: String): Boolean = when (verdict) {
        Verdict.Pending -> {
            restored += entry
            false
        }
        Verdict.Adopted -> true
        Verdict.Discarded -> entry !in restored
    }

    fun decide(adopt: Boolean) {
        if (verdict == Verdict.Pending) verdict = if (adopt) Verdict.Adopted else Verdict.Discarded
    }
}
