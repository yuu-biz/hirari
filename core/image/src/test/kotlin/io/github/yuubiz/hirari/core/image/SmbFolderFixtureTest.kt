package io.github.yuubiz.hirari.core.image

import io.github.yuubiz.hirari.core.model.ImageType
import io.github.yuubiz.hirari.core.model.PageResult
import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.SmbAddress
import io.github.yuubiz.hirari.core.source.smb.SmbLogin
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * A folder of images on the Samba container of tools/smb-fixture, opened as a book. Runs only with the `-D` options
 * that `tools/smb-fixture/fixture.sh props` prints.
 */
class SmbFolderFixtureTest {
    private val host: String? = System.getProperty("hirari.smb.host")
    private val port = System.getProperty("hirari.smb.port")?.toInt() ?: 445
    private val decoding = ImageDecoding(budgetBytes = 64L shl 20, parallelism = 1)

    private fun <T> withServer(block: suspend (SmbServer) -> T): T = runBlocking {
        assumeTrue("SMB fixture not configured (tools/smb-fixture/fixture.sh props)", host != null)
        val scope = CoroutineScope(SupervisorJob())
        val server = SmbServer(SmbAddress(host!!, "books", port), SmbLogin.Password("bookworm7", "hirari-fixture-Pw7q".toCharArray()), scope)
        try {
            block(server)
        } finally {
            server.close()
            scope.cancel()
        }
    }

    @Test
    fun theImagesOfAFolderOnTheServerAreItsPagesInNaturalOrder() = withServer { server ->
        val fetched = AtomicLong()
        val counted = { source: ByteSource ->
            object : ByteSource by source {
                override fun read(offset: Long, dst: ByteBuffer): Int = source.read(offset, dst).also { if (it > 0) fetched.addAndGet(it.toLong()) }
            }
        }
        val book = (Books.openServerFolder(server, "画像", decoding, counted) as BookResult.Opened).book
        assertEquals(listOf("scan", "第1話.png", "第2話.png", "第10話.png"), book.pages.map { it.name })
        assertEquals(ImageType.Png, book.pages[0].sniffed)
        assertEquals(listOf(1008L, 2008L, 3008L, 4008L), book.pages.map { it.declaredSize })

        val before = fetched.get()
        val raw = (book.raw(3) as PageResult.Ready).image as RawPage.Encoded
        assertEquals(ImageType.Png, raw.type)
        assertEquals(4008L, raw.byteCount)
        assertEquals(4008L, fetched.get() - before)
        book.close()
    }

    @Test
    fun aFolderThatIsNotOnTheServerIsNotFound() = withServer { server ->
        assertEquals(BookResult.Failed(BookFailure.NotFound), Books.openServerFolder(server, "ない", decoding))
    }
}
