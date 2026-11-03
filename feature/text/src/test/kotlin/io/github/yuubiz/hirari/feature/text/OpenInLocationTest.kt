package io.github.yuubiz.hirari.feature.text

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import androidx.test.core.app.ApplicationProvider
import io.github.yuubiz.hirari.core.data.FolderCover
import io.github.yuubiz.hirari.core.data.LocationRepository
import io.github.yuubiz.hirari.core.data.PasswordChange
import io.github.yuubiz.hirari.core.data.PinnedFolder
import io.github.yuubiz.hirari.core.data.SmbServer
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val AUTHORITY = "hirari.test.text"

class HangingTreeProvider : ContentProvider() {
    val queryStarted = CountDownLatch(1)
    val queryCancelled = CountDownLatch(1)

    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor =
        query(uri, projection, selection, selectionArgs, sortOrder, null)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?, cancellationSignal: CancellationSignal?): Cursor {
        queryStarted.countDown()
        val signal = checkNotNull(cancellationSignal) { "the query came without a cancellation signal" }
        signal.setOnCancelListener { queryCancelled.countDown() }
        check(queryCancelled.await(10, TimeUnit.SECONDS)) { "the provider was never told to stop" }
        signal.throwIfCanceled()
        return MatrixCursor(emptyArray())
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}

internal class OnePlace(private val place: LibraryLocation) : LocationRepository {
    override val locations: Flow<List<LibraryLocation>> = emptyFlow()
    override val pinned: Flow<List<PinnedFolder>> = emptyFlow()

    override suspend fun get(id: LocationId): LibraryLocation? = place.takeIf { it.id == id }

    override suspend fun addSafTree(displayName: String, treeUri: UriString): LocationId = error("unused")

    override suspend fun addSmbShare(server: SmbServer, password: CharArray?): LocationId = error("unused")

    override suspend fun updateSmbShare(id: LocationId, server: SmbServer, password: PasswordChange) = error("unused")

    override suspend fun rename(id: LocationId, displayName: String) = error("unused")

    override suspend fun delete(id: LocationId): LibraryLocation? = error("unused")

    override suspend fun syncGrants(granted: Set<UriString>) = error("unused")

    override suspend fun pin(location: LocationId, path: String, at: Instant) = error("unused")

    override suspend fun unpin(id: Long) = error("unused")

    override fun folderCover(location: LocationId, path: String): Flow<FolderCover?> = emptyFlow()

    override suspend fun setFolderCover(location: LocationId, path: String, cover: FolderCover?) = error("unused")
}

@RunWith(RobolectricTestRunner::class)
class OpenInLocationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val provider = Robolectric.buildContentProvider(HangingTreeProvider::class.java).create(AUTHORITY).get()
    private val resolver: ContentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val place = LibraryLocation.SafTree(LocationId(1), "shelf", UriString("content://$AUTHORITY/tree/root"))

    @Test
    fun `leaving the reader cancels the lookup of a text opened from the history by its path`() = runBlocking {
        val open = opener(TextBookRef.InLocation(place.id, "shelf/story.txt"), TextSources(resolver, OnePlace(place), { _, _ -> error("no server") }, temp.newFolder()) { error("no archive") })
        val call = async(Dispatchers.IO) { open.open() }
        assertTrue(provider.queryStarted.await(10, TimeUnit.SECONDS))
        call.cancel()
        withTimeout(10_000) {
            assertFailsWith<CancellationException> { call.await() }
        }
        assertTrue(provider.queryCancelled.await(10, TimeUnit.SECONDS))
    }
}
