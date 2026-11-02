package io.github.yuubiz.hirari.feature.reader

/**
 * Whoever holds the one resource of a screen, from the moment it opens on any thread. [release] closes it once,
 * whatever the screen was doing, and a resource that opens after the release closes at once.
 */
internal class Ownership<T : Any>(private val close: (T) -> Unit) {
    private var held: T? = null
    private var released = false

    @Synchronized
    fun take(item: T) {
        if (released) close(item) else held = item
    }

    @Synchronized
    fun release() {
        released = true
        held?.let(close)
        held = null
    }
}
