package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.ItemRecords
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.smb.SmbRequestException
import kotlinx.coroutines.test.runTest
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileEditsTest {
    private val place = LibraryLocation.SafTree(LocationId(1), "本", UriString("content://books/tree/root"))
    private val events = mutableListOf<String>()
    private var failure: Exception? = null
    private var recordFailure = false
    private val row = FileRow(FileItem.Archive(FolderEntry("old.CBZ", "old", false, 1, null, true, true), ArchiveKind.Zip), "a/old.CBZ", "old")
    private var siblings = listOf(row.item.entry)
    private val edits = FileEdits(object : FileEditStorage {
        override suspend fun list(place: LibraryLocation, path: String): List<FolderEntry> = siblings
        override suspend fun rename(place: LibraryLocation, path: String, destination: String) {
            events += "storage:$path:$destination"
            failure?.let { throw it }
        }
        override suspend fun delete(place: LibraryLocation, path: String, directory: Boolean) {
            events += "delete:$path"
            failure?.let { throw it }
        }
    }, object : ItemRecords {
        override suspend fun rename(place: LibraryLocation, oldPath: String, newPath: String, entryName: suspend (String, Int) -> ByteArray): Set<BookId> {
            events += "records:$oldPath:$newPath"
            if (recordFailure) throw IOException()
            return setOf(BookId("old"))
        }
        override suspend fun delete(place: LibraryLocation, path: String): Set<BookId> {
            events += "records-delete:$path"
            return setOf(BookId("old"))
        }
    }, ArchiveOpener { _, _, _, _ -> error("no archive text") }, { ids -> events += "cache:${ids.single().value}" })

    @Test fun `failed storage rename and delete never move or remove records or caches`() = runTest {
        failure = SecurityException()
        assertEquals(FileEditResult.Failed(FileEditFailure.NoPermission, "new.CBZ"), edits.rename(place, row, "new"))
        assertEquals(listOf("storage:a/old.CBZ:a/new.CBZ"), events)
        events.clear()
        assertIs<FileEditResult.Failed>(edits.delete(place, row))
        assertEquals(listOf("delete:a/old.CBZ"), events)
    }

    @Test fun `rename keeps the extension and changes storage before records and caches`() = runTest {
        assertEquals(FileEditResult.Done, edits.rename(place, row, "new"))
        assertEquals(listOf("storage:a/old.CBZ:a/new.CBZ", "records:a/old.CBZ:a/new.CBZ", "cache:old"), events)
    }

    @Test fun `duplicate names are reported by name before any mutation`() = runTest {
        siblings += row.item.entry.copy(name = "new.CBZ")
        assertEquals(FileEditResult.Failed(FileEditFailure.NameExists, "new.CBZ"), edits.rename(place, row, "new"))
        assertTrue(events.isEmpty())
    }

    @Test fun `invalid names never reach storage and records`() = runTest {
        assertEquals(FileEditResult.Failed(FileEditFailure.InvalidName), edits.rename(place, row, "../new"))
        assertTrue(events.isEmpty())
    }

    @Test fun `a record failure after a successful rename has the distinct partial success result`() = runTest {
        recordFailure = true
        assertEquals(FileEditResult.RecordsNotMoved, edits.rename(place, row, "new"))
        assertEquals(listOf("storage:a/old.CBZ:a/new.CBZ", "records:a/old.CBZ:a/new.CBZ"), events)
    }

    @Test fun `delete changes storage before removing records and cache`() = runTest {
        assertEquals(FileEditResult.Done, edits.delete(place, row))
        assertEquals(listOf("delete:a/old.CBZ", "records-delete:a/old.CBZ", "cache:old"), events)
    }

    @Test fun `storage error categories include SMB permission collision missing and connection failures`() {
        assertEquals(FileEditFailure.NoPermission, SmbRequestException("STATUS_ACCESS_DENIED").fileEditFailure())
        assertEquals(FileEditFailure.NameExists, SmbRequestException("STATUS_OBJECT_NAME_COLLISION").fileEditFailure())
        assertEquals(FileEditFailure.NotFound, FileNotFoundException().fileEditFailure())
        assertEquals(FileEditFailure.Unreachable, io.github.yuubiz.hirari.core.source.smb.ConnectionLostException("lost").fileEditFailure())
        assertEquals(FileEditFailure.Other, IOException().fileEditFailure())
    }
}
