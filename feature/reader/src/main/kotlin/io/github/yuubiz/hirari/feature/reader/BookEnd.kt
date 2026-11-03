package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.NeighborOpening
import io.github.yuubiz.hirari.core.model.NeighborStart
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.StartAt

fun NeighborOpening<BookAddress>.request(): OpenBookRequest = OpenBookRequest(
    book,
    when (start) {
        NeighborStart.Saved -> StartAt.Saved
        NeighborStart.First -> StartAt.Page(0)
        NeighborStart.Last -> StartAt.Page(Int.MAX_VALUE)
    },
)
