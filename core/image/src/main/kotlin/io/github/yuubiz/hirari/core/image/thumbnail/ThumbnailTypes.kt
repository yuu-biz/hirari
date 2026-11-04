package io.github.yuubiz.hirari.core.image.thumbnail

import android.graphics.Bitmap
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.CoverCrop
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.ReadingDirection
import java.io.Closeable
import java.io.File
import java.time.Instant

/**
 * What a thumbnail is made from (docs/spec.md section 5.4). The same fields as the key of the disk cache in
 * `:core:data`; `:core:image` cannot depend on that module (section 8.2), so [ThumbnailStore] is the port.
 */
data class ThumbnailKey(
    val book: BookId,
    val modified: Instant,
    val byteSize: Long,
    /** The `entryIndex` of the page the user chose as the cover, `null` for the first page in natural order. */
    val coverEntry: Int?,
    /** The long side in pixels. */
    val sizePx: Int,
    /** Picks the half of a cover scanned as a spread (section 4.4). */
    val direction: ReadingDirection,
    /** The part of the cover page the user chose, `null` for the whole page. Only a key with a range carries it into the digest. */
    val coverCrop: CoverCrop? = null,
)

/** What the store knows about a [ThumbnailKey]. */
sealed interface StoredThumb {
    data class Hit(val file: File) : StoredThumb

    /** Making it failed for this version of the file; section 4.4 does not try again. */
    data object Failed : StoredThumb

    data object Miss : StoredThumb
}

/**
 * The thumbnail disk cache as [Thumbnails] needs it. `ThumbnailCache` of `:core:data` is the implementation: the
 * app maps a [ThumbnailKey] to its `ThumbKey` field by field.
 */
interface ThumbnailStore {
    suspend fun get(key: ThumbnailKey): StoredThumb

    /** Stores a WebP thumbnail and returns its file. */
    suspend fun put(key: ThumbnailKey, webp: ByteArray): File

    suspend fun markFailed(key: ThumbnailKey)

    /** Forgets the thumbnail or the failure, so the next [get] is a miss. */
    suspend fun remove(key: ThumbnailKey)

    /** Keeps [key] out of eviction until the result is closed. */
    fun hold(key: ThumbnailKey): Closeable

    /** Makes the thumbnail of [key] with [block], recorded on disk while it runs, so a death in between is found at the next start. */
    suspend fun <T> making(key: ThumbnailKey, block: suspend () -> T): T

    /** Records as failed what a process that died while making it left, and says whether there was any. Once, before [making]. */
    suspend fun settleInterrupted(): Boolean

    /** Forgets failures from interrupted records, keeping actual decode failures. */
    suspend fun clearInterrupted()
}

/**
 * Where the bytes of a book come from, which sets how many thumbnails are made at once and how long one may take
 * (section 4.4). [limitMillis] is `null` for no limit of the lane's own.
 */
enum class ThumbLane(internal val parallelism: Int, internal val limitMillis: Long?) {
    Local(2, null),
    Smb(2, Thumbnails.SMB_COVER_LIMIT_MILLIS),
}

/** Visible items come before the ones the list only prepares. Section 4.4: both passes serve visible items first. */
enum class ThumbPriority { Visible, Nearby }

/** What [Thumbnails] shows for something in a list. */
sealed interface ThumbSubject {
    val id: BookId
    val modified: Instant

    /**
     * A book, or one image file: the cover is its first page in natural order, or the page with the `entryIndex`
     * [coverEntry]. A page that no longer exists gives the first page. With a [coverCrop] the thumbnail is that part
     * of the chosen page, whatever its shape, and the wide cover's half is not cut (section 4.4). A range belongs to its page: without a [coverEntry], or when that page is gone, it has no effect.
     *
     * @property open opens the book with the decoding it is given, with [io.github.yuubiz.hirari.core.image.Books].
     * It may throw, which counts as the book being unavailable for now.
     */
    class Book(
        override val id: BookId,
        override val modified: Instant,
        val byteSize: Long,
        val coverEntry: Int? = null,
        val lane: ThumbLane = ThumbLane.Local,
        val coverCrop: CoverCrop? = null,
        val open: suspend (ImageDecoding) -> BookResult,
    ) : ThumbSubject

    /**
     * A folder of the file list. Its thumbnail is the first thumbnail that can be made from [chosen], then from
     * the items inside in natural order of their names, descending into sub-folders in place (section 4.4). The
     * store keeps a copy of it under the folder's own key, which has [id] and [modified] but not what is inside,
     * so the folder shows the same cover until [modified] changes or [Thumbnails.regenerate] searches it again.
     *
     * @property chosen the image or page the user chose as the cover of this folder, if any. Regenerate the folder
     * when it changes.
     * @property children lists the folder; it is called on `Dispatchers.IO` and may throw, which counts as the
     * folder being unavailable for now. List only what a thumbnail can be made from: archives, PDFs and images.
     */
    class Folder(
        override val id: BookId,
        override val modified: Instant,
        val chosen: Book? = null,
        /** Resolves the chosen file only on a cache miss while generation is allowed. */
        val resolveChosen: suspend () -> Book? = { chosen },
        val children: suspend () -> List<FolderEntry>,
    ) : ThumbSubject
}

/** An entry of a folder being searched for a cover. */
sealed interface FolderEntry {
    val name: String

    class Item(override val name: String, val book: ThumbSubject.Book) : FolderEntry

    /** A sub-folder; [children] is called on `Dispatchers.IO` and may throw. */
    class Directory(override val name: String, val children: suspend () -> List<FolderEntry>) : FolderEntry
}

/**
 * One thumbnail to show: [sizePx] is the long side, [direction] the binding direction of the book. Within its
 * [priority], a request whose [distance] is smaller is made sooner: how far the item is from the middle of the items
 * on screen, read each time a lane picks what to make next, so the order follows the list as it scrolls. It is
 * called on any thread and must be cheap.
 */
class ThumbRequest(
    val subject: ThumbSubject,
    val sizePx: Int,
    val direction: ReadingDirection,
    val priority: ThumbPriority = ThumbPriority.Visible,
    val distance: () -> Int = { 0 },
) {
    init {
        require(sizePx > 0) { "size $sizePx" }
    }
}

/** A page of a book shown whole, to choose a part of it as the cover (docs/spec.md section 4.4). */
sealed interface PagePreview {
    /** [bitmap] is the page fitted into the size asked for, in the image's own orientation; [shape] is the original size. The caller owns it. */
    class Ready(val bitmap: Bitmap, val shape: PageShape.Known) : PagePreview

    /** The book has no page with that `entryIndex`. */
    data object Missing : PagePreview

    /** The page cannot be shown, and will not be as long as the file stays the same. */
    data object Failed : PagePreview

    /** Reading failed for a reason that may pass. */
    data object Unavailable : PagePreview
}

/** The outcome of a request. Cancelling it is not an outcome: the call throws `CancellationException`. */
sealed interface ThumbResult {
    /** [key] names the thumbnail in the store; for a folder it is the folder's own key, under which its cover is kept. */
    data class Ready(val file: File, val key: ThumbnailKey) : ThumbResult

    /** The book cannot give a thumbnail: encrypted, broken, or without an image. It is recorded and not tried again. */
    data object Failed : ThumbResult

    /** Reading failed for a reason that may pass (a lost connection, a timeout). Nothing is recorded; a later request tries again. */
    data object Unavailable : ThumbResult
}
