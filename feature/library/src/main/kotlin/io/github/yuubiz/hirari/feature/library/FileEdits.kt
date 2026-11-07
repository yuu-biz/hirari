package io.github.yuubiz.hirari.feature.library

import android.content.ContentResolver
import android.net.Uri
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
    data object RecordsNotMoved : FileEditResult
    data class Failed(val reason: FileEditFailure, val name: String = "") : FileEditResult
}

interface FileEditStorage {
    suspend fun list(place: LibraryLocation, path: String): List<FolderEntry>
    suspend fun rename(place: LibraryLocation, path: String, destination: String)
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

    override suspend fun rename(place: LibraryLocation, path: String, destination: String) {
        when (place) {
            is LibraryLocation.SafTree -> resolver.renameInTree(Uri.parse(place.treeUri.value), path, destination.substringAfterLast('/'))
            is LibraryLocation.SmbShare -> smb.rename(place, path, destination)
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
                storage.rename(place, row.path, destination)
                val names = HashMap<String, Map<Int, ByteArray>>()
                val oldIds = try {
                    records.rename(place, row.path, destination) { path, index ->
                        val entries = names[path] ?: when (val result = archives.open(place, path, null, locationBookId(place, path))) {
                            is ArchiveOpenResult.Failed -> throw IOException("cannot read moved archive")
                            is ArchiveOpenResult.Opened -> result.contents.use { contents -> contents.entries.associate { it.entryIndex to it.rawName } }.also { names[path] = it }
                        }
                        entries[index] ?: throw IOException("archive entry is missing")
                    }
                } catch (_: Exception) {
                    return@mutation FileEditResult.RecordsNotMoved
                }
                discardCaches(oldIds)
                FileEditResult.Done
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
                storage.delete(place, row.path, row.item is FileItem.Folder)
                discardCaches(records.delete(place, row.path))
                FileEditResult.Done
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

/** Drops what solid archives of [books] left in the extraction cache under [cacheDir], after a rename or a delete. */
fun removeExtracted(cacheDir: java.io.File, books: Set<io.github.yuubiz.hirari.core.model.BookId>) =
    io.github.yuubiz.hirari.core.archive.ExtractionCache.under(cacheDir).removeBooks(books)
