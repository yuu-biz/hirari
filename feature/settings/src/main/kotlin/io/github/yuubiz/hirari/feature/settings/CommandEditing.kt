package io.github.yuubiz.hirari.feature.settings

import io.github.yuubiz.hirari.core.model.ReaderCommand

/** [command] at the end of the list, unless it is there already or the list holds [max] items. */
internal fun List<ReaderCommand>.withCommand(command: ReaderCommand, max: Int): List<ReaderCommand> =
    if (command in this || size >= max) this else this + command

internal fun List<ReaderCommand>.withoutCommand(command: ReaderCommand): List<ReaderCommand> = this - command

/** [command] moved [delta] places, stopping at the ends. */
internal fun List<ReaderCommand>.movedCommand(command: ReaderCommand, delta: Int): List<ReaderCommand> {
    val from = indexOf(command)
    if (from < 0) return this
    val to = (from + delta).coerceIn(0, lastIndex)
    return toMutableList().apply {
        removeAt(from)
        add(to, command)
    }
}
