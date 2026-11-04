package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectionsTest {
    @Test
    fun `a connection that comes back from reconnecting or lost is one comeback each time`() = runTest {
        val states = flowOf(
            SmbConnectionState.Idle,
            SmbConnectionState.Connected,
            SmbConnectionState.Reconnecting(1),
            SmbConnectionState.Reconnecting(2),
            SmbConnectionState.Connected,
            SmbConnectionState.Connected,
            SmbConnectionState.Reconnecting(1),
            SmbConnectionState.Lost,
            SmbConnectionState.Connected,
        )
        assertEquals(2, states.comebacks().toList().size)
    }

    @Test
    fun `the first connection is not a comeback`() = runTest {
        assertEquals(0, flowOf(SmbConnectionState.Idle, SmbConnectionState.Connected).comebacks().toList().size)
    }
}
