package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.data.CredentialStore
import io.github.yuubiz.hirari.core.data.StoredSecret
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.smb.ServerFinder
import io.github.yuubiz.hirari.core.source.smb.SmbLogin
import io.github.yuubiz.hirari.core.source.smb.SmbShares
import io.github.yuubiz.hirari.feature.library.FolderLister
import io.github.yuubiz.hirari.feature.library.Listing
import io.github.yuubiz.hirari.feature.library.SmbjPlaces
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.Sibling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The neighbors of a book in an SMB place, listed on the Samba container of tools/smb-fixture through the shared
 * servers. Runs only with the `-D` options that `tools/smb-fixture/fixture.sh props` prints.
 */
class SmbSiblingsFixtureTest {
    private val host: String? = System.getProperty("hirari.smb.host")
    private val port = System.getProperty("hirari.smb.port")?.toInt() ?: 445
    private val scope = CoroutineScope(SupervisorJob())

    @Before
    fun fixture() = assumeTrue("SMB fixture not configured (tools/smb-fixture/fixture.sh props)", host != null)

    @After
    fun stop() = scope.cancel()

    private object NoSecrets : CredentialStore {
        override suspend fun store(secret: CharArray): CredentialRef = error("not used")

        override suspend fun replace(ref: CredentialRef, secret: CharArray) = error("not used")

        override suspend fun read(ref: CredentialRef): StoredSecret = StoredSecret.Missing

        override suspend fun delete(ref: CredentialRef) = error("not used")
    }

    private object NoSaf : FolderLister {
        override suspend fun list(tree: UriString, path: String, documentId: String?): Listing = error("not a SAF place")
    }

    @Test
    fun theSeriesOnTheServerGivesTheNextAndThePreviousBook() = runBlocking {
        val place = LibraryLocation.SmbShare(LocationId(3), "NAS", host!!, port, "public", "", null, null, null)
        val smb = SmbjPlaces(SmbShares({ SmbLogin.Guest }, scope), NoSecrets, { true }, scope, serverFinder = ServerFinder({ emptyList() }, { emptyFlow() }, { emptyFlow() }))
        val siblings = FolderSiblings(placeFolders({ if (it == place.id) place else null }, NoSaf, smb), dotFiles = { false })
        fun book(path: String, folder: Boolean = false) = BookAddress.InLocation(place.id, path, folder)

        assertEquals(Sibling.Found(book("シリーズ/第2巻.zip")), siblings.beside(book("シリーズ/第1巻.cbz"), BookEdge.End))
        assertEquals(Sibling.Found(book("シリーズ/第10巻.pdf")), siblings.beside(book("シリーズ/第2巻.zip"), BookEdge.End))
        assertEquals(Sibling.Last, siblings.beside(book("シリーズ/第10巻.pdf"), BookEdge.End))
        assertEquals(Sibling.Found(book("シリーズ/おまけ", folder = true)), siblings.beside(book("シリーズ/第1巻.cbz"), BookEdge.Start))
        assertEquals(Sibling.Last, siblings.beside(book("シリーズ/おまけ", folder = true), BookEdge.Start))
    }
}
