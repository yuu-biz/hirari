package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

/**
 * A connect, a listing, reads and every kind of failure with the fixture's test password, then a search for the
 * password in what smbj logged at every level and in the exceptions this module threw (docs/spec.md section 6.6).
 */
class SmbLogHygieneTest {
    private val scope = CoroutineScope(SupervisorJob())
    private val wrongPassword = "hirari-wrong-Pw7q"
    private val thrown = mutableListOf<Throwable>()

    @After
    fun tearDown() = scope.cancel()

    private fun server(share: String = "books", password: String = SmbFixture.PASSWORD, port: Int = SmbFixture.port, host: String = SmbFixture.host!!) =
        SmbServer(
            SmbAddress(host, share, port),
            SmbLogin.Password(SmbFixture.USER, password.toCharArray()),
            scope,
            timeouts = SmbTimeouts(request = 2.seconds),
        )

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            thrown += e
            return
        }
        error("expected a failure")
    }

    @Test
    fun `the test password reaches neither the logs nor the exceptions`() {
        SmbFixture.require()
        CapturedLogs.lines.clear()
        server().use { smb ->
            runBlocking { smb.list("many") }
            smb.open(SmbFixture.BIG).use { it.read(0, ByteBuffer.allocate(4096)) }
            expectFailure { smb.open("missing.bin") }
        }
        server(share = "sealed").use { smb -> smb.open("small.bin").use { it.read(0, ByteBuffer.allocate(10)) } }
        server(password = wrongPassword).use { smb -> expectFailure { smb.open("small.bin") } }
        server(share = "nothing").use { smb -> expectFailure { smb.open("small.bin") } }
        Relay(SmbFixture.host!!, SmbFixture.port).use { relay ->
            server(host = "127.0.0.1", port = relay.port).use { smb ->
                val source = smb.open(SmbFixture.BIG)
                relay.silenceOpenConnections()
                expectFailure { source.read(0, ByteBuffer.allocate(10)) }
                expectFailure { source.read(FAR_OFFSET * 3L, ByteBuffer.allocate(10)) }
            }
        }

        val logs = CapturedLogs.lines.joinToString("\n")
        val exceptions = thrown.joinToString("\n") { it.stackTraceToString() }
        println("SMB captured ${CapturedLogs.lines.size} log lines; user name in smbj logs: ${SmbFixture.USER in logs}")
        println("SMB exceptions: ${thrown.map { it.message }}")
        assertTrue("smbj logged the session", logs.contains("com.hierynomus") && CapturedLogs.lines.size > 50)
        assertTrue("every failure was seen", thrown.size == 5)
        for (secret in listOf(SmbFixture.PASSWORD, wrongPassword)) {
            assertFalse("$secret in the logs", secret in logs)
            assertFalse("$secret in an exception", secret in exceptions)
        }
        assertFalse("the user name in an exception", SmbFixture.USER in exceptions)
        assertFalse("the user name in the login", SmbFixture.USER in SmbLogin.Password(SmbFixture.USER, CharArray(1)).toString())
    }

    @Test
    fun `the dependencies bring no SLF4J provider, so smbj logs nothing in the app`() {
        val providers = java.util.ServiceLoader.load(org.slf4j.spi.SLF4JServiceProvider::class.java)
            .map { it.javaClass.name }
            .filter { it != CapturingProvider::class.java.name }
        assertTrue("providers: $providers", providers.isEmpty())
    }

    @Test
    fun `share discovery keeps passwords out of logs and results`() = runBlocking {
        SmbFixture.require()
        CapturedLogs.lines.clear()
        val lister = SrvsvcShareLister()
        val address = SmbAddress(SmbFixture.host!!, "", SmbFixture.port)
        val login = SmbLogin.Password(SmbFixture.USER, SmbFixture.PASSWORD.toCharArray())
        val listed = lister.list(address, login)
        assertTrue("shares returned", listed is ShareDiscoveryResult.Listed)
        assertTrue("login wiped", login.password.all { it == '\u0000' })
        val failed = lister.list(address, SmbLogin.Password(SmbFixture.USER, wrongPassword.toCharArray()))
        org.junit.Assert.assertEquals(ShareDiscoveryResult.Failed(ShareDiscoveryFailure.AuthenticationFailed), failed)
        val output = CapturedLogs.lines.joinToString("\n") + listed + failed
        assertFalse("password in discovery output", SmbFixture.PASSWORD in output || wrongPassword in output)
    }
}
