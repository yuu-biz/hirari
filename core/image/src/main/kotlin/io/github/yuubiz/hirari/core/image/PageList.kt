package io.github.yuubiz.hirari.core.image

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
        in IMAGE_EXTENSIONS -> PageCandidate.Image
        in EXCLUDED_EXTENSIONS -> PageCandidate.Excluded
        else -> PageCandidate.Sniff
    }
}

/** [entries] in natural order, with [PageEntry.index] set to the position in that order. */
fun sortedPages(entries: List<PageEntry>): List<PageEntry> =
    entries.sortedWith(NaturalPageOrder).mapIndexed { i, entry -> entry.copy(index = i) }

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "jpe", "jfif", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "jxl")
private val EXCLUDED_EXTENSIONS = setOf("txt", "nfo", "xml", "sfv", "md5", "url")
private val EXCLUDED_NAMES = setOf("thumbs.db", "desktop.ini")
