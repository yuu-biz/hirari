package io.github.yuubiz.hirari.feature.settings

import io.github.yuubiz.hirari.core.model.ReaderCommand

/** The slots with [command], or nothing for `null`, in slot [index]. */
internal fun List<ReaderCommand?>.withSlot(index: Int, command: ReaderCommand?): List<ReaderCommand?> =
    mapIndexed { i, slot -> if (i == index) command else slot }
