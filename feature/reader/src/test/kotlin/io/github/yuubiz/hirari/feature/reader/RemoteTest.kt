package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.source.ByteSource
import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteTest {
    @Test
    fun theConnectionComesBackOnlyAfterItBroke() {
        assertTrue(reconnected(SmbConnectionState.Reconnecting(2), SmbConnectionState.Connected))
        assertTrue(reconnected(SmbConnectionState.Lost, SmbConnectionState.Connected))
        assertFalse(reconnected(SmbConnectionState.Idle, SmbConnectionState.Connected))
        assertFalse(reconnected(null, SmbConnectionState.Connected))
        assertFalse(reconnected(SmbConnectionState.Reconnecting(1), SmbConnectionState.Lost))
    }

    @Test
    fun theStateFollowsTheConnectionAndAPageOnTheDeviceHasNone() {
        val local = readerOf(List(2) { TALL })
        assertEquals(null, local.connection)
        val broken = local.reduce(ReaderEvent.ConnectionChanged(SmbConnectionState.Reconnecting(1)))
        assertTrue(broken.connection.reconnecting)
        assertEquals(local.current, broken.current)
        assertFalse(broken.reduce(ReaderEvent.ConnectionChanged(SmbConnectionState.Connected)).connection.reconnecting)
    }

    @Test
    fun theNoticeShowsWhileReconnectingAndAfterTheAttemptsFail() {
        assertTrue(SmbConnectionState.Reconnecting(1).reconnecting)
        assertTrue(SmbConnectionState.Lost.reconnecting)
        assertFalse(SmbConnectionState.Connected.reconnecting)
        assertFalse((null as SmbConnectionState?).reconnecting)
    }

    @Test
    fun theMeterCountsWhatTheSourceReads() {
        val meter = FetchMeter()
        val source = meter.counted(object : ByteSource {
            override val size = 10L

            override fun read(offset: Long, dst: ByteBuffer): Int {
                if (offset >= size) return -1
                val n = minOf(dst.remaining().toLong(), size - offset).toInt()
                dst.position(dst.position() + n)
                return n
            }

            override fun close() = Unit
        })
        source.read(0, ByteBuffer.allocate(4))
        source.read(4, ByteBuffer.allocate(100))
        source.read(10, ByteBuffer.allocate(4))
        assertEquals(10, meter.bytes)
    }

    @Test
    fun progressIsTheShareOfWhatTheViewNeedsAndTheRateSinceTheLastSample() {
        val progress = fetchProgress(FetchSample(0, 1000), FetchSample(500, 1000 + 400_000), FetchSample(1000, 1000 + 900_000), expected = 2_000_000)
        assertEquals(FetchProgress(FetchSource.Server, 45, 1_000_000), progress)
    }

    @Test
    fun progressStopsShortOfDoneSinceTheDeclaredSizeIsNotTheCompressedOne() {
        assertEquals(99, fetchProgress(FetchSample(0, 0), FetchSample(0, 0), FetchSample(10, 5_000), expected = 1_000).percent)
    }

    @Test
    fun aViewWhoseDataIsInMemoryIsOnlyDecoding() {
        assertEquals(FetchSource.Memory, fetchProgress(FetchSample(0, 0), FetchSample(0, 0), FetchSample(10, 0), expected = 0).source)
    }
}
