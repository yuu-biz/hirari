package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.LibraryBook
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The CPU side of P7 (docs/spec.md section 5.2) on the JVM: sorting, marking and filtering a 1000-entry folder.
 * The provider's query is not in it. The bound only catches a pipeline that went quadratic; the printed medians
 * are the figures to read.
 */
class ListingTimingTest {
    private val place = LocationId(1)
    private val start = Instant.parse("2026-01-01T00:00:00Z")

    private val entries = (1..1000).map { n ->
        val series = "シリーズ${n % 37}"
        if (n % 50 == 0) {
            FolderEntry("$series 特典 $n", "dir$n", directory = true, size = null, modified = start.plusSeconds(n * 7L))
        } else {
            FolderEntry("$series 第${n}巻 (Vol.$n).zip", "doc$n", directory = false, size = 50_000_000L + n, modified = start.plusSeconds(n * 13L % 997))
        }
    }

    private val indexed = entries.filterIndexed { i, _ -> i % 3 == 0 }.map { entry ->
        val book = book(place, "作品/${entry.name}")
        LibraryBook(book, null, reading(book, viewStart = 3))
    }

    private val progress = indexed.progressByPath()

    private fun medianNanos(runs: Int, block: () -> Unit): Long {
        repeat(20) { block() }
        return (1..runs).map { System.nanoTime().also { block() }.let { System.nanoTime() - it } }.sorted()[runs / 2]
    }

    @Test
    fun `a 1000 entry folder sorts, marks and filters well inside the P7 budget`() {
        val byName = Ordering(LibrarySort.Name, foldersFirst = true, showDotFiles = false)
        val byDate = byName.copy(sort = LibrarySort.Newest)
        val rows = entries.toRows("作品", byName)

        val sortName = medianNanos(31) { entries.toRows("作品", byName) }
        val sortDate = medianNanos(31) { entries.toRows("作品", byDate) }
        val tree = LibraryLocation.SafTree(place, "漫画", UriString("content://tree/manga"))
        val ids = medianNanos(31) { rows.associateBy({ locationBookId(tree, it.path) }, { it.path }) }
        val mark = medianNanos(31) { rows.withMarks(indexed, progress) }
        val filter = medianNanos(31) { rows.filtered("第1") }

        println(
            "1000 entries, median ms: sort by name %.2f, by date %.2f, book ids %.2f, marks %.2f, filter %.2f"
                .format(sortName / 1e6, sortDate / 1e6, ids / 1e6, mark / 1e6, filter / 1e6),
        )
        assertTrue(sortName + ids + mark + filter < 300_000_000L)
    }
}
