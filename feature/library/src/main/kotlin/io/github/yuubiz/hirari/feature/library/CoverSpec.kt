package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookFormat
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.Container
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.locationBookId
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Where the bytes of a cover's book are, and so how to open them. */
sealed interface CoverOrigin {
    /** An archive or PDF in a place, which the content decides (docs/spec.md section 3.2). */
    data class Archive(val place: LibraryLocation, val path: String, val documentId: String?) : CoverOrigin

    /** One image file in a place, read as a book of one page. */
    data class Image(val place: LibraryLocation, val path: String, val documentId: String?) : CoverOrigin

    /** A folder in a place whose image files are the pages (docs/spec.md section 3.10). */
    data class ImageFolder(val place: LibraryLocation, val path: String, val documentId: String?) : CoverOrigin

    /** A document picked or handed over outside a place. */
    data class Document(val uri: UriString) : CoverOrigin

    /** A folder tree picked outside a place. */
    data class Tree(val uri: UriString) : CoverOrigin
}

/**
 * What a cover is made from (docs/spec.md section 4.4), as plain values: two specs are equal when they ask for the
 * same cover, so a list can keep a row when nothing changed and the loader can key on it. Building the
 * `ThumbSubject` from it, which is where the I/O lambdas live, waits until a row is bound.
 */
sealed interface CoverSpec {
    val id: BookId
    val modified: Instant

    /** The binding direction of the book, which picks the half of a cover scanned as a spread. */
    val direction: ReadingDirection

    /**
     * [coverEntry] is the `entryIndex` of the page the user chose, or `null` for the first page. [coverCrop] is the
     * part of that page the user chose, or `null` for the whole page.
     */
    data class Book(
        override val id: BookId,
        override val modified: Instant,
        val byteSize: Long,
        val coverEntry: Int?,
        override val direction: ReadingDirection,
        val origin: CoverOrigin,
        val coverCrop: CoverCrop? = null,
    ) : CoverSpec

    /** The folder at [path] of [place]; [chosen] is the image or page the user chose for it, if any. */
    data class Folder(
        override val id: BookId,
        override val modified: Instant,
        override val direction: ReadingDirection,
        val place: LibraryLocation,
        val path: String,
        val documentId: String?,
        val chosen: Book?,
    ) : CoverSpec
}

/**
 * The cover of an indexed [book] shown outside a file list: on the shelf and in the history. [place] is where the
 * book lies when it is in one; a book whose place is gone has none, and neither has a text, which shows a fixed
 * icon (docs/spec.md section 4.4).
 */
internal fun coverSpecOf(book: Book, coverEntry: Int?, place: LibraryLocation?, direction: ReadingDirection, coverCrop: CoverCrop? = null): CoverSpec.Book? {
    val folder = book.format == BookFormat.Images(Container.Folder)
    val origin = when {
        book.format is BookFormat.Text -> return null
        else -> when (val source = book.source) {
            is BookSource.InLocation -> {
                place ?: return null
                if (folder) CoverOrigin.ImageFolder(place, source.path, null) else CoverOrigin.Archive(place, source.path, null)
            }
            is BookSource.External -> if (folder) CoverOrigin.Tree(source.uri) else CoverOrigin.Document(source.uri)
        }
    }
    return CoverSpec.Book(book.id, book.modified, book.byteSize, coverEntry, direction, origin, coverCrop)
}

/** The thumbnail in the store that [CoverSpec] names at a size, which is what a loaded cover and a regeneration share. */
data class CoverKey(
    val id: BookId,
    val modified: Instant,
    val byteSize: Long,
    val coverEntry: Int?,
    val folder: Boolean,
    val sizePx: Int,
    val direction: ReadingDirection,
    val coverCrop: CoverCrop? = null,
)

fun CoverSpec.key(sizePx: Int): CoverKey = when (this) {
    is CoverSpec.Book -> CoverKey(id, modified, byteSize, coverEntry, folder = false, sizePx, direction, coverCrop)
    is CoverSpec.Folder -> CoverKey(id, modified, byteSize = 0, coverEntry = null, folder = true, sizePx, direction)
}

/** A cover at a size: what a row shows. The [CoverKey] does not tell the folder's chosen image, which the search needs. */
data class CoverRef(val spec: CoverSpec, val sizePx: Int) {
    val key: CoverKey get() = spec.key(sizePx)
}

/** Width over height of the cover box in a list. */
internal const val COVER_RATIO = 0.7f

/** The narrowest a grid cell may be (docs/spec.md section 4.3, グリッドの列数). */
internal val ThumbSize.minCellWidth: Dp get() = when (this) {
    ThumbSize.Small -> 72.dp
    ThumbSize.Medium -> 90.dp
    ThumbSize.Large -> 120.dp
}

/** How many columns a grid of [width] has: as many cells of at least [ThumbSize.minCellWidth] as fit, and never fewer than 2. */
internal fun gridColumns(width: Dp, size: ThumbSize): Int = max(MIN_COLUMNS, floor(width.value / size.minCellWidth.value).toInt())

private const val MIN_COLUMNS = 2

/**
 * The long side of the thumbnails of a size (docs/spec.md section 5.4): the height of the book cover box of a cell 1.5
 * times the narrowest cell of the size. A cell is narrower than twice the narrowest, so a cover is seldom enlarged.
 */
internal val ThumbSize.coverLongSide: Dp get() = minCellWidth * COVER_CELL_SCALE / COVER_RATIO

/**
 * The long side of a thumbnail of [size] in pixels on a screen of [density]. The only place the pixels are worked
 * out, so the lists, the regeneration and the store all name the same thumbnail.
 */
fun coverSizePx(size: ThumbSize, density: Float): Int = ceil(size.coverLongSide.value * density).toInt()

private const val COVER_CELL_SCALE = 1.5f

/**
 * The image or page the user chose as the cover of a folder, with the listing entry of the file it is in, whose
 * date and size name its thumbnail. [page] is the `entryIndex` of a page of the book at [imagePath], or `null`
 * when [imagePath] is an image file.
 */
data class ChosenCover(val imagePath: String, val page: Int?, val entry: FolderEntry) {
    fun toSpec(place: LibraryLocation, direction: ReadingDirection): CoverSpec.Book {
        val origin = if (page == null) CoverOrigin.Image(place, imagePath, entry.documentId) else CoverOrigin.Archive(place, imagePath, entry.documentId)
        return CoverSpec.Book(locationBookId(place, imagePath), entry.modified ?: Instant.EPOCH, entry.size ?: 0, page, direction, origin)
    }
}
