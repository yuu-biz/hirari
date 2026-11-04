package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.EntryOrder
import io.github.yuubiz.hirari.core.model.FileFormats
import io.github.yuubiz.hirari.core.model.NaturalPageOrder
import io.github.yuubiz.hirari.core.model.PageEntry
import java.util.Locale

/** Whether an entry or file goes into the page list (docs/spec.md section 3.5). */
enum class PageCandidate {
    /** Its extension is an image extension. */
    Image,

    /** Its extension is missing or unknown, so its first bytes decide. */
    Sniff,

    Excluded,
}

/** [name] is the path inside the book, with `/` or `\` between folders. */
fun pageCandidate(name: String, isDirectory: Boolean): PageCandidate {
    if (isDirectory || name.endsWith('/') || name.endsWith('\\')) return PageCandidate.Excluded
    val parts = name.split('/', '\\')
    if (parts.any { it.equals("__MACOSX", ignoreCase = true) || it.startsWith('.') }) return PageCandidate.Excluded
    val fileName = parts.last().lowercase(Locale.ROOT)
    if (fileName in EXCLUDED_NAMES) return PageCandidate.Excluded
    val extension = fileName.substringAfterLast('.', "")
    return when (extension) {
        in FileFormats.imagePages -> PageCandidate.Image
        in FileFormats.texts -> PageCandidate.Excluded
        in EXCLUDED_EXTENSIONS -> PageCandidate.Excluded
        else -> PageCandidate.Sniff
    }
}

/**
 * The order of a book's pages (docs/spec.md section 3.5): 書庫内の並び, natural or as the archive stores them, and
 * reversed for a book whose `reverseOrder` is on (section 2.9).
 */
data class PageOrder(val entries: EntryOrder = EntryOrder.Natural, val reversed: Boolean = false)

/** [entries] in [order], with [PageEntry.index] set to the position in that order. */
fun sortedPages(entries: List<PageEntry>, order: PageOrder = PageOrder()): List<PageEntry> {
    val sorted = when (order.entries) {
        EntryOrder.Natural -> entries.sortedWith(NaturalPageOrder)
        EntryOrder.Stored -> entries.sortedBy { it.entryIndex }
    }
    return (if (order.reversed) sorted.asReversed() else sorted).mapIndexed { i, entry -> entry.copy(index = i) }
}

private val EXCLUDED_EXTENSIONS = setOf("nfo", "xml", "sfv", "md5", "url")
private val EXCLUDED_NAMES = setOf("thumbs.db", "desktop.ini")
