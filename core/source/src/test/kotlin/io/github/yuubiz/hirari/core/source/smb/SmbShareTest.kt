package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

/** Listing and reading against the Samba fixture (tools/smb-fixture). */
class SmbShareTest {
    private val scope = CoroutineScope(SupervisorJob())

    @Before
    fun setUp() = SmbFixture.require()

    @After
    fun tearDown() = scope.cancel()

    private fun server(share: String = "books", login: SmbLogin = SmbFixture.login(), host: String = SmbFixture.host!!, port: Int = SmbFixture.port) =
        SmbServer(SmbAddress(host, share, port), login, scope)

    private fun SmbByteSource.readAt(offset: Long, length: Int): ByteArray {
        val out = ByteBuffer.allocate(length)
        while (out.hasRemaining()) {
            if (read(offset + out.position(), out) < 0) break
        }
        return out.array().copyOf(out.position())
    }

    @Test
    fun `lists a folder with names, kinds and sizes`() = runBlocking {
        server().use { smb ->
            val root = smb.list("").associateBy { it.name }
            assertEquals(SmbFixture.BIG_SIZE.toLong(), root.getValue("big.bin").size)
            assertEquals(false, root.getValue("big.bin").isDirectory)
            assertEquals(true, root.getValue("many").isDirectory)
            assertEquals(true, root.getValue("漫画").isDirectory)
            assertEquals(0L, root.getValue("empty.bin").size)
            assertTrue("no dot entries", root.keys.none { it == "." || it == ".." })
            assertEquals(300, smb.list("many").size)
            val book = smb.list("漫画").single()
            assertEquals("漫画/第1巻.bin" to 1000L, book.path to book.size)
            assertEquals(SmbConnectionState.Connected, smb.state.value)
        }
    }

    @Test
    fun `reads the bytes at any offset, across blocks and up to the end`() {
        server().use { smb ->
            smb.open(SmbFixture.BIG).use { source ->
                assertEquals(SmbFixture.BIG_SIZE.toLong(), source.size)
                val random = Random(7)
                val offsets = listOf(0L, SMB_READ_BLOCK - 10L, 3L * SMB_READ_BLOCK, SmbFixture.BIG_SIZE - 5000L) +
                    List(40) { random.nextLong(SmbFixture.BIG_SIZE.toLong()) }
                for (offset in offsets) {
                    val length = minOf(listOf(4096, 65536, 300_000).random(random).toLong(), SmbFixture.BIG_SIZE - offset).toInt()
                    val expected = SmbFixture.pattern.copyOfRange(offset.toInt(), offset.toInt() + length)
                    assertArrayEquals("bytes at $offset", expected, source.readAt(offset, length))
                }
                assertEquals(-1, source.read(SmbFixture.BIG_SIZE.toLong(), ByteBuffer.allocate(10)))
            }
        }
    }

    @Test
    fun `reads one file from many threads at once`() {
        server().use { smb ->
            val pool = Executors.newFixedThreadPool(8)
            val failures = (0 until 8).map { t ->
                pool.submit<String?> {
                    smb.open(SmbFixture.BIG).use { source ->
                        val random = Random(t)
                        repeat(20) {
                            val offset = random.nextLong(SmbFixture.BIG_SIZE - 70_000L)
                            val expected = SmbFixture.pattern.copyOfRange(offset.toInt(), offset.toInt() + 65_536)
                            if (!expected.contentEquals(source.readAt(offset, 65_536))) return@submit "thread $t at $offset"
                        }
                    }
                    null
                }
            }.mapNotNull { it.get(120, TimeUnit.SECONDS) }
            pool.shutdown()
            assertEquals(emptyList<String>(), failures)
        }
    }

    @Test
    fun `reads a file with a Japanese name and an empty file`() {
        server().use { smb ->
            smb.open("漫画/第1巻.bin").use { assertArrayEquals(SmbFixture.pattern.copyOf(1000), it.readAt(0, 1000)) }
            smb.open("empty.bin").use { assertEquals(-1, it.read(0, ByteBuffer.allocate(10))) }
        }
    }

    @Test
    fun `no login reads the guest share`() = runBlocking {
        server(share = "public", login = SmbLogin.Guest).use { smb ->
            assertEquals(SmbConnectResult.Connected, smb.check())
            smb.open("small.bin").use { assertArrayEquals(SmbFixture.pattern.copyOf(1000), it.readAt(0, 1000)) }
        }
    }

    @Test
    fun `a share that requires encryption is read over SMB 3`() {
        server(share = "sealed").use { smb ->
            smb.open("small.bin").use { assertArrayEquals(SmbFixture.pattern.copyOf(1000), it.readAt(0, 1000)) }
        }
    }

    @Test
    fun `a missing file is not a lost connection`() {
        server().use { smb ->
            smb.open("small.bin").close()
            try {
                smb.open("missing.bin")
                error("opened a missing file")
            } catch (e: FileNotFoundException) {
                assertEquals(SmbConnectionState.Connected, smb.state.value)
            }
        }
    }

    @Test
    fun `connecting reports why it failed`() = runBlocking {
        val wrongPassword = server(login = SmbLogin.Password(SmbFixture.USER, "wrong".toCharArray())).use { it.check() }
        val noShare = server(share = "nothing").use { it.check() }
        val noName = server(host = "no-such-host.invalid").use { it.check() }
        val closedPort = server(host = "127.0.0.1", port = 1).use { it.check() }
        assertEquals(SmbConnectResult.Failed(SmbConnectFailure.AuthenticationFailed), wrongPassword)
        assertEquals(SmbConnectResult.Failed(SmbConnectFailure.ShareNotFound), noShare)
        assertEquals(SmbConnectResult.Failed(SmbConnectFailure.NameNotResolved), noName)
        assertEquals(SmbConnectResult.Failed(SmbConnectFailure.Unreachable), closedPort)
    }

    @Test
    fun `a server that accepts and never answers fails the connect after the connect timeout`() = runBlocking {
        Relay(SmbFixture.host!!, SmbFixture.port).use { relay ->
            relay.blackHole = true
            server(host = "127.0.0.1", port = relay.port).use { smb ->
                val (millis, result) = timed("connect to a silent server") { runBlocking { smb.check() } }
                assertEquals(SmbConnectResult.Failed(SmbConnectFailure.Unreachable), result.getOrThrow())
                val timeout = SmbTimeouts().connect.inWholeMilliseconds
                assertTrue("gives up after about ${SmbTimeouts().connect}: $millis ms", millis in timeout - 100..timeout + 2.seconds.inWholeMilliseconds)
            }
        }
    }
}
