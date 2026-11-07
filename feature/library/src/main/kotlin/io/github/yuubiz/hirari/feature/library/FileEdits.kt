package io.github.yuubiz.hirari.feature.library

import android.content.ContentResolver
import android.net.Uri
import io.github.yuubiz.hirari.core.data.ChangedFolderCover
import io.github.yuubiz.hirari.core.data.ItemRecords
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.model.normalizePath
import io.github.yuubiz.hirari.core.source.EditableName
import io.github.yuubiz.hirari.core.source.NameExistsException
import io.github.yuubiz.hirari.core.source.countDescendants
import io.github.yuubiz.hirari.core.source.deleteInTree
import io.github.yuubiz.hirari.core.source.renameInTree
import io.github.yuubiz.hirari.core.source.validFileStem
import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import io.github.yuubiz.hirari.core.source.smb.PasswordUnreadableException
import io.github.yuubiz.hirari.core.source.smb.SmbConnectException
import io.github.yuubiz.hirari.core.source.smb.SmbConnectFailure
import io.github.yuubiz.hirari.core.source.smb.SmbRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException
import java.text.Normalizer

enum class FileEditFailure { InvalidName, NoPermission, NotFound, NameExists, Unreachable, Other }

sealed interface FileEditResult {
    data object Done : FileEditResult
    data class Renamed(val actualName: String?, val skipped: Int = 0, val recordsNotMoved: Boolean = false) : FileEditResult
    data object RecordsNotMoved : FileEditResult
    data object PartialDelete : FileEditResult
    data class Failed(val reason: FileEditFailure, val name: String = "") : FileEditResult
}

interface FileEditStorage {
    suspend fun list(place: LibraryLocation, path: String): List<FolderEntry>
    /** Returns the actual path after the provider has chosen the display name. */
    suspend fun rename(place: LibraryLocation, path: String, destination: String): String
    suspend fun delete(place: LibraryLocation, path: String, directory: Boolean)
}

class DeviceFileEditStorage(private val resolver: ContentResolver, private val folders: FolderLister, private val smb: SmbPlaces) : FileEditStorage {
    override suspend fun list(place: LibraryLocation, path: String): List<FolderEntry> {
        val listing = when (place) {
            is LibraryLocation.SafTree -> {
                if (!place.permissionHeld) throw SecurityException()
                folders.list(place.treeUri, path, null)
            }
            is LibraryLocation.SmbShare -> smb.list(place, path)
        }
        return when (listing) {
            is Listing.Listed -> listing.entries
            is Listing.Failed -> throw when (listing.reason) {
                ListFailure.NoPermission, ListFailure.AuthenticationFailed, ListFailure.PasswordUnreadable, ListFailure.NoLocalNetwork -> SecurityException()
                ListFailure.NotFound -> FileNotFoundException()
                ListFailure.Unreachable -> ConnectionLostException("listing failed")
                ListFailure.ReadError -> IOException("listing failed")
            }
        }
    }

    override suspend fun rename(place: LibraryLocation, path: String, destination: String): String =
        when (place) {
            is LibraryLocation.SafTree -> {
                val actualName = resolver.renameInTree(Uri.parse(place.treeUri.value), path, destination.substringAfterLast('/'))
                normalizePath("${destination.substringBeforeLast('/', "")}/$actualName")
            }
            is LibraryLocation.SmbShare -> {
                smb.rename(place, path, destination)
                destination
            }
        }

    override suspend fun delete(place: LibraryLocation, path: String, directory: Boolean) {
        when (place) {
            is LibraryLocation.SafTree -> resolver.deleteInTree(Uri.parse(place.treeUri.value), path)
            is LibraryLocation.SmbShare -> smb.delete(place, path, directory)
        }
    }
}

/** Storage first, then an atomic record update. Once a mutation starts, leaving the screen cannot split them. */
class FileEdits(
    private val storage: FileEditStorage,
    private val records: ItemRecords,
    private val archives: ArchiveOpener,
    private val discardCaches: suspend (Set<BookId>) -> Unit,
    private val remakeFolderCovers: suspend (Set<ChangedFolderCover>) -> Unit = {},
) {
    suspend fun count(place: LibraryLocation, path: String, progress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        countDescendants(path, { parent -> storage.list(place, parent).map { child ->
            normalizePath("$parent/${child.name}") to child.directory
        } }, progress)
    }

    suspend fun rename(place: LibraryLocation, row: FileRow, stem: String): FileEditResult = withContext(Dispatchers.IO) {
        if (!validFileStem(stem)) return@withContext FileEditResult.Failed(FileEditFailure.InvalidName)
        val name = Normalizer.normalize(EditableName.of(row.item.entry.name, row.item is FileItem.Folder).fullName(stem), Normalizer.Form.NFC)
        val parent = row.path.substringBeforeLast('/', "")
        val destination = normalizePath("$parent/$name")
        if (destination == row.path) return@withContext FileEditResult.Done
        try {
            requireEditable(place, row, rename = true)
            if (storage.list(place, parent).any { Normalizer.normalize(it.name, Normalizer.Form.NFC) == name }) throw NameExistsException(name)
            withContext(NonCancellable) mutation@ {
                val actual = storage.rename(place, row.path, destination)
                val actualName = actual.substringAfterLast('/').takeIf { it != name }
                val names = HashMap<String, Map<Int, ByteArray>>()
                val oldIds = try {
                    records.rename(place, row.path, actual) { path, index ->
                        val entries = names[path] ?: when (val result = archives.open(place, path, null, locationBookId(place, path))) {
                            is ArchiveOpenResult.Failed -> throw IOException("cannot read moved archive")
                            is ArchiveOpenResult.Opened -> result.contents.use { contents -> contents.entries.associate { it.entryIndex to it.rawName } }.also { names[path] = it }
                        }
                        entries[index] ?: throw IOException("archive entry is missing")
                    }
                } catch (_: Exception) {
                    return@mutation if (actualName == null) FileEditResult.RecordsNotMoved else FileEditResult.Renamed(actualName, recordsNotMoved = true)
                }
                discardCaches(oldIds.oldIds)
                remakeFolderCovers(oldIds.folderCovers)
                if (actualName != null || oldIds.skipped > 0) FileEditResult.Renamed(actualName, oldIds.skipped) else FileEditResult.Done
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileEditResult.Failed(e.fileEditFailure(), (e as? NameExistsException)?.name ?: name)
        }
    }

    suspend fun delete(place: LibraryLocation, row: FileRow): FileEditResult = withContext(Dispatchers.IO) {
        try {
            requireEditable(place, row, rename = false)
            withContext(NonCancellable) {
                try {
                    storage.delete(place, row.path, row.item is FileItem.Folder)
                    val removed = records.delete(place, row.path)
                    discardCaches(removed.oldIds)
                    remakeFolderCovers(removed.folderCovers)
                    FileEditResult.Done
                } catch (e: io.github.yuubiz.hirari.core.source.smb.SmbServer.PartialDeleteException) {
                    val base = normalizePath((place as LibraryLocation.SmbShare).basePath)
                    val ids = linkedSetOf<BookId>()
                    val folders = linkedSetOf<ChangedFolderCover>()
                    for (deleted in e.deletedPaths) {
                        val relative = if (base.isEmpty()) deleted else deleted.removePrefix("$base/")
                        val removed = records.delete(place, relative)
                        ids += removed.oldIds
                        folders += removed.folderCovers
                    }
                    discardCaches(ids)
                    remakeFolderCovers(folders)
                    FileEditResult.PartialDelete
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileEditResult.Failed(e.fileEditFailure())
        }
    }

    private fun requireEditable(place: LibraryLocation, row: FileRow, rename: Boolean) {
        require(row.pseudo == null && normalizePath(row.path).isNotEmpty() && row.item.entry.name !in listOf(".", ".."))
        if (place is LibraryLocation.SafTree && (!place.permissionHeld || !(if (rename) row.item.entry.canRename else row.item.entry.canDelete))) throw SecurityException()
    }
}

internal fun Exception.fileEditFailure(): FileEditFailure = when (this) {
    is SecurityException, is PasswordUnreadableException -> FileEditFailure.NoPermission
    is FileNotFoundException -> FileEditFailure.NotFound
    is NameExistsException -> FileEditFailure.NameExists
    is ConnectionLostException -> FileEditFailure.Unreachable
    is SmbConnectException -> when (failure) {
        SmbConnectFailure.AuthenticationFailed -> FileEditFailure.NoPermission
        SmbConnectFailure.ShareNotFound -> FileEditFailure.NotFound
        else -> FileEditFailure.Unreachable
    }
    is SmbRequestException -> when (status) {
        "STATUS_ACCESS_DENIED", "STATUS_CANNOT_DELETE", "STATUS_MEDIA_WRITE_PROTECTED" -> FileEditFailure.NoPermission
        "STATUS_OBJECT_NAME_COLLISION" -> FileEditFailure.NameExists
        else -> FileEditFailure.Other
    }
    else -> FileEditFailure.Other
}

/** Leaves open extraction directories to the cache's next trim (docs/spec.md section 7). */
fun removeExtracted(cacheDir: java.io.File, books: Set<BookId>) =
    io.github.yuubiz.hirari.core.archive.ExtractionCache.under(cacheDir).removeBooks(books)
