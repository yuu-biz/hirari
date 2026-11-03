package io.github.yuubiz.hirari.feature.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndAction
import io.github.yuubiz.hirari.core.model.BookEndEffect
import io.github.yuubiz.hirari.core.model.Sibling
import io.github.yuubiz.hirari.core.model.bookEndEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * What running into an end of the text asks of the screen (docs/spec.md sections 4.8 and 4.9). The library is asked
 * for the text beside the end once per edge, by [lookup] on [scope]; whoever waits for the answer, the sheet or an
 * effect, can go away without cancelling it.
 */
internal class BookEnds<F>(
    private val scope: CoroutineScope,
    private val lookup: suspend (BookEdge) -> Sibling<F>,
) {
    /** The effect of the end last run into, until the screen says it is handled. */
    var effect: BookEndEffect<F>? by mutableStateOf(null)
        private set

    private val lookups = HashMap<BookEdge, Deferred<Sibling<F>>>()
    private var asking: Job? = null

    suspend fun sibling(edge: BookEdge): Sibling<F> = lookups.getOrPut(edge) { scope.async { lookup(edge) } }.await()

    /** The text ran into [edge]; unless an effect is already on its way, [action] says what comes of it. */
    fun reached(edge: BookEdge, action: BookEndAction) {
        if (asking?.isActive == true || effect != null) return
        asking = scope.launch { effect = bookEndEffect(edge, action, ::sibling) }
    }

    /** The reader moved on, so an end waiting for the library no longer means anything. */
    fun pageActed() {
        asking?.cancel()
        asking = null
    }

    fun handled() {
        effect = null
    }
}
