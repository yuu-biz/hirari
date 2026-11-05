package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.LibrarySection

/** One sticky header or one bulk interval of ordinary cells, shared by the grid and column. */
internal sealed interface CellInterval {
    data class Header(val cell: ListCell.Header) : CellInterval
    data class Items(val cells: List<ListCell>) : CellInterval
}

internal data class FileCells(val cells: List<ListCell>, val intervals: List<CellInterval>, val entryKeys: List<String>, val entrySections: Map<String, String> = emptyMap())

/** Partition sections and intervals together, off main. Keep folded entries in the anchor's original order. */
internal fun prepareFileCells(rows: List<FileRow>, section: LibrarySection, parent: Boolean, folded: Set<String>): FileCells {
    val all = fileCells(rows, section, parent)
    val cells = ArrayList<ListCell>(all.size)
    val intervals = ArrayList<CellInterval>()
    val keys = ArrayList<String>(rows.size)
    val sections = HashMap<String, String>()
    var sectionKey: String? = null
    var run = ArrayList<ListCell>()
    var hidden = false
    fun flush() {
        if (run.isNotEmpty()) intervals += CellInterval.Items(run)
        run = ArrayList()
    }
    for (cell in all) {
        if (cell is ListCell.Entry) {
            keys += cell.key
            sectionKey?.let { sections[cell.row.path] = it }
        }
        if (cell is ListCell.Header) {
            flush()
            cells += cell
            intervals += CellInterval.Header(cell)
            hidden = cell.key in folded
            sectionKey = cell.key
        } else if (!hidden) {
            cells += cell
            run += cell
        }
    }
    flush()
    return FileCells(cells, intervals, keys, sections)
}

/** Visible cell indexes and their signed offsets from the viewport start, in lazy layout coordinates. */
internal fun FileCells.saveAnchor(visible: List<Pair<Int, Int>>): List<Any> {
    val entry = visible.sortedBy { it.first }.firstOrNull { cells.getOrNull(it.first) is ListCell.Entry }
    val key = entry?.let { cells[it.first].key }.orEmpty()
    val at = entryKeys.indexOf(key)
    val neighbours = if (at < 0) emptyList() else entryKeys.subList(
        maxOf(0, at - 50), minOf(entryKeys.size, at + 51),
    )
    return listOf(key, entry?.let { -it.second } ?: 0) + neighbours
}

/** Exact entry, then original successors, then original predecessors. Headers are never destinations. */
internal fun FileCells.restoreAnchor(saved: List<Any>): Pair<Int, Int> {
    val key = saved[0] as String
    val shown = cells.withIndex().filter { it.value is ListCell.Entry }.associate { it.value.key to it.index }
    shown[key]?.let { return it to saved[1] as Int }
    val order = saved.drop(2).map { it as String }
    val at = order.indexOf(key)
    if (at >= 0) {
        for (next in at + 1 until order.size) shown[order[next]]?.let { return it to 0 }
        for (previous in at - 1 downTo 0) shown[order[previous]]?.let { return it to 0 }
    }
    return 0 to 0
}
