package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.NaturalOrder
import java.text.Normalizer
import java.util.Locale

/** Cells after filtering and sorting, including rows that never request a cover. */
internal sealed interface ListCell {
    val key: String

    data object Parent : ListCell {
        override val key = PARENT_KEY
    }

    data class Header(val author: String?, val count: Int, override val key: String) : ListCell

    data class Entry(val row: FileRow) : ListCell {
        override val key get() = row.path
    }
}

internal const val PARENT_KEY = ".."

/** Paths are relative, so header keys starting with `/` cannot collide with an item's key. Upper then lower case folds ß and final sigma too. */
private fun authorKey(author: String?): String = if (author == null) "/no-author" else
    "/author:" + Normalizer.normalize(author, Normalizer.Form.NFKC).trim().uppercase(Locale.ROOT).lowercase(Locale.ROOT)

/** [rows] have already been filtered and sorted; preserve that order within each author. */
internal fun fileCells(rows: List<FileRow>, grouping: LibraryGrouping, parent: Boolean, folded: Set<String> = emptySet()): List<ListCell> = buildList {
    if (parent) add(ListCell.Parent)
    if (grouping == LibraryGrouping.None) {
        rows.mapTo(this) { ListCell.Entry(it) }
    } else {
        val groups = rows.groupBy { authorKey(authorInName(it.item.entry.name)) }
        val ordered = groups.entries.sortedWith { a, b ->
            when {
                a.key == "/no-author" -> if (b.key == a.key) 0 else 1
                b.key == "/no-author" -> -1
                else -> NaturalOrder.compare(a.key.removePrefix("/author:"), b.key.removePrefix("/author:"))
            }
        }
        for ((key, items) in ordered) {
            add(ListCell.Header(authorInName(items.first().item.entry.name), items.size, key))
            if (key !in folded) items.mapTo(this) { ListCell.Entry(it) }
        }
    }
}
