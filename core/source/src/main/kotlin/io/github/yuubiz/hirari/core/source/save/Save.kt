package io.github.yuubiz.hirari.core.source.save

import java.io.IOException
import java.text.Normalizer
import java.util.Locale

/** The suffix of the name a file has while it is being written (docs/spec.md section 4.5, 端末に保存). */
const val SAVE_PART_SUFFIX = ".part"

/** Why a save did not finish. The message shown to the user depends on this alone (docs/spec.md section 4.5). */
enum class SaveFailure {
    /** `名前` and `名前 (1)` to `名前 (9)` all exist in the destination. */
    NamesTaken,

    /** The destination has less free space than the file needs. */
    NoRoom,

    /** The connection to the server broke and did not come back in time. */
    ConnectionLost,
    Unreachable,
    AuthenticationFailed,

    /** The file, the folder or the share is gone from the server. */
    NotFound,

    /** The server refused a request on a working connection, or the file ended before its size. */
    Refused,

    /** The destination refuses the write: the grant is gone, or the provider cannot rename. */
    NoPermission,

    /** The destination failed in another way. */
    WriteFailed,
    PasswordUnreadable,
    NoLocalNetwork,
}

sealed interface SaveOutcome {
    /** [name] is what the item is called in the destination; [files] and [bytes] count what was written. */
    data class Saved(val name: String, val files: Int, val bytes: Long) : SaveOutcome

    data class Failed(val failure: SaveFailure) : SaveOutcome
}

/** How far a save is. [bytesTotal] is the sum of the sizes the server reported for the files to copy. */
data class SaveProgress(
    val bytesDone: Long,
    val bytesTotal: Long,
    /** The name of the file being written. */
    val file: String,
    val filesDone: Int,
    val filesTotal: Int,
) {
    val percent: Int
        get() = if (bytesTotal > 0) (bytesDone * 100 / bytesTotal).coerceIn(0, 100).toInt() else if (filesTotal > 0 && filesDone >= filesTotal) 100 else 0
}

/**
 * A folder the saved files are written into. The SAF folder implements it in [SafSaveFolder]; the JVM tests use a
 * fake. Every method blocks. A failure of the destination is thrown as [SaveDestinationException], [NoRoomException],
 * [CannotRenameException] or [SecurityException]. Two folders are equal when they are the same folder of the
 * destination, so that saves through separate instances see the names the others hold ([HeldNames]).
 */
interface SaveFolder {
    /** The names of the children. */
    fun names(): Set<String>

    fun createFolder(name: String): SaveFolder

    /** A new file named exactly [name], open for writing. */
    fun create(name: String): SaveFile

    /** Deletes the child [name] with all it holds, if there is one. */
    fun delete(name: String)
}

/** A file being written. Ends with [commit] or [discard], and both may follow a failed [write]. */
interface SaveFile {
    /** The free bytes of the storage the file is on, or null when that cannot be told. */
    val freeBytes: Long?

    /** @throws NoRoomException when the storage is full. */
    fun write(buffer: ByteArray, length: Int)

    /** Closes the file and renames it to [name]. */
    fun commit(name: String)

    /** Closes and deletes the file. Does nothing the second time. */
    fun discard()
}

/** The destination has no room for the bytes. */
class NoRoomException : IOException("the destination is full")

/** The provider of the destination cannot rename a document, so a file cannot be written under its `.part` name. */
class CannotRenameException : IOException("the provider cannot rename a document")

/** The destination failed; [cause] says how. */
class SaveDestinationException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Every name `名前` to `名前 (9)` is taken. */
class NamesTakenException : IOException("no free name")

/** The most `(n)` a name gets (docs/spec.md section 4.5). */
const val SAVE_NAME_TRIES = 9

/**
 * [wanted] if no name of [taken] equals it, else the first of `名前 (1)` to `名前 (9)` that none equals, with the number
 * before the extension of a file. Names compare in NFC and without case, as a destination on FAT does.
 *
 * @throws NamesTakenException when all ten are taken.
 */
fun freeName(wanted: String, taken: Set<String>): String {
    val used = taken.mapTo(HashSet()) { it.comparable() }
    val dot = wanted.lastIndexOf('.')
    val stem = if (dot > 0) wanted.substring(0, dot) else wanted
    val extension = if (dot > 0) wanted.substring(dot) else ""
    val candidates = sequenceOf(wanted) + (1..SAVE_NAME_TRIES).asSequence().map { "$stem ($it)$extension" }
    return candidates.firstOrNull { it.comparable() !in used } ?: throw NamesTakenException()
}

/**
 * The names the saves of this process hold in their destination folders (docs/spec.md section 4.5). A save holds the
 * name of its item from the moment it picks it until its file is renamed or deleted, so a second save of the same name
 * into the same folder picks another and never deletes the `.part` of the first.
 */
internal object HeldNames {
    private val held = HashMap<SaveFolder, MutableList<String>>()

    /**
     * Holds and returns the first free name of [wanted] in [folder], one that neither a child of [folder] nor the hold of
     * another save takes. [own] is a name this save already holds, which does not count as taken. Lists [folder] under
     * the lock, so a save that renames its file and lets go of the name in between is seen.
     *
     * @throws NamesTakenException when all ten are taken.
     */
    fun hold(folder: SaveFolder, wanted: String, own: String? = null): String = synchronized(held) {
        val others = held[folder].orEmpty().let { if (own == null) it else it - own }
        val name = freeName(wanted, folder.names() + others)
        held.getOrPut(folder, ::mutableListOf) += name
        name
    }

    fun release(folder: SaveFolder, name: String) {
        synchronized(held) {
            val names = held[folder] ?: return
            names.remove(name)
            if (names.isEmpty()) held.remove(folder)
        }
    }
}

private fun String.comparable(): String = Normalizer.normalize(this, Normalizer.Form.NFC).lowercase(Locale.ROOT)
