package io.github.yuubiz.hirari.feature.library

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Trace
import android.provider.DocumentsContract
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.documentInfo
import io.github.yuubiz.hirari.core.source.listFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class SafTreeGrants(private val resolver: ContentResolver) : TreeGrants {
    override suspend fun take(tree: UriString): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.takePersistableUriPermission(Uri.parse(tree.value), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun release(tree: UriString) = withContext(Dispatchers.IO) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(tree.value), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    override suspend fun takeWrite(tree: UriString): Boolean = withContext(Dispatchers.IO) {
        try {
            resolver.takePersistableUriPermission(Uri.parse(tree.value), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    override suspend fun releaseWrite(tree: UriString) = withContext(Dispatchers.IO) {
        try {
            resolver.releasePersistableUriPermission(Uri.parse(tree.value), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
    }

    override suspend fun held(): Set<UriString> = withContext(Dispatchers.IO) {
        resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { UriString(it.uri.toString()) }
    }

    override suspend fun name(tree: UriString): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(tree.value)
        val treeId = DocumentsContract.getTreeDocumentId(uri)
        val name = try {
            resolver.documentInfo(DocumentsContract.buildDocumentUriUsingTree(uri, treeId))?.name
        } catch (_: Exception) {
            null
        }
        name?.takeIf { it.isNotBlank() } ?: treeId.substringAfterLast(':').substringAfterLast('/').ifEmpty { treeId }
    }
}

class SafFolderLister(private val resolver: ContentResolver) : FolderLister {
    override suspend fun list(tree: UriString, path: String, documentId: String?): Listing = withContext(Dispatchers.IO) {
        val uri = Uri.parse(tree.value)
        Trace.beginSection(TRACE_LIST)
        try {
            val folder = resolver.listFolder(uri, path, documentId) ?: return@withContext Listing.Failed(ListFailure.NotFound)
            val entries = folder.children.map { child ->
                FolderEntry(child.name, child.documentId, child.isDirectory, child.size, child.lastModifiedMs?.let(Instant::ofEpochMilli))
            }
            Listing.Listed(folder.documentId, entries)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            Listing.Failed(ListFailure.NoPermission)
        } catch (_: FileNotFoundException) {
            Listing.Failed(ListFailure.NotFound)
        } catch (_: IllegalArgumentException) {
            Listing.Failed(ListFailure.NotFound)
        } catch (_: Exception) {
            Listing.Failed(ListFailure.ReadError)
        } finally {
            Trace.endSection()
        }
    }
}

/** The trace section of docs/spec.md section 5.2, P7: the query of one folder and reading its rows. */
const val TRACE_LIST = "hirari.listFolder"
