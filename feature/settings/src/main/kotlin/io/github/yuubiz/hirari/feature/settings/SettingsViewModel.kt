package io.github.yuubiz.hirari.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.FontImport
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.ImportChanges
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** A settings file that has been read and checked, waiting for the user to confirm it. */
interface PreparedImport {
    val changes: ImportChanges

    suspend fun apply()
}

/** 設定の書き出しと読み込み (docs/spec.md section 4.11) as the settings screen uses it. */
interface SettingsFileTransfer {
    /** The text of the settings file. [includeReading] adds positions, bookmarks and history. */
    suspend fun export(includeReading: Boolean): String

    /** `null` when [text] is not a settings file. */
    suspend fun prepare(text: String): PreparedImport?
}

/** The documents the user picks with the system file picker. Every call runs its I/O off the main thread. */
interface DocumentStore {
    suspend fun write(uri: UriString, text: String)

    /** @throws DocumentTooLargeException for a file too big to be a settings file. */
    suspend fun read(uri: UriString): String
}

class DocumentTooLargeException : IOException("the file is too large to be a settings file")

/** What the settings screen needs; build it with [settingsServices] from the app's `Storage`. */
class SettingsServices(
    val settings: SettingsRepository,
    val transfer: SettingsFileTransfer,
    val documents: DocumentStore,
    val clearThumbnails: suspend () -> Unit,

    /** Deletes the extraction cache (docs/spec.md section 3.3) and returns the bytes freed. */
    val clearExtraction: suspend () -> Long,

    /** The version, build date and changes that このアプリについて shows. Runs its I/O off the main thread. */
    val about: suspend () -> AboutInfo,

    val fonts: FontLibrary,
)

sealed interface SettingsLoad {
    data object Loading : SettingsLoad

    /** The stored settings could not be read. */
    data object Failed : SettingsLoad

    data class Ready(val settings: GlobalSettings) : SettingsLoad
}

enum class Step { Exporting, Reading, Applying, ClearingThumbnails, ClearingExtraction, ImportingFont }

enum class Problem { CannotWrite, CannotRead, TooLarge, NotASettingsFile, ApplyFailed, CannotClear, CannotSave, NotAFont, NoRoomForFont, CannotReadFont }

sealed interface ActionResult {
    data class Exported(val includedReading: Boolean) : ActionResult

    data class Imported(val changes: ImportChanges) : ActionResult

    data object ThumbnailsCleared : ActionResult

    data class ExtractionCleared(val freedBytes: Long) : ActionResult

    /** The font is stored as [fileName] and is now the text typeface. */
    data class FontImported(val fileName: String) : ActionResult

    data class Failed(val problem: Problem) : ActionResult
}

/** What the screen asks of the user or tells them beside the list of settings. */
sealed interface ActionState {
    data object Idle : ActionState

    data class Working(val step: Step) : ActionState

    /** A file was read; [changes] says what applying it would change. */
    data class ConfirmImport(val changes: ImportChanges) : ActionState

    data class Done(val result: ActionResult) : ActionState
}

/**
 * [saveFailures] counts the changes that could not be written, so a control can go back to the stored value.
 * [fonts] are the file names of the imported fonts.
 */
data class SettingsUiState(
    val load: SettingsLoad,
    val action: ActionState,
    val saveFailures: Int = 0,
    val fonts: List<String> = emptyList(),
)

/**
 * Holds the state of the settings screen. All file and database work runs on [io]; the settings repository is safe
 * to call from the main thread.
 */
class SettingsViewModel(
    private val services: SettingsServices,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val action = MutableStateFlow<ActionState>(ActionState.Idle)

    /** Kept out of [ActionState] so that the state stays comparable. */
    private var pending: PreparedImport? = null

    private val load: Flow<SettingsLoad> = services.settings.settings
        .map<GlobalSettings, SettingsLoad> { SettingsLoad.Ready(it) }
        .catch { if (it is IOException) emit(SettingsLoad.Failed) else throw it }

    private val saveFailures = MutableStateFlow(0)

    private val fonts = MutableStateFlow<List<String>>(emptyList())

    val state: StateFlow<SettingsUiState> = combine(load, action, saveFailures, fonts, ::SettingsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SettingsUiState(SettingsLoad.Loading, ActionState.Idle))

    init {
        viewModelScope.launch { refreshFonts() }
    }

    /** Copies the font at [uri] into the imported fonts and, once stored, makes it the text typeface. */
    fun importFont(uri: UriString) {
        if (!begin(Step.ImportingFont)) return
        viewModelScope.launch {
            val outcome = try {
                services.fonts.import(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                FontImport.Unreadable
            }
            if (outcome is FontImport.Imported) refreshFonts()
            finish(
                if (outcome is FontImport.Imported && !use(outcome.fileName)) ActionResult.Failed(Problem.CannotSave) else outcome.toResult(),
            )
        }
    }

    private suspend fun use(fileName: String): Boolean = try {
        services.settings.update { it.copy(textTypeface = TextTypeface.Imported(fileName)) }
        true
    } catch (e: IOException) {
        false
    }

    private suspend fun refreshFonts() {
        fonts.value = try {
            services.fonts.list()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            fonts.value
        }
    }

    /** Writes the settings [transform] makes from the stored ones; the repository moves numbers to allowed values. */
    fun change(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch {
            try {
                services.settings.update(transform)
            } catch (e: IOException) {
                saveFailures.update { it + 1 }
                action.compareAndSet(ActionState.Idle, ActionState.Done(ActionResult.Failed(Problem.CannotSave)))
            }
        }
    }

    fun export(uri: UriString, includeReading: Boolean) {
        if (!begin(Step.Exporting)) return
        viewModelScope.launch {
            finish(
                attempt(Problem.CannotWrite) {
                    val text = withContext(io) { services.transfer.export(includeReading) }
                    services.documents.write(uri, text)
                    ActionResult.Exported(includeReading)
                },
            )
        }
    }

    /** Reads the file at [uri] and asks for confirmation, or tells why it cannot be imported. */
    fun prepareImport(uri: UriString) {
        if (!begin(Step.Reading)) return
        viewModelScope.launch {
            try {
                val text = services.documents.read(uri).removePrefix(BYTE_ORDER_MARK)
                val prepared = withContext(io) { services.transfer.prepare(text) }
                if (prepared == null) {
                    finish(ActionResult.Failed(Problem.NotASettingsFile))
                } else {
                    pending = prepared
                    action.value = ActionState.ConfirmImport(prepared.changes)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: DocumentTooLargeException) {
                finish(ActionResult.Failed(Problem.TooLarge))
            } catch (e: Exception) {
                finish(ActionResult.Failed(Problem.CannotRead))
            }
        }
    }

    /** Applies the file that [prepareImport] read. A file that changes nothing is not applied. */
    fun confirmImport() {
        val prepared = pending ?: return
        val confirming = action.value as? ActionState.ConfirmImport ?: return
        pending = null
        if (prepared.changes.total == 0) {
            action.compareAndSet(confirming, ActionState.Idle)
            return
        }
        if (!action.compareAndSet(confirming, ActionState.Working(Step.Applying))) return
        viewModelScope.launch {
            // Half an import would leave the settings replaced and the books untouched, so leaving the screen
            // does not cancel it.
            finish(
                attempt(Problem.ApplyFailed) {
                    withContext(NonCancellable + io) { prepared.apply() }
                    ActionResult.Imported(prepared.changes)
                },
            )
        }
    }

    fun cancelImport() {
        if (action.value is ActionState.ConfirmImport) {
            pending = null
            action.value = ActionState.Idle
        }
    }

    fun clearThumbnails() {
        if (!begin(Step.ClearingThumbnails)) return
        viewModelScope.launch {
            finish(
                attempt(Problem.CannotClear) {
                    withContext(io) { services.clearThumbnails() }
                    ActionResult.ThumbnailsCleared
                },
            )
        }
    }

    fun clearExtraction() {
        if (!begin(Step.ClearingExtraction)) return
        viewModelScope.launch {
            finish(
                attempt(Problem.CannotClear) {
                    ActionResult.ExtractionCleared(withContext(io) { services.clearExtraction() })
                },
            )
        }
    }

    /** Closes the message of a finished action. */
    fun dismissResult() {
        if (action.value is ActionState.Done) action.value = ActionState.Idle
    }

    private fun begin(step: Step): Boolean = action.compareAndSet(ActionState.Idle, ActionState.Working(step))

    private fun finish(result: ActionResult) {
        action.value = ActionState.Done(result)
    }

    private suspend fun attempt(problem: Problem, block: suspend () -> ActionResult): ActionResult = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ActionResult.Failed(problem)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val BYTE_ORDER_MARK = 0xFEFF.toChar().toString()
    }
}
