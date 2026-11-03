package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.save.SaveFailure
import io.github.yuubiz.hirari.core.source.save.SaveOutcome
import io.github.yuubiz.hirari.core.source.save.SaveProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSavesTest {
    private val place = LibraryLocation.SmbShare(LocationId(2), "NAS", "nas.local", 445, "books", "漫画", null, null, null)
    private val tree = UriString("content://tree/books")
    private val smb = FakeSmb()
    private val folders = FakeSaveFolders()

    private fun request(name: String = "vol1.cbz", directory: Boolean = false) = SaveRequest(place, "作品/$name", name, directory, tree)

    @Test
    fun `a save reports its progress while it runs and is announced when it is done`() = runTest {
        val finished = mutableListOf<SaveFinished>()
        val saves = DeviceSaves(smb, folders, backgroundScope, finished::add)
        val announced = mutableListOf<SaveFinished>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { while (true) announced += saves.nextUnshown() }
        val release = CompletableDeferred<Unit>()
        smb.saving = { onProgress ->
            onProgress(SaveProgress(512, 1024, "vol1.cbz", 0, 1))
            release.await()
            SaveOutcome.Saved("vol1 (1).cbz", 1, 1024)
        }

        val id = saves.start(request())
        testScheduler.runCurrent()

        assertEquals(listOf(SaveJob(id, "vol1.cbz", tree, SaveProgress(512, 1024, "vol1.cbz", 0, 1))), saves.jobs.value)
        assertEquals("50% 512 B / 1.0 KB", saves.jobs.value.single().progress!!.text())
        val saved = smb.saved.single()
        assertEquals(place, saved.place)
        assertEquals("作品/vol1.cbz", saved.path)
        assertEquals(false, saved.directory)
        assertEquals(listOf(tree), folders.opened)

        release.complete(Unit)
        testScheduler.runCurrent()
        assertTrue(saves.jobs.value.isEmpty())
        val done: SaveFinished = SaveFinished.Saved("vol1.cbz", "vol1 (1).cbz")
        assertEquals(listOf(done), finished)
        assertEquals(listOf(done), announced)
    }

    @Test
    fun `ends that come while no screen asks wait for the next screen and go to one screen only`() = runTest {
        val saves = DeviceSaves(smb, folders, backgroundScope)
        smb.saving = { SaveOutcome.Failed(SaveFailure.ConnectionLost) }
        saves.start(request("a.cbz"))
        testScheduler.runCurrent()
        smb.saving = { SaveOutcome.Saved("b.cbz", 1, 10) }
        saves.start(request("b.cbz"))
        testScheduler.runCurrent()

        val first = mutableListOf<SaveFinished>()
        val second = mutableListOf<SaveFinished>()
        val screen = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { while (true) first += saves.nextUnshown() }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { while (true) second += saves.nextUnshown() }
        testScheduler.runCurrent()
        assertEquals(listOf(SaveFinished.Failed("a.cbz", SaveFailure.ConnectionLost), SaveFinished.Saved("b.cbz", "b.cbz")), first + second)

        screen.cancel()
        smb.saving = { SaveOutcome.Saved("c.cbz", 1, 10) }
        saves.start(request("c.cbz"))
        testScheduler.runCurrent()
        assertEquals(listOf(SaveFinished.Saved("c.cbz", "c.cbz")), (first + second).drop(2))
    }

    @Test
    fun `cancelling a save stops it and announces nothing`() = runTest {
        val finished = mutableListOf<SaveFinished>()
        val saves = DeviceSaves(smb, folders, backgroundScope, finished::add)
        val stopped = CompletableDeferred<Unit>()
        smb.saving = {
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        val id = saves.start(request(directory = true))
        testScheduler.runCurrent()
        assertEquals(listOf(id), saves.jobs.value.map { it.id })
        assertTrue(smb.saved.single().directory)

        saves.cancel(id)
        testScheduler.runCurrent()

        assertTrue(stopped.isCompleted)
        assertTrue(saves.jobs.value.isEmpty())
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `a failed save is announced with its reason and leaves the list`() = runTest {
        val finished = mutableListOf<SaveFinished>()
        val saves = DeviceSaves(smb, folders, backgroundScope, finished::add)
        smb.saving = { SaveOutcome.Failed(SaveFailure.NoRoom) }
        saves.start(request())
        testScheduler.runCurrent()
        assertEquals(listOf<SaveFinished>(SaveFinished.Failed("vol1.cbz", SaveFailure.NoRoom)), finished)
        assertTrue(saves.jobs.value.isEmpty())
    }

    @Test
    fun `an unexpected error ends one save as a write failure and leaves the others running`() = runTest {
        val finished = mutableListOf<SaveFinished>()
        val saves = DeviceSaves(smb, folders, backgroundScope, finished::add)
        smb.saving = { throw IllegalStateException("surprise") }
        saves.start(request("bad.cbz"))
        testScheduler.runCurrent()
        smb.saving = { awaitCancellation() }
        val other = saves.start(request("good.cbz"))
        testScheduler.runCurrent()
        assertEquals(listOf<SaveFinished>(SaveFinished.Failed("bad.cbz", SaveFailure.WriteFailed)), finished)
        assertEquals(listOf(other), saves.jobs.value.map { it.id })
        saves.cancelAll()
        testScheduler.runCurrent()
        assertTrue(saves.jobs.value.isEmpty())
    }
}
