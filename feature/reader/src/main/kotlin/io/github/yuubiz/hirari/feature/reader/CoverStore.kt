package io.github.yuubiz.hirari.feature.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * 表紙にする on this scope (docs/spec.md section 4.8): runs [store], and once it says the cover is stored, makes the
 * thumbnails again with [remake] on the same scope. The result is whether it was stored; a [store] that throws did
 * not store. Both steps belong to this scope, so a caller that stops waiting, such as a screen that closed, does not
 * skip the thumbnails. The result does not wait for [remake].
 */
internal fun CoroutineScope.storeCover(store: suspend () -> Boolean, remake: suspend () -> Unit): Deferred<Boolean> {
    val stored = async {
        try {
            store()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
    launch { if (stored.await()) remake() }
    return stored
}
