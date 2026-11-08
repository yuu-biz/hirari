package io.github.yuubiz.hirari.feature.library

import android.content.ComponentCallbacks2

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.yuubiz.hirari.core.archive.ListingNeed
import io.github.yuubiz.hirari.core.data.ProvisionalCovers
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.image.thumbnail.CoverListing
import io.github.yuubiz.hirari.core.image.thumbnail.FolderEntry as ThumbEntry
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.image.thumbnail.PagePreview
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbImage
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbLane
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbPriority
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbRequest
import io.github.yuubiz.hirari.core.image.thumbnail.ThumbSubject
import io.github.yuubiz.hirari.core.data.FolderCover
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ThumbSize
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * The covers of the lists (docs/spec.md section 4.4), one for the process: the thumbnail source, the books it
 * makes them from, and the decoded covers in memory that every list shares. [memoryBytes] is the share of the
 * memory budget for them (section 5.4, メモリ). [density] is read when a size is worked out, since the display can
 * change while the app runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Covers(
    private val source: ThumbnailSource,
    private val books: CoverBooks,
    private val memoryBytes: Long,
    private val density: () -> Float,
    decodeContext: CoroutineContext = Dispatchers.IO.limitedParallelism(DECODE_PARALLELISM),
    /** The covers chosen from the first images of a RAR or a tar (docs/spec.md section 4.4). */
    private val provisional: ProvisionalCovers = ProvisionalCovers.None,
    decode: (ThumbImage) -> ImageBitmap? = ::decodeCover,
) {
    /** One [settle] at a time, so a second one for a book finds what the first left. */
    private val settling = Mutex()

    private data class ListingKey(val place: LibraryLocation, val path: String)
    private class Listing(val job: Deferred<List<FolderEntry>>, var waiters: Int = 0)
    private val chosenLookups = Semaphore(CHOSEN_LOOKUPS)
    private val listings = HashMap<ListingKey, Listing>()

    /** Shares only in-flight listings; the last waiter leaving cancels the network work. */
    private suspend fun parentEntries(place: LibraryLocation, path: String): List<FolderEntry> {
        val key = ListingKey(place, path)
        val context = currentCoroutineContext()
        val listing = synchronized(listings) {
            listings.getOrPut(key) {
                Listing(CoroutineScope(context.minusKey(Job)).async(start = CoroutineStart.LAZY) { chosenLookups.withPermit { books.list(place, path, null) } })
            }.also { it.waiters++ }
        }
        try {
            return listing.job.await()
        } finally {
            synchronized(listings) {
                if (--listing.waiters == 0) {
                    listings.remove(key, listing)
                    listing.job.cancel()
                }
            }
        }
    }

    private val loader = CoverLoader(source, MemoryImages(memoryBytes) { it.width * it.height * BYTES_PER_PIXEL }, decodeContext, decode)

    /** The long side of the thumbnails of a list of [size], on this display. */
    fun sizePx(size: ThumbSize): Int = coverSizePx(size, density())

    /** Every size the app can show, [first] before the others: the sizes a regeneration remakes. */
    fun sizesFrom(first: ThumbSize): List<Int> = (listOf(first) + ThumbSize.entries).map(::sizePx).distinct()

    /**
     * The request for [ref]. The only place a request is made, so showing, preparing and regenerating a cover name
     * the same thumbnail in the store.
     */
    fun request(ref: CoverRef, priority: ThumbPriority, focus: CoverFocus? = null): ThumbRequest = request(ref, priority, focus, subject(ref.spec))

    private fun request(ref: CoverRef, priority: ThumbPriority, focus: CoverFocus?, subject: ThumbSubject): ThumbRequest {
        val key = ref.key
        return ThumbRequest(subject, ref.sizePx, ref.spec.direction, priority) { focus?.distance(key) ?: 0 }
    }

    fun peek(ref: CoverRef): ImageBitmap? = loader.peek(ref.key)

    /**
     * Answers `onTrimMemory` (docs/spec.md section 5.4): when the app's screens are hidden the covers shrink to an eighth
     * of their share, and once the app sits in the background they are all let go; the store keeps them on disk.
     */
    fun trimMemory(level: Int) = when {
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> loader.trimMemory(0)
        level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> loader.trimMemory(memoryBytes / 8)
        else -> Unit
    }

    /** Whether covers are not made, since the last process crashed making one (docs/spec.md section 4.4), until [resume]. */
    val held: Flow<Boolean> get() = source.held

    /** 表紙を作る: makes covers again, and the rows that showed none meanwhile ask for theirs. */
    fun resume() = source.resume()

    /**
     * What a row on screen shows, with the visible priority and, in a list that has a [focus], before the rows farther
     * from the middle of the screen; collect it while the row is bound.
     */
    fun show(ref: CoverRef, focus: CoverFocus? = null): Flow<CoverState<ImageBitmap>> =
        focus?.show(ref.key) ?: loader.show(ref.key, request(ref, ThumbPriority.Visible), recoveries(ref.spec))

    /** Reads only stored covers of items near the screen, until their jobs are cancelled. */
    fun prefetcher(scope: CoroutineScope, focus: CoverFocus): Prefetcher<CoverRef> =
        Prefetcher.forRefs(scope) { ref -> loader.warm(ref.key, request(ref, ThumbPriority.Nearby, focus)).collect {} }

    fun viewport(scope: CoroutineScope, focus: CoverFocus): CoverViewport =
        CoverViewport(focus, visible(scope, focus), prefetcher(scope, focus))

    /** Starts visible covers in viewport order, independently of row composition. */
    fun visible(scope: CoroutineScope, focus: CoverFocus): Prefetcher<CoverRef> =
        Prefetcher.forRefs(scope) { ref ->
            loader.show(ref.key, request(ref, ThumbPriority.Visible, focus), recoveries(ref.spec))
                .collect { focus.publish(ref.key, it) }
        }

    /**
     * サムネイルを作り直す for [spec] at each of [sizes], the first being the one on screen: forgets it in the store,
     * makes it again, and tells the rows showing it to look again.
     */
    suspend fun regenerate(spec: CoverSpec, sizes: List<Int>) = remake(spec, sizes, subject(spec))

    private suspend fun remake(spec: CoverSpec, sizes: List<Int>, subject: ThumbSubject) {
        for (sizePx in sizes) {
            val ref = CoverRef(spec, sizePx)
            source.regenerate(request(ref, ThumbPriority.Visible, focus = null, subject))
            loader.refresh(ref.key)
        }
    }

    /**
     * The page with the `entryIndex` [entryIndex] of the book of [spec], whole and fitted into [maxSidePx], for
     * choosing a range of it as the cover (docs/spec.md section 4.4). The book opens as a cover's does, in its lane.
     */
    suspend fun preview(spec: CoverSpec.Book, entryIndex: Int, maxSidePx: Int): PagePreview =
        source.preview(book(spec) { decoding -> books.open(spec, decoding, ListingNeed.UpTo(entryIndex)) }, entryIndex, maxSidePx)

    /**
     * Settles the provisional cover of [id], if it has one, once a whole listing of the book put the page with the
     * `entryIndex` [firstEntry] first in natural order (docs/spec.md section 4.4). When that is another page, the
     * automatic cover of the book of [spec] is made again from that page at each of [sizes], under the key it has, and
     * either way the provisional cover is forgotten. [spec] is asked for only to make the cover again; `null` keeps the
     * provisional cover for the next opening. Settles run one at a time.
     */
    suspend fun settle(id: BookId, firstEntry: Int?, sizes: List<Int>, spec: suspend () -> CoverSpec.Book?) = settling.withLock {
        val remembered = provisional.entry(id) ?: return@withLock
        if (firstEntry != null && firstEntry != remembered) {
            val automatic = spec()?.copy(coverEntry = null, coverCrop = null) ?: return@withLock
            remake(automatic, sizes, book(automatic, firstEntry))
        }
        provisional.forget(id)
    }

    /**
     * Opens the book of [spec] for its cover: up to the chosen page, and otherwise up to [firstEntry], the first page
     * a whole listing found, or up to the first images (docs/spec.md section 4.4). A partial listing that reached the
     * chosen entry without finding a page there cannot tell which page stands in for it, so the book opens again as
     * one without a chosen page. A cover chosen from the first images is remembered as provisional.
     */
    private suspend fun openCover(spec: CoverSpec.Book, decoding: ImageDecoding, firstEntry: Int?): BookResult {
        spec.coverEntry?.let { chosen ->
            val opened = books.open(spec, decoding, ListingNeed.UpTo(chosen))
            val book = (opened as? BookResult.Opened)?.book
            if (book == null || !book.partial || book.pages.any { it.entryIndex == chosen }) return opened
            book.close()
        }
        val need = firstEntry?.let(ListingNeed::UpTo) ?: CoverListing.automatic
        val opened = books.open(spec, decoding, need)
        if (opened is BookResult.Opened) {
            CoverListing.provisional(need, opened.book)?.let { entry ->
                try {
                    provisional.remember(spec.id, entry)
                } catch (e: CancellationException) {
                    opened.book.close()
                    throw e
                } catch (_: Exception) {
                    // The cover still shows; only its correction once the book is opened is lost.
                }
            }
        }
        return opened
    }

    /**
     * The listing entry of the item at [path] of [place], found in its folder, or `null` when the folder cannot be
     * listed or the item is not in it. The date and size of the entry name the thumbnail.
     */
    suspend fun find(place: LibraryLocation, path: String): FolderEntry? {
        val name = path.substringAfterLast('/')
        return entries(place, path.substringBeforeLast('/', "")).firstOrNull { normalizePath(it.name) == name }
    }

    /** A caller showing several items retains this snapshot for its own lifetime. */
    internal suspend fun entries(place: LibraryLocation, parent: String): List<FolderEntry> {
        return try {
            parentEntries(place, parent)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun chosen(place: LibraryLocation, cover: FolderCover): ChosenCover? =
        find(place, cover.imagePath)?.let { ChosenCover(cover.imagePath, cover.entry, it) }

    /** The spec of the folder at [path], which its own entry in the parent folder dates, or `null` when that cannot be found. */
    suspend fun folderSpec(place: LibraryLocation, path: String, direction: ReadingDirection, chosen: ChosenCover?): CoverSpec.Folder? {
        val entry = find(place, path) ?: return null
        return CoverSpec.Folder(locationBookId(place, path), entry.modified ?: Instant.EPOCH, direction, place, path, entry.documentId, chosen?.toSpec(place, direction))
    }

    private fun subject(spec: CoverSpec): ThumbSubject = when (spec) {
        is CoverSpec.Book -> book(spec)
        is CoverSpec.Folder -> ThumbSubject.Folder(spec.id, spec.modified, spec.chosen?.let(::book), spec.place.lane(), resolveChosen = {
            spec.selection?.let { chosen(spec.place, it)?.toSpec(spec.place, spec.direction)?.let(::book) } ?: spec.chosen?.let(::book)
        }) {
            children(spec.place, spec.path, spec.documentId, spec.direction)
        }
    }

    private fun book(spec: CoverSpec.Book, firstEntry: Int? = null, open: suspend (ImageDecoding) -> BookResult = { openCover(spec, it, firstEntry) }) =
        ThumbSubject.Book(spec.id, spec.modified, spec.byteSize, spec.coverEntry, spec.origin.lane(), spec.coverCrop, firstEntry, open)

    /**
     * What a thumbnail can be made from in the folder at [path] (docs/spec.md section 4.4): archives, PDFs and
     * images. Hidden files are left out whatever the list shows.
     */
    private suspend fun children(place: LibraryLocation, path: String, documentId: String?, direction: ReadingDirection): List<ThumbEntry> =
        books.list(place, path, documentId).mapNotNull { entry ->
            val childPath = normalizePath("$path/${entry.name}")
            val id = locationBookId(place, childPath)
            val modified = entry.modified ?: Instant.EPOCH
            val size = entry.size ?: 0
            when (entry.toItem(showDotFiles = false)) {
                is FileItem.Folder -> ThumbEntry.Directory(entry.name) { children(place, childPath, entry.documentId, direction) }
                is FileItem.Archive -> ThumbEntry.Item(entry.name, book(CoverSpec.Book(id, modified, size, null, direction, CoverOrigin.Archive(place, childPath, entry.documentId))))
                is FileItem.Image -> ThumbEntry.Item(entry.name, book(CoverSpec.Book(id, modified, size, null, direction, CoverOrigin.Image(place, childPath, entry.documentId))))
                is FileItem.Text, null -> null
            }
        }

    private fun CoverOrigin.lane(): ThumbLane = place()?.lane() ?: ThumbLane.Local

    private fun CoverOrigin.place(): LibraryLocation? = when (this) {
        is CoverOrigin.Archive -> place
        is CoverOrigin.Image -> place
        is CoverOrigin.ImageFolder -> place
        is CoverOrigin.Document, is CoverOrigin.Tree -> null
    }

    /**
     * A place reconnecting makes its unavailable covers worth asking for again. The thumbnail source itself
     * retains and observes whether making covers is held, including when a row starts collecting later.
     */
    private fun recoveries(spec: CoverSpec): Flow<Unit> {
        val place = when (spec) {
            is CoverSpec.Book -> spec.origin.place()
            is CoverSpec.Folder -> spec.place
        }
        return place?.let(books::reconnections) ?: emptyFlow()
    }

    private fun LibraryLocation.lane() = if (this is LibraryLocation.SmbShare) ThumbLane.Smb else ThumbLane.Local

    private companion object {
        const val DECODE_PARALLELISM = 2
        const val BYTES_PER_PIXEL = 4

        /** docs/spec.md section 4.4: the images chosen for folders looked up at once. */
        const val CHOSEN_LOOKUPS = 4
    }
}

/** Reads the WebP of a thumbnail, from its file or from the bytes just made. Called off the main thread. */
internal fun decodeCover(image: ThumbImage): ImageBitmap? = when (image) {
    is ThumbImage.Stored -> BitmapFactory.decodeFile(image.file.path)
    is ThumbImage.Fresh -> BitmapFactory.decodeByteArray(image.webp, 0, image.webp.size)
}?.asImageBitmap()
