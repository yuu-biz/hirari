package io.github.yuubiz.hirari.feature.library

import android.content.ContentResolver
import android.content.res.Resources
import android.net.Uri
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.save.SafSaveFolder
import io.github.yuubiz.hirari.core.source.save.SaveFailure
import io.github.yuubiz.hirari.core.source.save.SaveFolder
import io.github.yuubiz.hirari.core.source.save.SaveOutcome
import io.github.yuubiz.hirari.core.source.save.SaveProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException

/**
 * What 端末に保存 copies (docs/spec.md section 4.5): the file or folder at [path] of an SMB [place], counted from the
 * place's folder, into the tree [destination] the user picked. [name] is what the item is called.
 */
data class SaveRequest(
    val place: LibraryLocation.SmbShare,
    val path: String,
    val name: String,
    val directory: Boolean,
    val destination: UriString,
)

/** A save under way. [progress] is null until the first block is read. */
data class SaveJob(val id: Long, val name: String, val destination: UriString, val progress: SaveProgress?)

/** How a save ended other than by 取り消す. */
sealed interface SaveFinished {
    /** What the user asked to save. */
    val name: String

    /** [savedAs] is the name in the destination, which differs from [name] when [name] was taken. */
    data class Saved(override val name: String, val savedAs: String) : SaveFinished

    data class Failed(override val name: String, val failure: SaveFailure) : SaveFinished
}

/** Opens the folder a save writes into. Constructing one does no I/O. */
fun interface SaveFolders {
    fun open(tree: UriString): SaveFolder
}

/** The trees of the device through [resolver]. */
class SafSaveFolders(private val resolver: ContentResolver) : SaveFolders {
    override fun open(tree: UriString): SaveFolder = SafSaveFolder(resolver, Uri.parse(tree.value))
}

/**
 * The saves to the device (docs/spec.md section 4.5), which run on [scope] so that they outlive the screen that
 * started them. Several can run at once. [jobs] holds the ones under way. A save that ends other than by 取り消す goes
 * to [onFinished], which the app uses for a notification, and waits for a screen to take it with [nextUnshown].
 */
class DeviceSaves(
    private val smb: SmbPlaces,
    private val folders: SaveFolders,
    private val scope: CoroutineScope,
    private val onFinished: (SaveFinished) -> Unit = {},
) {
    private val ids = AtomicLong()
    private val running = MutableStateFlow<List<SaveJob>>(emptyList())
    private val runs = HashMap<Long, Job>()
    private val unshown = Channel<SaveFinished>(Channel.UNLIMITED)

    val jobs: StateFlow<List<SaveJob>> = running.asStateFlow()

    /**
     * Waits for a save to end that no screen has shown and takes it, oldest first. Ends that come while no screen
     * asks are kept, so the next screen shown gets them, and each goes to one caller only.
     */
    suspend fun nextUnshown(): SaveFinished = unshown.receive()

    /** Starts the save and returns its id. */
    fun start(request: SaveRequest): Long {
        val id = ids.incrementAndGet()
        running.update { it + SaveJob(id, request.name, request.destination, progress = null) }
        val run = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                when (val outcome = save(request, id)) {
                    is SaveOutcome.Saved -> SaveFinished.Saved(request.name, outcome.name)
                    is SaveOutcome.Failed -> SaveFinished.Failed(request.name, outcome.failure)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                SaveFinished.Failed(request.name, SaveFailure.WriteFailed)
            }
            running.update { jobs -> jobs.filter { it.id != id } }
            synchronized(runs) { runs.remove(id) }
            unshown.trySend(result)
            onFinished(result)
        }
        synchronized(runs) { runs[id] = run }
        run.invokeOnCompletion { cause ->
            if (cause != null) {
                running.update { jobs -> jobs.filter { it.id != id } }
                synchronized(runs) { runs.remove(id) }
            }
        }
        run.start()
        return id
    }

    /**
     * 取り消す: leaves [jobs] at once, and the save stops at its next block and deletes the file it was writing. A
     * request to the server that does not answer ends when it times out (docs/spec.md section 6.5), so the stop can
     * come after the save has left the list.
     */
    fun cancel(id: Long) {
        synchronized(runs) { runs[id] }?.cancel()
        running.update { jobs -> jobs.filter { it.id != id } }
    }

    fun cancelAll() {
        synchronized(runs) { runs.values.toList() }.forEach(Job::cancel)
        running.value = emptyList()
    }

    private suspend fun save(request: SaveRequest, id: Long): SaveOutcome =
        smb.save(request.place, request.path, request.directory, folders.open(request.destination)) { progress ->
            running.update { jobs -> jobs.map { if (it.id == id) it.copy(progress = progress) else it } }
        }
}

fun SaveProgress.text(): String = progressText(percent, bytesDone, bytesTotal)

/** `45% 12.3 MB / 27.0 MB`, or just the first part before the total is known (docs/spec.md section 4.5). */
fun progressText(percent: Int, bytesDone: Long, bytesTotal: Long): String =
    if (bytesTotal > 0) "$percent% ${sizeText(bytesDone)} / ${sizeText(bytesTotal)}" else "$percent%"

/** The string naming why a save failed. */
@androidx.annotation.StringRes
fun SaveFailure.message(): Int = when (this) {
    SaveFailure.NamesTaken -> R.string.save_failed_names_taken
    SaveFailure.NoRoom -> R.string.save_failed_no_room
    SaveFailure.ConnectionLost -> R.string.save_failed_connection_lost
    SaveFailure.Unreachable -> R.string.save_failed_unreachable
    SaveFailure.AuthenticationFailed -> R.string.save_failed_auth
    SaveFailure.NotFound -> R.string.save_failed_not_found
    SaveFailure.Refused -> R.string.save_failed_refused
    SaveFailure.NoPermission -> R.string.save_failed_permission
    SaveFailure.WriteFailed -> R.string.save_failed_write
    SaveFailure.PasswordUnreadable -> R.string.save_failed_password_unreadable
    SaveFailure.NoLocalNetwork -> R.string.save_failed_local_network
}

/** The sentence a notification or snackbar gives for how a save ended. */
fun SaveFinished.text(resources: Resources): String = when (this) {
    is SaveFinished.Saved -> resources.getString(R.string.save_done, savedAs)
    is SaveFinished.Failed -> resources.getString(R.string.save_failed, name, resources.getString(failure.message()))
}
