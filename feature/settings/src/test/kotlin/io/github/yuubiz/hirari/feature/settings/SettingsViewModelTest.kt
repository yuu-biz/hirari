package io.github.yuubiz.hirari.feature.settings

import io.github.yuubiz.hirari.core.data.FontImport
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.ImportChanges
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private val NO_ABOUT: suspend () -> AboutInfo = { AboutInfo(null, null, null) }

private class FakeFonts : FontLibrary {
    val stored = mutableListOf("a.ttf")
    var outcome: FontImport = FontImport.Imported("new.ttf")
    var failure: Exception? = null
    val imported = mutableListOf<UriString>()

    override suspend fun list(): List<String> = stored.toList()

    override suspend fun import(uri: UriString): FontImport {
        imported += uri
        failure?.let { throw it }
        (outcome as? FontImport.Imported)?.let { stored += it.fileName }
        return outcome
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private class FakeSettings(initial: GlobalSettings = GlobalSettings()) : SettingsRepository {
        val stored = MutableStateFlow(initial)
        var failure: IOException? = null
        override val settings: Flow<GlobalSettings> get() = stored

        override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) {
            failure?.let { throw it }
            stored.value = transform(stored.value).normalized()
        }
    }

    private class FakeDocuments : DocumentStore {
        val written = mutableMapOf<UriString, String>()
        var contents: String = ""
        var readFailure: Exception? = null
        var writeFailure: Exception? = null
        var writeGate: CompletableDeferred<Unit>? = null

        override suspend fun write(uri: UriString, text: String) {
            writeGate?.await()
            writeFailure?.let { throw it }
            written[uri] = text
        }

        override suspend fun read(uri: UriString): String {
            readFailure?.let { throw it }
            return contents
        }
    }

    private class FakeTransfer : SettingsFileTransfer {
        val exports = mutableListOf<Boolean>()
        val prepared = mutableListOf<String>()
        var exported = "{\"format\":1}"
        var readable = true
        var changes = ImportChanges(settings = 3, bookOverrides = 1, servers = 1, progress = 2, bookmarks = 0, history = 4)
        var applyFailure: Exception? = null
        var applied = 0

        override suspend fun export(includeReading: Boolean): String {
            exports += includeReading
            return exported
        }

        override suspend fun prepare(text: String): PreparedImport? {
            prepared += text
            if (!readable) return null
            val changes = changes
            return object : PreparedImport {
                override val changes = changes

                override suspend fun apply() {
                    applyFailure?.let { throw it }
                    applied++
                }
            }
        }
    }

    private val settings = FakeSettings()
    private val documents = FakeDocuments()
    private val transfer = FakeTransfer()
    private val fonts = FakeFonts()
    private var thumbnailsCleared = 0
    private var thumbnailFailure: Exception? = null
    private var extractionFreed = 0L
    private var extractionFailure: Exception? = null

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(scope: TestScope) = SettingsViewModel(
        SettingsServices(
            settings = settings,
            transfer = transfer,
            documents = documents,
            clearThumbnails = {
                thumbnailFailure?.let { throw it }
                thumbnailsCleared++
            },
            clearExtraction = {
                extractionFailure?.let { throw it }
                extractionFreed
            },
            about = NO_ABOUT,
            fonts = fonts,
        ),
        io = UnconfinedTestDispatcher(scope.testScheduler),
    ).also { vm -> scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { vm.state.collect {} } }

    private val uri = UriString("content://documents/settings.json")

    @Test
    fun `the state shows the stored settings and follows changes`() = runTest {
        val vm = viewModel(this)
        assertEquals(SettingsLoad.Ready(GlobalSettings()), vm.state.value.load)
        vm.change { it.copy(readingDirection = ReadingDirection.LeftToRight, historyLimit = 90) }
        assertEquals(SettingsLoad.Ready(GlobalSettings(readingDirection = ReadingDirection.LeftToRight, historyLimit = 100)), vm.state.value.load)
    }

    @Test
    fun `a setting that cannot be read leaves a failed state`() = runTest {
        val broken = object : SettingsRepository {
            override val settings: Flow<GlobalSettings> = flow { throw IOException("disk") }

            override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) = Unit
        }
        val vm = SettingsViewModel(SettingsServices(broken, transfer, documents, clearThumbnails = {}, clearExtraction = { 0L }, about = NO_ABOUT, fonts = FakeFonts()), UnconfinedTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        assertEquals(SettingsLoad.Failed, vm.state.value.load)
    }

    @Test
    fun `a setting that cannot be saved is reported`() = runTest {
        val vm = viewModel(this)
        settings.failure = IOException("full")
        vm.change { it.copy(coverAlone = false) }
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.CannotSave)), vm.state.value.action)
        vm.dismissResult()
        assertEquals(ActionState.Idle, vm.state.value.action)
    }

    @Test
    fun `every failed save is counted, even while an earlier failure is still on screen`() = runTest {
        val vm = viewModel(this)
        assertEquals(0, vm.state.value.saveFailures)
        settings.failure = IOException("full")
        vm.change { it.copy(coverAlone = false) }
        vm.change { it.copy(coverAlone = true) }
        assertEquals(2, vm.state.value.saveFailures)
        settings.failure = null
        vm.change { it.copy(coverAlone = false) }
        assertEquals(2, vm.state.value.saveFailures)
    }

    @Test
    fun `export writes the file text to the chosen document and says whether reading was included`() = runTest {
        val vm = viewModel(this)
        vm.export(uri, includeReading = true)
        assertEquals(listOf(true), transfer.exports)
        assertEquals(mapOf(uri to transfer.exported), documents.written)
        assertEquals(ActionState.Done(ActionResult.Exported(includedReading = true)), vm.state.value.action)

        vm.dismissResult()
        vm.export(uri, includeReading = false)
        assertEquals(listOf(true, false), transfer.exports)
        assertEquals(ActionState.Done(ActionResult.Exported(includedReading = false)), vm.state.value.action)
    }

    @Test
    fun `export reports a document that cannot be written`() = runTest {
        val vm = viewModel(this)
        documents.writeFailure = IOException("read-only")
        vm.export(uri, includeReading = false)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.CannotWrite)), vm.state.value.action)
        assertTrue(documents.written.isEmpty())
    }

    @Test
    fun `import asks for confirmation with the counts before it changes anything`() = runTest {
        val vm = viewModel(this)
        documents.contents = 0xFEFF.toChar() + "{\"format\":1}"
        vm.prepareImport(uri)
        assertEquals(listOf("{\"format\":1}"), transfer.prepared)
        assertEquals(ActionState.ConfirmImport(transfer.changes), vm.state.value.action)
        assertEquals(0, transfer.applied)

        vm.confirmImport()
        assertEquals(1, transfer.applied)
        assertEquals(ActionState.Done(ActionResult.Imported(transfer.changes)), vm.state.value.action)

        vm.confirmImport()
        assertEquals(1, transfer.applied)
    }

    @Test
    fun `cancelling the confirmation applies nothing`() = runTest {
        val vm = viewModel(this)
        vm.prepareImport(uri)
        vm.cancelImport()
        assertEquals(ActionState.Idle, vm.state.value.action)
        vm.confirmImport()
        assertEquals(0, transfer.applied)
        assertEquals(ActionState.Idle, vm.state.value.action)
    }

    @Test
    fun `a file that is not a settings file is refused`() = runTest {
        val vm = viewModel(this)
        transfer.readable = false
        vm.prepareImport(uri)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.NotASettingsFile)), vm.state.value.action)
        assertEquals(0, transfer.applied)
    }

    @Test
    fun `a document that cannot be read, or is too large, is reported`() = runTest {
        val vm = viewModel(this)
        documents.readFailure = IOException("gone")
        vm.prepareImport(uri)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.CannotRead)), vm.state.value.action)
        vm.dismissResult()

        documents.readFailure = SecurityException("no grant")
        vm.prepareImport(uri)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.CannotRead)), vm.state.value.action)
        vm.dismissResult()

        documents.readFailure = DocumentTooLargeException()
        vm.prepareImport(uri)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.TooLarge)), vm.state.value.action)
        assertTrue(transfer.prepared.isEmpty())
    }

    @Test
    fun `an import that fails while applying is reported`() = runTest {
        val vm = viewModel(this)
        transfer.applyFailure = IOException("database")
        vm.prepareImport(uri)
        vm.confirmImport()
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.ApplyFailed)), vm.state.value.action)
    }

    @Test
    fun `a file that changes nothing is not applied`() = runTest {
        val vm = viewModel(this)
        transfer.changes = ImportChanges(0, 0, 0, 0, 0, 0)
        vm.prepareImport(uri)
        assertEquals(ActionState.ConfirmImport(transfer.changes), vm.state.value.action)
        vm.confirmImport()
        assertEquals(0, transfer.applied)
        assertEquals(ActionState.Idle, vm.state.value.action)
    }

    @Test
    fun `a second action waits while one is running`() = runTest {
        val vm = SettingsViewModel(
            SettingsServices(settings, transfer, documents, clearThumbnails = { thumbnailsCleared++ }, clearExtraction = { 0L }, about = NO_ABOUT, fonts = FakeFonts()),
            StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        val gate = CompletableDeferred<Unit>()
        documents.writeGate = gate
        vm.export(uri, includeReading = false)
        advanceUntilIdle()
        assertEquals(ActionState.Working(Step.Exporting), vm.state.value.action)

        vm.clearThumbnails()
        vm.prepareImport(uri)
        vm.export(uri, includeReading = true)
        advanceUntilIdle()
        assertEquals(0, thumbnailsCleared)
        assertEquals(listOf(false), transfer.exports)

        gate.complete(Unit)
        advanceUntilIdle()
        assertIs<ActionState.Done>(vm.state.value.action)
    }

    @Test
    fun `clearing the thumbnails reports success and failure`() = runTest {
        val vm = viewModel(this)
        vm.clearThumbnails()
        assertEquals(1, thumbnailsCleared)
        assertEquals(ActionState.Done(ActionResult.ThumbnailsCleared), vm.state.value.action)
        vm.dismissResult()

        thumbnailFailure = IOException("busy")
        vm.clearThumbnails()
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.CannotClear)), vm.state.value.action)
    }

    @Test
    fun `clearing the extraction cache reports the bytes freed and a failure`() = runTest {
        val vm = viewModel(this)
        extractionFreed = 4_096
        vm.clearExtraction()
        assertEquals(ActionState.Done(ActionResult.ExtractionCleared(4_096)), vm.state.value.action)
        vm.dismissResult()

        extractionFailure = IOException("busy")
        vm.clearExtraction()
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.CannotClear)), vm.state.value.action)
    }

    @Test
    fun `clearing the extraction cache shows a working state and ignores a second request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val vm = SettingsViewModel(
            SettingsServices(settings, transfer, documents, clearThumbnails = {}, clearExtraction = { gate.await(); 7L }, about = NO_ABOUT, fonts = FakeFonts()),
            StandardTestDispatcher(testScheduler),
        )
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.clearExtraction()
        vm.clearExtraction()
        advanceUntilIdle()
        assertEquals(ActionState.Working(Step.ClearingExtraction), vm.state.value.action)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(ActionState.Done(ActionResult.ExtractionCleared(7)), vm.state.value.action)
    }

    @Test
    fun `the imported fonts are listed for the typeface dialog`() = runTest {
        val vm = viewModel(this)
        assertEquals(listOf("a.ttf"), vm.state.value.fonts)
    }

    @Test
    fun `an imported font becomes the text typeface and joins the list`() = runTest {
        val vm = viewModel(this)
        vm.importFont(uri)
        assertEquals(ActionState.Done(ActionResult.FontImported("new.ttf")), vm.state.value.action)
        assertEquals(TextTypeface.Imported("new.ttf"), settings.stored.value.textTypeface)
        assertEquals(listOf("a.ttf", "new.ttf"), vm.state.value.fonts)
    }

    @Test
    fun `a file that is no font changes nothing and says why`() = runTest {
        val vm = viewModel(this)
        fonts.outcome = FontImport.NotAFont
        vm.importFont(uri)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.NotAFont)), vm.state.value.action)
        assertEquals(TextTypeface.Mincho, settings.stored.value.textTypeface)
        assertEquals(listOf("a.ttf"), vm.state.value.fonts)
    }

    @Test
    fun `a font that does not fit and one that cannot be read are told apart`() = runTest {
        val vm = viewModel(this)
        fonts.outcome = FontImport.NoRoom
        vm.importFont(uri)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.NoRoomForFont)), vm.state.value.action)
        vm.dismissResult()

        fonts.outcome = FontImport.Unreadable
        vm.importFont(uri)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.CannotReadFont)), vm.state.value.action)
        vm.dismissResult()

        fonts.failure = IOException("provider")
        vm.importFont(uri)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.CannotReadFont)), vm.state.value.action)
        assertEquals(TextTypeface.Mincho, settings.stored.value.textTypeface)
    }

    @Test
    fun `a stored font whose setting cannot be saved is reported as unsaved but stays listed`() = runTest {
        val vm = viewModel(this)
        settings.failure = IOException("disk")
        vm.importFont(uri)
        assertEquals(ActionState.Done(ActionResult.Failed(Problem.CannotSave)), vm.state.value.action)
        assertEquals(listOf("a.ttf", "new.ttf"), vm.state.value.fonts)
    }

    @Test
    fun `every import outcome has a result`() {
        assertEquals(ActionResult.FontImported("x.otf"), FontImport.Imported("x.otf").toResult())
        assertEquals(ActionResult.Failed(Problem.NotAFont), FontImport.NotAFont.toResult())
        assertEquals(ActionResult.Failed(Problem.NoRoomForFont), FontImport.NoRoom.toResult())
        assertEquals(ActionResult.Failed(Problem.CannotReadFont), FontImport.Unreadable.toResult())
    }
}
