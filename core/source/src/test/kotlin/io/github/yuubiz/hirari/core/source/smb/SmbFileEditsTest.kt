package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SmbFileEditsTest {
    @Test fun `rename and recursive delete share one connection and leave common prefix siblings`() = runTest {
        val connector = FakeConnector(mapOf("a/b/book.cbz" to byteArrayOf(1), "a/b/child/text.txt" to byteArrayOf(2), "a/bc/keep.cbz" to byteArrayOf(3)))
        val server = SmbServer(connector, SmbLogin.Guest, backgroundScope, emptyFlow(), StandardTestDispatcher(testScheduler), 1.seconds)
        try {
            server.list("a")
            server.rename("a/b", "a/moved")
            assertEquals(setOf("book.cbz", "child"), server.list("a/moved").map { it.name }.toSet())
            server.delete("a/moved", true)
            assertEquals(listOf("bc"), server.list("a").map { it.name })
            assertEquals(listOf("keep.cbz"), server.list("a/bc").map { it.name })
            assertEquals(1, connector.attempts.get())
            val removed = connector.current.deleted
            assertTrue(removed.indexOf("a/moved/child/text.txt") < removed.indexOf("a/moved/child"))
            assertEquals("a/moved", removed.last())
        } finally { server.close() }
    }

    @Test fun `rename refuses an existing destination without replacing either file`() = runTest {
        val connector = FakeConnector(mapOf("old.cbz" to byteArrayOf(1), "taken.cbz" to byteArrayOf(2)))
        val server = SmbServer(connector, SmbLogin.Guest, backgroundScope, emptyFlow(), StandardTestDispatcher(testScheduler), 1.seconds)
        try {
            assertFailsWith<SmbRequestException> { server.rename("old.cbz", "taken.cbz") }
            assertEquals(setOf("old.cbz", "taken.cbz"), server.list("").map { it.name }.toSet())
        } finally { server.close() }
    }
    @Test fun `partial recursive deletion reports only paths successfully removed`() = runTest {
        val connector = FakeConnector(linkedMapOf("a/blocked.cbz" to byteArrayOf(1), "a/deleted.cbz" to byteArrayOf(2)))
        val server = SmbServer(connector, SmbLogin.Guest, backgroundScope, emptyFlow(), StandardTestDispatcher(testScheduler), 1.seconds)
        try {
            server.list("a")
            connector.current.refuseDelete = "a/blocked.cbz"
            val failure = assertFailsWith<SmbServer.PartialDeleteException> { server.delete("a", true) }
            assertEquals(setOf("a/deleted.cbz"), failure.deletedPaths)
            assertTrue(failure.cause is SmbRequestException)
            assertEquals(listOf("blocked.cbz"), server.list("a").map { it.name })
        } finally { server.close() }
    }

    @Test fun `delete failure before any removal preserves the original permission failure`() = runTest {
        val connector = FakeConnector(mapOf("a/blocked.cbz" to byteArrayOf(1)))
        val server = SmbServer(connector, SmbLogin.Guest, backgroundScope, emptyFlow(), StandardTestDispatcher(testScheduler), 1.seconds)
        try {
            server.list("a")
            connector.current.refuseDelete = "a/blocked.cbz"
            assertFailsWith<SmbRequestException> { server.delete("a", true) }
            assertTrue(connector.current.deleted.isEmpty())
        } finally { server.close() }
    }

}
