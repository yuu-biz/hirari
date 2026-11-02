package io.github.yuubiz.hirari.feature.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The thumbnail one place on screen draws. Asking for another page drops the old one first, so a page that
 * is still decoding shows nothing instead of the page before it.
 */
internal class ThumbnailSlot<T : Any>(
    private val cached: (Int) -> T?,
    private val load: suspend (Int) -> T?,
) {
    var shown: T? by mutableStateOf(null)
        private set

    suspend fun show(page: Int) {
        shown = cached(page)
        if (shown == null) shown = load(page)
    }
}
