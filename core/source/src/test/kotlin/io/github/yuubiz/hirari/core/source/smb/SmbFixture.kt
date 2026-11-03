package io.github.yuubiz.hirari.core.source.smb

import org.junit.Assume.assumeTrue
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * The Samba container of tools/smb-fixture. Its tests run only when `-Dhirari.smb.host` is given, as printed by
 * `tools/smb-fixture/fixture.sh props`, and are skipped otherwise.
 */
object SmbFixture {
    const val USER = "bookworm7"
    const val PASSWORD = "hirari-fixture-Pw7q"
    const val BIG = "big.bin"
    const val BIG_SIZE = 8_388_731

    val host: String? get() = System.getProperty("hirari.smb.host")
    val port: Int get() = System.getProperty("hirari.smb.port")?.toInt() ?: 445
    val container: String? get() = System.getProperty("hirari.smb.container")

    fun require() = assumeTrue("SMB fixture not configured (tools/smb-fixture/fixture.sh props)", host != null)

    fun requireContainer() {
        require()
        assumeTrue("SMB fixture container not named (-Dhirari.smb.container)", container != null)
    }

    fun login() = SmbLogin.Password(USER, PASSWORD.toCharArray())

    /** The bytes of the fixture's pattern files: the AES-128-CTR keystream with key 00..0f and IV 0. */
    val pattern: ByteArray by lazy {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(16) { it.toByte() }, "AES"), IvParameterSpec(ByteArray(16)))
        cipher.doFinal(ByteArray(BIG_SIZE))
    }

    fun docker(command: String) {
        val process = ProcessBuilder("docker", command, container).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "docker $command failed" }
    }
}

/**
 * A TCP relay in front of the fixture. [answerDelayMillis] holds back each chunk the server sends.
 * [silenceOpenConnections] makes the relayed connections drop everything in both directions while their sockets
 * stay open (no FIN, no RST), as a Wi-Fi that went away does; later connections are relayed normally.
 * [resetOpenConnections] ends them with a reset instead.
 */
class Relay(private val host: String, private val targetPort: Int) : Closeable {
    private val server = ServerSocket().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
    val port: Int get() = server.localPort
    val accepted = AtomicInteger()

    @Volatile
    var answerDelayMillis = 0L

    /** New connections are accepted and then never answered. */
    @Volatile
    var blackHole = false

    private val links = CopyOnWriteArrayList<Link>()

    private inner class Link(val client: Socket, val target: Socket?) {
        @Volatile
        var silent = false

        fun pump(from: InputStream, to: OutputStream, delayed: Boolean) = thread(isDaemon = true) {
            val buffer = ByteArray(64 * 1024)
            runCatching {
                while (true) {
                    val n = from.read(buffer)
                    if (n < 0) break
                    if (delayed && answerDelayMillis > 0) Thread.sleep(answerDelayMillis)
                    if (!silent) {
                        to.write(buffer, 0, n)
                        to.flush()
                    }
                }
            }
            if (!silent) close()
        }

        fun close() {
            runCatching { client.close() }
            runCatching { target?.close() }
        }
    }

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                accepted.incrementAndGet()
                if (blackHole) {
                    links += Link(client, null)
                    continue
                }
                val target = Socket(host, targetPort)
                val link = Link(client, target)
                links += link
                link.pump(client.getInputStream(), target.getOutputStream(), delayed = false)
                link.pump(target.getInputStream(), client.getOutputStream(), delayed = true)
            }
        }
    }

    fun silenceOpenConnections() = links.forEach { it.silent = true }

    /** Resets the relayed connections on the client's side, as Android does to the sockets of an app in the background. */
    fun resetOpenConnections() = links.forEach { link ->
        runCatching { link.client.setSoLinger(true, 0) }
        link.close()
    }

    override fun close() {
        runCatching { server.close() }
        links.forEach { it.close() }
    }
}

/** Runs [block] and returns how long it took and what it threw, printing both for the test report. */
fun <T> timed(what: String, block: () -> T): Pair<Long, Result<T>> {
    val start = System.nanoTime()
    val result = runCatching(block)
    val millis = (System.nanoTime() - start) / 1_000_000
    println("SMB $what: $millis ms -> ${result.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "ok"}")
    return millis to result
}
