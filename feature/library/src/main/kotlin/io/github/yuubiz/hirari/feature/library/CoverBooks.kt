package io.github.yuubiz.hirari.feature.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yuubiz.hirari.core.archive.ExtractionCache
import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.Books
import io.github.yuubiz.hirari.core.image.EntryNaming
import io.github.yuubiz.hirari.core.image.FolderFile
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.locateDocument
import io.github.yuubiz.hirari.core.source.openByteSource
import io.github.yuubiz.hirari.core.source.smb.SmbPurpose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the covers read from storage: the books they are made from, and the folders searched for one. Each call
 * does its I/O off the main thread, and only when a thumbnail has to be made.
 */
interface CoverBooks {
    /** Opens the book of [spec] for the thumbnail maker. Throws nothing but cancellation. */
    suspend fun open(spec: CoverSpec.Book, decoding: ImageDecoding): BookResult

    /**
     * The entries directly in the folder at [path] of [place].
     *
     * @throws IOException when the folder cannot be listed
     */
    suspend fun list(place: LibraryLocation, path: String, documentId: String?): List<FolderEntry>

    /**
     * Emits each time the connection to [place] comes back after it broke (docs/spec.md section 6.5), and never for a
     * place that has no connection of its own.
     */
    fun reconnections(place: LibraryLocation): Flow<Unit>
}

/**
 * The books of SAF places and picked documents through [resolver], and of SMB places through [smb]. Everything it reads
 * from a server is background work, so covers leave the server's last request permit to the listing and the reader
 * (docs/spec.md section 8.3).
 */
class DeviceCoverBooks(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val folders: FolderLister,
    private val smb: SmbPlaces,
    private val indexed: BookRepository,
    private val settings: SettingsRepository,
) : CoverBooks {
    private val copyDir get() = File(cacheDir, "copy")

    private suspend fun openDocument(uri: Uri, spec: CoverSpec.Book, decoding: ImageDecoding, naming: EntryNaming): BookResult =
        Books.openDocument(resolver, uri, copyDir, decoding, naming = naming, extractDirOf = { source ->
            withContext(Dispatchers.IO) { coverExtractDir(cacheDir, spec.id, source.size, source.lastModifiedMs) }
        })

    private suspend fun openBook(source: ByteSource, spec: CoverSpec.Book, decoding: ImageDecoding, naming: EntryNaming): BookResult {
        val dir = try {
            withContext(Dispatchers.IO) { coverExtractDir(cacheDir, spec.id, source.size, source.lastModifiedMs) }
        } catch (e: Throwable) {
            source.close()
            throw e
        }
        return Books.openBook(source, decoding, dir, naming)
    }

    override suspend fun open(spec: CoverSpec.Book, decoding: ImageDecoding): BookResult = try {
        val naming = EntryNaming(indexed.overrides(spec.id).first().nameCharset, settings.settings.first().fallbackCharset)
        when (val origin = spec.origin) {
            is CoverOrigin.Archive -> when (val place = origin.place) {
                is LibraryLocation.SafTree ->
                    openDocument(documentUri(place, origin.path, origin.documentId), spec, decoding, naming)
                is LibraryLocation.SmbShare -> openBook(smb.open(place, origin.path, SmbPurpose.Background), spec, decoding, naming)
            }
            is CoverOrigin.Image -> {
                val name = origin.path.substringAfterLast('/')
                val file = when (val place = origin.place) {
                    is LibraryLocation.SafTree -> {
                        val uri = documentUri(place, origin.path, origin.documentId)
                        FolderFile(name, spec.byteSize) { resolver.openByteSource(uri, copyDir) }
                    }
                    is LibraryLocation.SmbShare -> FolderFile(name, spec.byteSize) { runBlocking { smb.open(place, origin.path, SmbPurpose.Background) } }
                }
                Books.openFolder(listOf(file), decoding)
            }
            is CoverOrigin.ImageFolder -> when (val place = origin.place) {
                is LibraryLocation.SafTree -> {
                    val tree = Uri.parse(place.treeUri.value)
                    val id = origin.documentId ?: origin.path.takeIf { it.isNotEmpty() }?.let {
                        withContext(Dispatchers.IO) { resolver.locateDocument(tree, it) } ?: throw FileNotFoundException()
                    }
                    Books.openTreeFolder(resolver, tree, id, copyDir, decoding)
                }
                is LibraryLocation.SmbShare -> {
                    val files = list(place, origin.path, null).filterNot { it.directory }.map { entry ->
                        FolderFile(entry.name, entry.size) { runBlocking { smb.open(place, "${origin.path}/${entry.name}", SmbPurpose.Background) } }
                    }
                    Books.openFolder(files, decoding)
                }
            }
            is CoverOrigin.Document -> openDocument(Uri.parse(origin.uri.value), spec, decoding, naming)
            is CoverOrigin.Tree -> Books.openTreeFolder(resolver, Uri.parse(origin.uri.value), null, copyDir, decoding)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        BookResult.Failed(e.bookFailure())
    }

    private suspend fun documentUri(place: LibraryLocation.SafTree, path: String, documentId: String?): Uri {
        if (!place.permissionHeld) throw SecurityException("the grant of ${place.displayName} is gone")
        val tree = Uri.parse(place.treeUri.value)
        val id = documentId ?: withContext(Dispatchers.IO) { resolver.locateDocument(tree, path) } ?: throw FileNotFoundException()
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    override fun reconnections(place: LibraryLocation): Flow<Unit> = when (place) {
        is LibraryLocation.SafTree -> emptyFlow()
        is LibraryLocation.SmbShare -> smb.reconnections(place)
    }

    override suspend fun list(place: LibraryLocation, path: String, documentId: String?): List<FolderEntry> {
        val listing = when (place) {
            is LibraryLocation.SafTree -> {
                if (!place.permissionHeld) throw SecurityException("the grant of ${place.displayName} is gone")
                folders.list(place.treeUri, path, documentId)
            }
            is LibraryLocation.SmbShare -> smb.list(place, path, SmbPurpose.Background)
        }
        return when (listing) {
            is Listing.Listed -> listing.entries
            is Listing.Failed -> throw IOException("listing $path failed: ${listing.reason}")
        }
    }
}

/**
 * Where a cover reads a solid archive from (docs/spec.md sections 3.3 and 4.4): the directory the reader or the
 * archive list extracts the book into, when it is there, and none otherwise, so that a cover reads up to its entry
 * and writes nothing. Blocks.
 */
internal fun coverExtractDir(cacheDir: File, book: BookId, size: Long, lastModifiedMs: Long?): File? =
    ExtractionCache.directory(cacheDir, book, size, lastModifiedMs).takeIf { it.isDirectory }

private fun Exception.bookFailure(): BookFailure = when (this) {
    is FileNotFoundException, is IllegalArgumentException -> BookFailure.NotFound
    is SecurityException -> BookFailure.NoPermission
    else -> BookFailure.ReadError
}
