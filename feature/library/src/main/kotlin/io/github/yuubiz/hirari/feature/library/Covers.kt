package io.github.yuubiz.hirari.feature.library

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.yuubiz.hirari.core.image.thumbnail.FolderEntry as ThumbEntry
import io.github.yuubiz.hirari.core.image.thumbnail.PagePreview
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
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
    memoryBytes: Long,
    private val density: () -> Float,
    decodeContext: CoroutineContext = Dispatchers.IO.limitedParallelism(DECODE_PARALLELISM),
    decode: (File) -> ImageBitmap? = ::decodeCover,
) {
    private val loader = CoverLoader(source, MemoryImages(memoryBytes) { it.width * it.height * BYTES_PER_PIXEL }, decodeContext, decode)

    /** The long side of the thumbnails of a list of [size], on this display. */
    fun sizePx(size: ThumbSize): Int = coverSizePx(size, density())

    /** Every size the app can show, [first] before the others: the sizes a regeneration remakes. */
    fun sizesFrom(first: ThumbSize): List<Int> = (listOf(first) + ThumbSize.entries).map(::sizePx).distinct()

    /**
     * The request for [ref]. The only place a request is made, so showing, preparing and regenerating a cover name
     * the same thumbnail in the store.
     */
    fun request(ref: CoverRef, priority: ThumbPriority): ThumbRequest =
        ThumbRequest(subject(ref.spec), ref.sizePx, ref.spec.direction, priority)

    fun peek(ref: CoverRef): ImageBitmap? = loader.peek(ref.key)

    /** What a row on screen shows, with the visible priority; collect it while the row is bound. */
    fun show(ref: CoverRef): Flow<CoverState<ImageBitmap>> = loader.show(ref.key, request(ref, ThumbPriority.Visible))

    /** Prepares the covers of items near the screen, with the nearby priority, until their jobs are cancelled. */
    fun prefetcher(scope: CoroutineScope): Prefetcher<CoverRef> =
        Prefetcher.forRefs(scope) { ref -> loader.warm(request(ref, ThumbPriority.Nearby)).collect {} }

    /**
     * サムネイルを作り直す for [spec] at each of [sizes], the first being the one on screen: forgets it in the store,
     * makes it again, and tells the rows showing it to look again.
     */
    suspend fun regenerate(spec: CoverSpec, sizes: List<Int>) {
        for (sizePx in sizes) {
            val ref = CoverRef(spec, sizePx)
            source.regenerate(request(ref, ThumbPriority.Visible))
            loader.refresh(ref.key)
        }
    }

    /**
     * The page with the `entryIndex` [entryIndex] of the book of [spec], whole and fitted into [maxSidePx], for
     * choosing a range of it as the cover (docs/spec.md section 4.4). The book opens as a cover's does, in its lane.
     */
    suspend fun preview(spec: CoverSpec.Book, entryIndex: Int, maxSidePx: Int): PagePreview =
        source.preview(book(spec), entryIndex, maxSidePx)

    /**
     * The listing entry of the item at [path] of [place], found in its folder, or `null` when the folder cannot be
     * listed or the item is not in it. The date and size of the entry name the thumbnail.
     */
    suspend fun find(place: LibraryLocation, path: String): FolderEntry? {
        val name = path.substringAfterLast('/')
        return try {
            books.list(place, path.substringBeforeLast('/', ""), null).firstOrNull { normalizePath(it.name) == name }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    suspend fun chosen(place: LibraryLocation, cover: FolderCover): ChosenCover? =
        find(place, cover.imagePath)?.let { ChosenCover(cover.imagePath, cover.entry, it) }

    /**
     * Looks up in its folder the image or page chosen for each folder of [chosen], by the folder's path (docs/spec.md
     * section 4.4). The first value has every folder pending, and each lookup that ends takes its folder out, found or
     * not, so no folder waits for another. The lookups start in the order of [chosen], at most [CHOSEN_LOOKUPS] at once.
     */
    fun chosenCovers(place: LibraryLocation, chosen: Map<String, FolderCover>): Flow<ChosenCovers> {
        val lookups = Semaphore(CHOSEN_LOOKUPS)
        return channelFlow {
            for ((path, cover) in chosen) launch { send(path to lookups.withPermit { chosen(place, cover) }) }
        }.scan(ChosenCovers(emptyMap(), chosen.keys)) { covers, (path, found) ->
            ChosenCovers(if (found == null) covers.found else covers.found + (path to found), covers.pending - path)
        }
    }

    /** The spec of the folder at [path], which its own entry in the parent folder dates, or `null` when that cannot be found. */
    suspend fun folderSpec(place: LibraryLocation, path: String, direction: ReadingDirection, chosen: ChosenCover?): CoverSpec.Folder? {
        val entry = find(place, path) ?: return null
        return CoverSpec.Folder(locationBookId(place, path), entry.modified ?: Instant.EPOCH, direction, place, path, entry.documentId, chosen?.toSpec(place, direction))
    }

    private fun subject(spec: CoverSpec): ThumbSubject = when (spec) {
        is CoverSpec.Book -> book(spec)
        is CoverSpec.Folder -> ThumbSubject.Folder(spec.id, spec.modified, spec.chosen?.let(::book)) {
            children(spec.place, spec.path, spec.documentId, spec.direction)
        }
    }

    private fun book(spec: CoverSpec.Book) = ThumbSubject.Book(spec.id, spec.modified, spec.byteSize, spec.coverEntry, spec.origin.lane(), spec.coverCrop) { decoding ->
        books.open(spec, decoding)
    }

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

    private fun CoverOrigin.lane(): ThumbLane = when (this) {
        is CoverOrigin.Archive -> place.lane()
        is CoverOrigin.Image -> place.lane()
        is CoverOrigin.ImageFolder -> place.lane()
        is CoverOrigin.Document, is CoverOrigin.Tree -> ThumbLane.Local
    }

    private fun LibraryLocation.lane() = if (this is LibraryLocation.SmbShare) ThumbLane.Smb else ThumbLane.Local

    private companion object {
        const val DECODE_PARALLELISM = 2
        const val BYTES_PER_PIXEL = 4

        /** docs/spec.md section 4.4: the images chosen for folders looked up at once. */
        const val CHOSEN_LOOKUPS = 4
    }
}

/** The images chosen for the folders of a list, by folder path: those found, and those still being looked up. */
data class ChosenCovers(val found: Map<String, ChosenCover>, val pending: Set<String>)

/** Reads the WebP of a thumbnail. Called off the main thread. */
internal fun decodeCover(file: File): ImageBitmap? = BitmapFactory.decodeFile(file.path)?.asImageBitmap()
