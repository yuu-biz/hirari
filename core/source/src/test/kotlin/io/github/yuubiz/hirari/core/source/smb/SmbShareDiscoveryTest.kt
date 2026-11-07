package io.github.yuubiz.hirari.core.source.smb

import org.junit.Test
import kotlin.test.assertEquals
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.TimeoutException

class SmbShareDiscoveryTest {
    @Test
    fun `only ordinary disk shares appear in natural order with their remarks`() {
        val second = DiscoveredShare("books2", 0, "漫画")
        val tenth = DiscoveredShare("books10", 0)
        assertEquals(listOf(second, tenth), diskShares(listOf(
            tenth, DiscoveredShare("IPC$", 3), DiscoveredShare("printer", 1),
            DiscoveredShare("ADMIN$", Int.MIN_VALUE), DiscoveredShare("C$", Int.MIN_VALUE),
            DiscoveredShare("device", 2), second,
        )))
        assertEquals(emptyList(), diskShares(listOf(DiscoveredShare("IPC$", Int.MIN_VALUE or 3))))
    }

    @Test
    fun `RPC refusal differs from a broken or timed out connection`() {
        assertEquals(ShareDiscoveryFailure.Unsupported, rpcFailure(IOException("RPC refused")))
        assertEquals(ShareDiscoveryFailure.Unsupported, rpcFailure(UnsupportedOperationException()))
        assertEquals(ShareDiscoveryFailure.Unreachable, rpcFailure(SocketException("closed")))
        assertEquals(ShareDiscoveryFailure.Unreachable, rpcFailure(IOException(TimeoutException())))
    }
}
