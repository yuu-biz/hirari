package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.LibraryBook
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.NaturalOrder
import io.github.yuubiz.hirari.core.model.normalizePath
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The book kinds the list tells apart by extension (docs/spec.md section 3.2); the reader decides by content. */
enum class ArchiveKind(val label: String) { Zip("ZIP"), Rar("RAR"), SevenZip("7Z"), Tar("TAR"), Pdf("PDF") }

/** One entry of a folder that the list shows (docs/spec.md section 4.3, 項目の種類). */
sealed interface FileItem {
    val entry: FolderEntry

    data class Folder(override val entry: FolderEntry) : FileItem

    data class Archive(override val entry: FolderEntry, val kind: ArchiveKind) : FileItem

    data class Text(override val entry: FolderEntry) : FileItem

    data class Image(override val entry: FolderEntry) : FileItem
}

private val ARCHIVES = mapOf(
    "zip" to ArchiveKind.Zip,
    "cbz" to ArchiveKind.Zip,
    "rar" to ArchiveKind.Rar,
    "cbr" to ArchiveKind.Rar,
    "7z" to ArchiveKind.SevenZip,
    "cb7" to ArchiveKind.SevenZip,
    "tar" to ArchiveKind.Tar,
    "cbt" to ArchiveKind.Tar,
    "pdf" to ArchiveKind.Pdf,
)

private val IMAGES = setOf("jpg", "jpeg", "jpe", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "jxl")

/** The kind of [entry], or `null` for a file the list leaves out. */
fun FolderEntry.toItem(showDotFiles: Boolean): FileItem? {
    if (!showDotFiles && name.startsWith('.')) return null
    if (directory) return FileItem.Folder(this)
    val extension = name.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.ROOT)
    ARCHIVES[extension]?.let { return FileItem.Archive(this, it) }
    return when (extension) {
        in IMAGES -> FileItem.Image(this)
        "txt" -> FileItem.Text(this)
        else -> null
    }
}

/**
 * A shown entry with its path in the place, the key the filter matches, the detail line of a list (docs/spec.md
 * section 4.3, 補足情報) formatted ahead of binding, and what the index knows of it.
 */
data class FileRow(
    val item: FileItem,
    val path: String,
    val filterKey: String,
    val dateText: String? = null,
    val sizeText: String? = null,
    val mark: ReadMark? = null,
    val pageCount: Int? = null,
)

data class Ordering(val sort: LibrarySort, val foldersFirst: Boolean, val showDotFiles: Boolean)

/**
 * The entries of the folder at [folder] as rows in [ordering]. Names compare in natural order; dates sort the
 * entries without one last either way, and ties fall back to the names.
 */
fun List<FolderEntry>.toRows(folder: String, ordering: Ordering, dates: DateTimeFormatter = DETAIL_DATE): List<FileRow> {
    val byName = compareBy<FileItem, String>(NaturalOrder) { it.entry.name }
    val order = when (ordering.sort) {
        LibrarySort.Name -> byName
        LibrarySort.Newest -> compareBy<FileItem, Instant?>(nullsLast(reverseOrder())) { it.entry.modified }.then(byName)
        LibrarySort.Oldest -> compareBy<FileItem, Instant?>(nullsLast(naturalOrder())) { it.entry.modified }.then(byName)
    }
    val grouped = if (ordering.foldersFirst) compareBy<FileItem> { it !is FileItem.Folder }.then(order) else order
    return mapNotNull { it.toItem(ordering.showDotFiles) }
        .sortedWith(grouped)
        .map { item ->
            FileRow(
                item = item,
                path = normalizePath("$folder/${item.entry.name}"),
                filterKey = filterKey(item.entry.name),
                dateText = item.entry.modified?.let(dates::format),
                sizeText = if (item is FileItem.Folder) null else item.entry.size?.let(::sizeText),
            )
        }
}

/** `yyyy/MM/dd HH:mm` in the device's zone. First used off the main thread, since the zone data is read from disk. */
val DETAIL_DATE: DateTimeFormatter by lazy { DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm").withZone(ZoneId.systemDefault()) }

/** `12.3 MB`: one decimal, in steps of 1024. */
fun sizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < SIZE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, SIZE_UNITS[unit])
}

private val SIZE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

/**
 * The read state of each row from the books the index holds directly in the folder (docs/spec.md section 7).
 * A book the index does not know is unread; a folder is marked only once it was read as a book, and an image
 * file never.
 */
fun List<FileRow>.withMarks(indexed: List<LibraryBook>): List<FileRow> {
    val byPath = HashMap<String, LibraryBook>(indexed.size * 2)
    for (book in indexed) (book.book.source as? BookSource.InLocation)?.let { byPath[it.path] = book }
    return map { row ->
        val known = byPath[row.path]
        val mark = when (row.item) {
            is FileItem.Archive, is FileItem.Text -> known?.progress.mark()
            is FileItem.Folder -> known?.let { it.progress.mark() }
            is FileItem.Image -> null
        }
        if (mark == row.mark && known?.book?.pageCount == row.pageCount) row else row.copy(mark = mark, pageCount = known?.book?.pageCount)
    }
}

/** The entry right below [ancestor] on the way down to [descendant], or `null` when it is not below. */
fun childToward(ancestor: String, descendant: String): String? {
    val up = normalizePath(ancestor).split('/').filter { it.isNotEmpty() }
    val down = normalizePath(descendant).split('/').filter { it.isNotEmpty() }
    if (down.size <= up.size || down.subList(0, up.size) != up) return null
    return down.subList(0, up.size + 1).joinToString("/")
}

/** The filter of docs/spec.md section 4.3: NFKC, either case, a part of the name. */
fun filterKey(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

fun List<FileRow>.filtered(query: String): List<FileRow> {
    val key = filterKey(query.trim())
    return if (key.isEmpty()) this else filter { key in it.filterKey }
}
