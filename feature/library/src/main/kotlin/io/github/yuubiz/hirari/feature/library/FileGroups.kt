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

/** Upper then lower case folds ß and final sigma too. */
private fun normalizedName(name: String): String =
    Normalizer.normalize(name, Normalizer.Form.NFKC).trim().uppercase(Locale.ROOT).lowercase(Locale.ROOT)

/** Paths are relative, so header keys starting with `/` cannot collide with an item's key. */
private fun authorKey(name: String?): String = if (name == null) "/no-author" else "/author:$name"

/** Each root is the smallest normalized name in its component, independent of list order. */
private class CreatorGroups {
    private val parents = mutableMapOf<String, String>()

    fun root(name: String): String {
        parents.putIfAbsent(name, name)
        var root = name
        while (parents.getValue(root) != root) root = parents.getValue(root)
        var current = name
        while (current != root) {
            val next = parents.getValue(current)
            parents[current] = root
            current = next
        }
        return root
    }

    fun join(names: List<String>) {
        val first = names.firstOrNull() ?: return
        for (name in names.drop(1)) {
            val a = root(first)
            val b = root(name)
            parents[maxOf(a, b)] = minOf(a, b)
        }
    }
}

private data class CreatorRow(val row: FileRow, val parsed: LibraryName) {
    val names = parsed.names.map(::normalizedName).distinct()
}

/** Count exact full spellings; linked map order breaks ties by the first appearance in the sorted list. */
private fun groupSpelling(items: List<CreatorRow>): String? {
    val both = items.filter { it.parsed.circle != null && it.parsed.author != null }
    return both.ifEmpty { items }.mapNotNull { it.parsed.creator }.groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key
}

/** [rows] have already been filtered and sorted; preserve that order within each author. */
internal fun fileCells(
    rows: List<FileRow>,
    grouping: LibraryGrouping,
    parent: Boolean,
    folded: Set<String> = emptySet(),
    mergeCircleAuthors: Boolean = true,
): List<ListCell> = buildList {
    if (parent) add(ListCell.Parent)
    if (grouping == LibraryGrouping.None) {
        rows.mapTo(this) { ListCell.Entry(it) }
    } else {
        val creators = rows.map { CreatorRow(it, parseLibraryName(it.item.gridBase())) }
        val unions = CreatorGroups()
        if (mergeCircleAuthors) creators.forEach { unions.join(it.names) }
        val groups = creators.groupBy {
            authorKey(if (mergeCircleAuthors) it.names.firstOrNull()?.let(unions::root) else it.parsed.creator?.let(::normalizedName))
        }
        val headers = groups.map { (key, items) ->
            val spelling = if (mergeCircleAuthors) groupSpelling(items) else items.first().parsed.creator
            ListCell.Header(spelling, items.size, key)
        }
        val ordered = headers.sortedWith { a, b ->
            when {
                a.key == "/no-author" -> if (b.key == a.key) 0 else 1
                b.key == "/no-author" -> -1
                else -> NaturalOrder.compare(normalizedName(a.author.orEmpty()), normalizedName(b.author.orEmpty()))
            }
        }
        for (header in ordered) {
            add(header)
            if (header.key !in folded) groups.getValue(header.key).mapTo(this) { ListCell.Entry(it.row) }
        }
    }
}
