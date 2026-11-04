package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.data.BookOpening
import io.github.yuubiz.hirari.core.data.LaunchRepository
import io.github.yuubiz.hirari.core.data.OpeningBook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * The record of the book a reader opens (docs/spec.md section 6.1). [begin] writes it before the book opens. It ends
 * once the reader [shown] the book's content or why it did not open, or [left] before that. While the screen is
 * [stopped] it is ended too, since the process ending then is not the book's doing, and it is written again once the
 * screen [started]. The writes run on [scope], which outlives the reader, one after another in the order asked, so
 * leaving the screen neither cuts one short nor lets an end overtake its write. A write that fails is left out: the
 * record guards the next start, and the book opens without it. Main thread only.
 */
internal class OpeningRecord(scope: CoroutineScope, private val launch: LaunchRepository, private val book: OpeningBook) {
    private sealed interface Step {
        class Write(val written: CompletableDeferred<Unit>?) : Step

        class End(val shown: Boolean) : Step
    }

    private val steps = Channel<Step>(Channel.UNLIMITED)
    private var begun = false
    private var onScreen = true
    private var done = false

    /** Whether the last step asked writes the record. */
    private var recorded = false

    /** Whether any step asked writes it, so the end has a record to end. */
    private var written = false

    init {
        scope.launch {
            var last: BookOpening? = null
            for (step in steps) {
                try {
                    when (step) {
                        is Step.Write -> last = launch.beginOpening(book)
                        is Step.End -> last?.let { launch.endOpening(it, step.shown) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    (step as? Step.Write)?.written?.complete(Unit)
                }
            }
        }
    }

    /** Writes the record and returns once it is written or could not be, at once while stopped. Only the first call counts. */
    suspend fun begin() {
        if (begun || done) return
        begun = true
        val ack = CompletableDeferred<Unit>().takeIf { onScreen }
        converge(ack)
        ack?.await()
    }

    fun stopped() {
        onScreen = false
        converge()
    }

    fun started() {
        onScreen = true
        converge()
    }

    /** The reader showed the book's content or why it did not open, which also clears the book as the suspect. */
    fun shown() = finish(shown = true)

    fun left() = finish(shown = false)

    private fun converge(ack: CompletableDeferred<Unit>? = null) {
        val wanted = begun && onScreen && !done
        if (wanted == recorded) return
        recorded = wanted
        if (wanted) {
            written = true
            steps.trySend(Step.Write(ack))
        } else {
            steps.trySend(Step.End(shown = false))
        }
    }

    private fun finish(shown: Boolean) {
        if (done) return
        done = true
        recorded = false
        if (written) steps.trySend(Step.End(shown))
        steps.close()
    }
}
