package io.github.yuubiz.hirari.feature.library

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.image.BookResult
import io.github.yuubiz.hirari.core.image.Books
import io.github.yuubiz.hirari.core.image.FolderFile
import io.github.yuubiz.hirari.core.image.ImageDecoding
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.source.locateDocument
import io.github.yuubiz.hirari.core.source.openByteSource
import kotlinx.coroutines.Dispatchers
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
}

/** The books of SAF places and picked documents through [resolver], and of SMB places through [smb]. */
class DeviceCoverBooks(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val folders: FolderLister,
    private val smb: SmbPlaces,
) : CoverBooks {
    private val copyDir get() = File(cacheDir, "copy")

    /** The reader extracts a solid archive into the same directory (docs/spec.md section 3.3), so both share it. */
    private fun extractDir(spec: CoverSpec.Book) = File(cacheDir, "extract/${spec.id.value}")

    override suspend fun open(spec: CoverSpec.Book, decoding: ImageDecoding): BookResult = try {
        when (val origin = spec.origin) {
            is CoverOrigin.Archive -> when (val place = origin.place) {
                is LibraryLocation.SafTree ->
                    Books.openDocument(resolver, documentUri(place, origin.path, origin.documentId), copyDir, decoding, extractDir(spec))
                is LibraryLocation.SmbShare -> Books.openBook(smb.open(place, origin.path), decoding, extractDir(spec))
            }
            is CoverOrigin.Image -> {
                val name = origin.path.substringAfterLast('/')
                val file = when (val place = origin.place) {
                    is LibraryLocation.SafTree -> {
                        val uri = documentUri(place, origin.path, origin.documentId)
                        FolderFile(name, spec.byteSize) { resolver.openByteSource(uri, copyDir) }
                    }
                    is LibraryLocation.SmbShare -> FolderFile(name, spec.byteSize) { runBlocking { smb.open(place, origin.path) } }
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
                        FolderFile(entry.name, entry.size) { runBlocking { smb.open(place, "${origin.path}/${entry.name}") } }
                    }
                    Books.openFolder(files, decoding)
                }
            }
            is CoverOrigin.Document -> Books.openDocument(resolver, Uri.parse(origin.uri.value), copyDir, decoding, extractDir(spec))
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

    override suspend fun list(place: LibraryLocation, path: String, documentId: String?): List<FolderEntry> {
        val listing = when (place) {
            is LibraryLocation.SafTree -> {
                if (!place.permissionHeld) throw SecurityException("the grant of ${place.displayName} is gone")
                folders.list(place.treeUri, path, documentId)
            }
            is LibraryLocation.SmbShare -> smb.list(place, path)
        }
        return when (listing) {
            is Listing.Listed -> listing.entries
            is Listing.Failed -> throw IOException("listing $path failed: ${listing.reason}")
        }
    }
}

private fun Exception.bookFailure(): BookFailure = when (this) {
    is FileNotFoundException, is IllegalArgumentException -> BookFailure.NotFound
    is SecurityException -> BookFailure.NoPermission
    else -> BookFailure.ReadError
}
