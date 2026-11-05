package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.LibrarySection
import io.github.yuubiz.hirari.core.model.LibrarySort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileCellsTest {
    @Test
    fun `large anchor saves only fifty neighbours on each side and restores its offset`() {
        val source = (0 until 5_000).map { FolderEntry("book$it.zip", null, false, 1, null) }
            .toRows("", Ordering(LibrarySort.Name, false, false))
        val before = prepareFileCells(source, LibrarySection.None, false, emptySet())
        val saved = before.saveAnchor(listOf(2_500 to -27))
        assertEquals(103, saved.size) // key, offset, then 50 predecessors, anchor, 50 successors
        assertEquals(before.entryKeys.subList(2_450, 2_551), saved.drop(2))
        assertEquals(2_500 to 27, before.restoreAnchor(saved))
        assertEquals(53, before.saveAnchor(listOf(0 to 0)).size)
        assertEquals(53, before.saveAnchor(listOf(4_999 to 0)).size)
        assertEquals(listOf("", 0), before.saveAnchor(emptyList()))

        fun remaining(vararg indices: Int) = prepareFileCells(indices.map { source[it] }, LibrarySection.None, false, emptySet())
        // The last saved successor still wins over a closer predecessor, even after reordering.
        val successor = remaining(2_499, 2_550)
        assertEquals(1 to 0, successor.restoreAnchor(saved))
        val predecessor = remaining(2_450)
        assertEquals(0 to 0, predecessor.restoreAnchor(saved))
        // Entries beyond the saved neighbourhood do not become fallback destinations.
        val outside = remaining(4_999, 2_551, 2_449)
        assertEquals(0 to 0, outside.restoreAnchor(saved))
    }

    private val rows = (1..60).map { FolderEntry("book$it.zip", null, false, 1, null) }
        .toRows("", Ordering(LibrarySort.Name, false, false))
        .mapIndexed { index, row -> row.copy(mark = if (index < 20) ReadMark.Unread else if (index < 40) ReadMark.Reading(20) else ReadMark.Read) }

    @Test
    fun `bulk intervals keep every cell key and order with no sections with sections and with folded sections`() {
        for (section in LibrarySection.entries) for (parent in listOf(false, true)) {
            val headers = fileCells(rows, section, parent).filterIsInstance<ListCell.Header>()
            for (folded in listOf(emptySet(), headers.take(1).map { it.key }.toSet(), headers.map { it.key }.toSet())) {
                val prepared = prepareFileCells(rows, section, parent, folded)
                val flattened = prepared.intervals.flatMap { interval -> when (interval) {
                    is CellInterval.Header -> listOf(interval.cell)
                    is CellInterval.Items -> interval.cells
                } }
                val expected = fileCells(rows, section, parent, folded)
                assertEquals(expected, flattened)
                assertEquals(expected.map { it.key }, flattened.map { it.key })
                assertEquals(expected, prepared.cells)
                assertEquals(rows.size, prepared.entryKeys.size)
                assertTrue(prepared.intervals.filterIsInstance<CellInterval.Items>().all { run -> run.cells.none { it is ListCell.Header } })
                if (section == LibrarySection.None) {
                    assertEquals(1, prepared.intervals.size)
                    assertEquals(60 + if (parent) 1 else 0, assertIs<CellInterval.Items>(prepared.intervals.single()).cells.size)
                } else assertTrue(prepared.intervals.size <= headers.size * 2 + if (parent) 1 else 0)
            }
        }
    }

    @Test
    fun `anchor skips a header and parent and restores to the entry when the anchored section is removed`() {
        val before = prepareFileCells(rows, LibrarySection.ReadState, true, emptySet())
        val saved = before.saveAnchor(listOf(0 to -12, 1 to 0, 2 to 30))
        assertEquals("book1.zip", saved[0])
        assertEquals(-30, saved[1])
        val withoutSections = prepareFileCells(rows, LibrarySection.None, true, emptySet())
        assertEquals(1 to -30, withoutSections.restoreAnchor(saved))
        // The first section and all of its entries disappear. Use the nearest original successor.
        val removed = prepareFileCells(rows.drop(20), LibrarySection.ReadState, true, emptySet())
        val (next, offset) = removed.restoreAnchor(saved)
        assertEquals("book21.zip", removed.cells[next].key)
        assertEquals(0, offset)
        // A folded section follows the same rule, while a removed final section uses a predecessor.
        val folded = prepareFileCells(rows, LibrarySection.ReadState, true, setOf("/section:ReadState:Unread"))
        assertEquals("book21.zip", folded.cells[folded.restoreAnchor(saved).first].key)
        val last = before.cells.indexOfFirst { it.key == "book60.zip" }
        val finalAnchor = before.saveAnchor(listOf(last to -8))
        val withoutLast = prepareFileCells(rows.take(40), LibrarySection.ReadState, false, emptySet())
        assertEquals("book40.zip", withoutLast.cells[withoutLast.restoreAnchor(finalAnchor).first].key)
        assertEquals(0 to 0, prepareFileCells(emptyList(), LibrarySection.None, false, emptySet()).restoreAnchor(saved))
        assertEquals(0 to 0, before.restoreAnchor(before.saveAnchor(listOf(0 to 0, 1 to 20))))
    }
}
