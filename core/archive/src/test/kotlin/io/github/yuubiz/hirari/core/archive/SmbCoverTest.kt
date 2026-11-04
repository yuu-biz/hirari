package io.github.yuubiz.hirari.core.archive

import io.github.yuubiz.hirari.core.model.NaturalOrder
import io.github.yuubiz.hirari.core.source.smb.SmbAddress
import io.github.yuubiz.hirari.core.source.smb.SmbLogin
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The SMB cover target P11 of docs/spec.md section 5.2, against `cover.zip` of the Samba fixture (tools/smb-fixture):
 * 200 stored pages of 150,000 bytes, the cover in the middle. Runs only with `-Dhirari.smb.host`, as printed by
 * `tools/smb-fixture/fixture.sh props`.
 */
class SmbCoverTest {
    private val host = System.getProperty("hirari.smb.host")
    private val port = System.getProperty("hirari.smb.port")?.toInt() ?: 445

    private class LibarchiveCalled : RuntimeException()

    /** The fixture's pages: the start of the AES-128-CTR keystream with key 00..0f and IV 0. */
    private val page: ByteArray by lazy {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(16) { it.toByte() }, "AES"), IvParameterSpec(ByteArray(16)))
        cipher.doFinal(ByteArray(PAGE_SIZE))
    }

    @Test
    fun `a ZIP cover over SMB takes at most ten requests and little more than the central directory and the cover`() {
        assumeTrue("SMB fixture not configured (tools/smb-fixture/fixture.sh props)", host != null)
        val scope = CoroutineScope(SupervisorJob())
        val login = SmbLogin.Password("bookworm7", "hirari-fixture-Pw7q".toCharArray())
        SmbServer(SmbAddress(host!!, "books", port), login, scope).use { server ->
            val directorySize = server.open(BOOK).use { book ->
                val end = ByteBuffer.allocate(END_SIZE)
                book.read(book.size - END_SIZE, end)
                (0 until 4).sumOf { (end.get(12 + it).toLong() and 0xff) shl (8 * it) }
            }
            repeat(RUNS) { run ->
                val source = server.open(BOOK)
                val started = System.nanoTime()
                // The book opener reads the first kilobyte to tell a PDF or an image from an archive (3.2).
                source.read(0, ByteBuffer.allocate(1024))
                val session = (runBlocking { ArchiveSession.open(source, null) { throw LibarchiveCalled() } } as OpenResult.Opened).session
                session.use {
                    val cover = session.entries.minWith(compareBy(NaturalOrder) { session.name(it, Charsets.UTF_8) })
                    val data = runBlocking { session.read(cover.entryIndex) }
                    val millis = (System.nanoTime() - started) / 1_000_000
                    val traffic = source.traffic
                    val budget = directorySize + PAGE_SIZE + SLACK
                    println("P11 run $run: cover entry ${cover.entryIndex} of ${session.entries.size}, ${traffic.requests} requests, ${traffic.bytes} bytes (budget $budget), $millis ms")
                    assertArrayEquals(page, (data as EntryResult.Data).bytes)
                    assertTrue("${traffic.requests} requests", traffic.requests <= MAX_REQUESTS)
                    assertTrue("${traffic.bytes} bytes, more than $budget", traffic.bytes <= budget)
                }
            }
        }
        scope.cancel()
    }

    private companion object {
        const val BOOK = "cover.zip"
        const val PAGE_SIZE = 150_000
        const val END_SIZE = 22
        const val RUNS = 5
        const val MAX_REQUESTS = 10
        const val SLACK = 256L * 1024
    }
}
