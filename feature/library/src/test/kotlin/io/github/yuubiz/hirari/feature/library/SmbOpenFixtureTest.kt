package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.CredentialStore
import io.github.yuubiz.hirari.core.data.StoredSecret
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.source.smb.SmbShares
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * Tapping the books, images and texts of a folder of the Samba container of tools/smb-fixture, listed through
 * [SmbjPlaces]. Runs only with the `-Dhirari.smb.*` options that `tools/smb-fixture/fixture.sh props` prints.
 */
class SmbOpenFixtureTest {
    private val host = System.getProperty("hirari.smb.host")
    private val port = System.getProperty("hirari.smb.port")?.toInt() ?: 445
    private val scope = CoroutineScope(SupervisorJob())
    private val fixture = Fixture()

    @Before
    fun fixture() {
        assumeTrue("SMB fixture not configured (tools/smb-fixture/fixture.sh props)", host != null)
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun stop() {
        Dispatchers.resetMain()
        scope.cancel()
    }

    private val place by lazy { LibraryLocation.SmbShare(LocationId(1), "NAS", host!!, port, "public", "", null, null, null) }

    private val services by lazy {
        val credentials = object : CredentialStore {
            override suspend fun store(secret: CharArray): CredentialRef = error("a guest stores no password")

            override suspend fun replace(ref: CredentialRef, secret: CharArray) = error("a guest stores no password")

            override suspend fun read(ref: CredentialRef): StoredSecret = error("a guest reads no password")

            override suspend fun delete(ref: CredentialRef) = error("a guest stores no password")
        }
        val smb = SmbjPlaces(SmbShares({ credentials.smbLogin(it.user, it.credential) }, scope), credentials, { true }, scope)
        fixture.locations.rows.value = listOf(place)
        with(fixture) {
            LibraryServices(locations, books, history, bookmarks, books.progressRepository, settings, grants, lister, smb, archives, launch, coverIndex, covers, clock = { T0 })
        }
    }

    private suspend fun listed(path: String): Pair<FolderViewModel, Map<String, FileRow>> {
        val list = FolderViewModel(CompletableDeferred(services), FolderKey(place.id, path), Dispatchers.Unconfined)
        val content = withTimeout(30.seconds) {
            list.state.first { it.content is FolderContent.Shown || it.content is FolderContent.Failed }.content
        }
        return list to assertIs<FolderContent.Shown>(content).rows.associateBy { it.path }
    }

    @Test
    fun `a book, an image and a text of an SMB folder open their readers by the place and the path`() = runBlocking {
        val (series, books) = listed("シリーズ")
        assertEquals(
            FileTarget.Book(OpenBookRequest(BookAddress.InLocation(place.id, "シリーズ/第2巻.zip", folder = false))),
            series.open(books.getValue("シリーズ/第2巻.zip")),
        )
        assertEquals(
            FileTarget.Book(OpenBookRequest(BookAddress.InLocation(place.id, "シリーズ/第10巻.pdf", folder = false))),
            series.open(books.getValue("シリーズ/第10巻.pdf")),
        )

        val (pictures, rows) = listed("画像")
        assertEquals(
            FileTarget.Book(OpenBookRequest(BookAddress.InLocation(place.id, "画像", folder = true), StartAt.Image("第1話.png"))),
            pictures.open(rows.getValue("画像/第1話.png")),
        )
        assertEquals(FileTarget.Text(TextAddress.InLocation(place.id, "画像/メモ.txt")), pictures.open(rows.getValue("画像/メモ.txt")))
    }

    @Test
    fun `a shortcut to an SMB book or text finds it in the listing of its folder`() = runBlocking {
        assertEquals(
            ShortcutTarget.Book(OpenBookRequest(BookAddress.InLocation(place.id, "シリーズ/第2巻.zip", folder = false))),
            services.resolve(PlaceShortcut(place.id, "シリーズ/第2巻.zip", folder = false, "第2巻.zip")),
        )
        assertEquals(
            ShortcutTarget.Text(TextAddress.InLocation(place.id, "画像/メモ.txt")),
            services.resolve(PlaceShortcut(place.id, "画像/メモ.txt", folder = false, "メモ.txt")),
        )
    }
}
