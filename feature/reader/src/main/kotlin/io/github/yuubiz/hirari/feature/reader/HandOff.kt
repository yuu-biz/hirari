package io.github.yuubiz.hirari.feature.reader

import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

private sealed interface Made<out O> {
    data object Nothing : Made<kotlin.Nothing>

    class Value<O>(val value: O) : Made<O>
}

/**
 * [convert] of [input] on [context], for values that must be released by hand. A caller cancelled before the
 * switch never runs [convert], and one cancelled during it loses the value `withContext` drops; either way the
 * value left over goes to its release before the cancellation goes on.
 */
internal suspend fun <I, O> handOff(
    context: CoroutineContext,
    input: I,
    releaseInput: (I) -> Unit,
    releaseOutput: (O) -> Unit,
    convert: (I) -> O,
): O {
    val made = AtomicReference<Made<O>>(Made.Nothing)
    try {
        return withContext(context) { convert(input).also { made.set(Made.Value(it)) } }
    } catch (e: CancellationException) {
        when (val left = made.get()) {
            Made.Nothing -> releaseInput(input)
            is Made.Value -> releaseOutput(left.value)
        }
        throw e
    }
}
