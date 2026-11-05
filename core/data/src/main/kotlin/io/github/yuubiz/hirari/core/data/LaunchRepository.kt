package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.logFailure
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger

/** The folder of a SAF place the file list showed last. [documentId] saves looking the path up again. */
data class LastFolder(val location: LocationId, val path: String, val documentId: String?)

/**
 * A book a reader opens (docs/spec.md section 6.1), by its address without the provider's document id and with its
 * path normalized, so every way of opening the same book names it alike. [name] is what a notice calls it.
 */
class OpeningBook(book: BookAddress, val name: String) {
    val book: BookAddress = identity(book)

    /** Whether [address] is this book, however it was reached. */
    fun isBook(address: BookAddress): Boolean = identity(address) == book

    override fun equals(other: Any?): Boolean = other is OpeningBook && other.book == book && other.name == name

    override fun hashCode(): Int = 31 * book.hashCode() + name.hashCode()

    override fun toString(): String = "OpeningBook($book, $name)"
}

private fun identity(book: BookAddress): BookAddress = when (book) {
    is BookAddress.InLocation -> book.copy(path = normalizePath(book.path), documentId = null)
    is BookAddress.Document, is BookAddress.Tree -> book
}

/** One reader's record of the book it opens, which [LaunchRepository.endOpening] ends. */
data class BookOpening(val serial: Long, val book: OpeningBook)

/** What the app knows at start-up about the starts before it (docs/spec.md section 4.3, 起動時の動作). */
data class LaunchRecord(
    /** Starts in a row that never reached their first screen. */
    val unfinishedStarts: Int = 0,
    val lastFolder: LastFolder? = null,
    /** The book a reader was opening when the app ended since the last start (docs/spec.md section 6.1). */
    val diedOpening: OpeningBook? = null,
    /**
     * The last book the app ended while opening, this start's [diedOpening] or one of an earlier start, which has
     * not shown its content in a reader since. A start does not open it by itself.
     */
    val suspect: OpeningBook? = null,
)

/** The start-up record. Device-local: it is not backed up and not exported (docs/spec.md section 7). */
interface LaunchRepository {
    /** Takes a persistence failure once, after switching to process-local state. */
    fun takeFailure(): IOException? = null

    /**
     * Counts this start as unfinished until [finishStart], and returns the record from before it. A book a reader was
     * still opening becomes the [LaunchRecord.suspect] from here on.
     */
    suspend fun beginStart(): LaunchRecord

    suspend fun finishStart()

    suspend fun setLastFolder(folder: LastFolder?)

    /** Records that a reader opens [book], in place of any book recorded so (docs/spec.md section 6.1). */
    suspend fun beginOpening(book: OpeningBook): BookOpening

    /**
     * Ends [opening], unless another reader recorded its own book since. [shown]: the reader showed the book's
     * content or why it did not open, which also clears the book as the suspect.
     */
    suspend fun endOpening(opening: BookOpening, shown: Boolean)

    /** Whether the reader's operation guide has been shown to the end on this device (docs/spec.md section 4.8). */
    suspend fun readerGuideSeen(): Boolean

    suspend fun markReaderGuideSeen()

    /** The folder the user last chose to save a book into, the picker's starting point (docs/spec.md section 4.5). */
    suspend fun lastSaveFolder(): UriString?

    suspend fun setLastSaveFolder(folder: UriString?)
}

/** An [OpeningBook] in the file, flattened as the reader's route is. A [kind] this version does not know reads as none. */
@Serializable
internal data class StoredBook(
    val kind: String,
    val name: String,
    val uri: String? = null,
    val location: Long? = null,
    val path: String? = null,
    val folder: Boolean = false,
) {
    fun toBook(): OpeningBook? {
        val address = when (kind) {
            DOCUMENT -> uri?.let { BookAddress.Document(UriString(it)) }
            TREE -> uri?.let { BookAddress.Tree(UriString(it)) }
            IN_LOCATION -> if (location != null && path != null) BookAddress.InLocation(LocationId(location), path, folder) else null
            else -> null
        }
        return address?.let { OpeningBook(it, name) }
    }

    companion object {
        private const val DOCUMENT = "document"
        private const val TREE = "tree"
        private const val IN_LOCATION = "inLocation"

        fun of(book: OpeningBook): StoredBook = when (val address = book.book) {
            is BookAddress.Document -> StoredBook(DOCUMENT, book.name, uri = address.uri.value)
            is BookAddress.Tree -> StoredBook(TREE, book.name, uri = address.uri.value)
            is BookAddress.InLocation -> StoredBook(IN_LOCATION, book.name, location = address.location.value, path = address.path, folder = address.folder)
        }
    }
}

@Serializable
internal data class StoredLaunch(
    val unfinishedStarts: Int = 0,
    val lastFolderLocation: Long? = null,
    val lastFolderPath: String? = null,
    val lastFolderDocumentId: String? = null,
    val readerGuideSeen: Boolean = false,
    val lastSaveFolder: String? = null,
    /** The book a reader opens now, and the serial of that reader's record. */
    val opening: StoredBook? = null,
    val openingSerial: Long = 0,
    val suspect: StoredBook? = null,
) {
    fun toRecord() = LaunchRecord(
        unfinishedStarts = unfinishedStarts.coerceAtLeast(0),
        lastFolder = if (lastFolderLocation != null && lastFolderPath != null) {
            LastFolder(LocationId(lastFolderLocation), lastFolderPath, lastFolderDocumentId)
        } else {
            null
        },
        diedOpening = opening?.toBook(),
        suspect = (opening ?: suspect)?.toBook(),
    )
}

internal class DataStoreLaunchRepository(
    private val store: DataStore<StoredLaunch>,
    private val debuggable: Boolean = false,
) : LaunchRepository {
    private val mutex = Mutex()
    private var memory: StoredLaunch? = null
    private var memoryOnly = false
    private val failure = AtomicReference<IOException?>(null)

    override fun takeFailure(): IOException? = failure.getAndSet(null)

    private fun failed(error: IOException) {
        memoryOnly = true
        if (error !is CorruptionException) {
            failure.compareAndSet(null, error)
            Logger.getLogger("LaunchRepository").logFailure("Cannot persist launch record; using process-local state", error, debuggable)
        }
    }

    private suspend fun read(): StoredLaunch {
        memory?.let { return it }
        return try {
            store.data.first()
        } catch (error: IOException) {
            failed(error)
            StoredLaunch()
        }.also { memory = it }
    }

    private suspend fun update(transform: (StoredLaunch) -> StoredLaunch): StoredLaunch = mutex.withLock {
        val next = transform(read())
        if (!memoryOnly) {
            try {
                store.updateData { next }
            } catch (error: IOException) {
                failed(error)
            }
        }
        next.also { memory = it }
    }

    override suspend fun beginStart(): LaunchRecord {
        var before = StoredLaunch()
        update {
            before = it
            it.copy(unfinishedStarts = it.unfinishedStarts.coerceAtLeast(0) + 1, opening = null, suspect = it.opening ?: it.suspect)
        }
        return before.toRecord()
    }

    override suspend fun beginOpening(book: OpeningBook): BookOpening {
        val serial = update { it.copy(opening = StoredBook.of(book), openingSerial = it.openingSerial + 1) }.openingSerial
        return BookOpening(serial, book)
    }

    override suspend fun endOpening(opening: BookOpening, shown: Boolean) {
        update {
            val own = it.opening != null && it.openingSerial == opening.serial
            val cleared = shown && it.suspect?.toBook()?.book == opening.book.book
            if (!own && !cleared) it else it.copy(opening = it.opening.takeUnless { own }, suspect = it.suspect.takeUnless { cleared })
        }
    }

    override suspend fun finishStart() {
        update { it.copy(unfinishedStarts = 0) }
    }

    override suspend fun setLastFolder(folder: LastFolder?) {
        update {
            it.copy(lastFolderLocation = folder?.location?.value, lastFolderPath = folder?.path, lastFolderDocumentId = folder?.documentId)
        }
    }

    override suspend fun readerGuideSeen(): Boolean = mutex.withLock { read().readerGuideSeen }

    override suspend fun markReaderGuideSeen() {
        update { it.copy(readerGuideSeen = true) }
    }

    override suspend fun lastSaveFolder(): UriString? = mutex.withLock { read().lastSaveFolder?.let(::UriString) }

    override suspend fun setLastSaveFolder(folder: UriString?) {
        update { it.copy(lastSaveFolder = folder?.value) }
    }
}

/** A file that does not read as the record starts over: losing it loses the last folder and shows the guide once more. */
internal object LaunchSerializer : Serializer<StoredLaunch> {
    override val defaultValue = StoredLaunch()

    override suspend fun readFrom(input: InputStream): StoredLaunch = try {
        SettingsJson.decodeFromString(StoredLaunch.serializer(), input.readBytes().decodeToString())
    } catch (error: SerializationException) {
        throw CorruptionException("unreadable launch record", error)
    } catch (error: IllegalArgumentException) {
        throw CorruptionException("invalid launch record", error)
    }

    override suspend fun writeTo(t: StoredLaunch, output: OutputStream) {
        output.write(SettingsJson.encodeToString(StoredLaunch.serializer(), t).encodeToByteArray())
    }
}

internal fun openLaunch(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    file: () -> File,
): DataStore<StoredLaunch> = DataStoreFactory.create(
    serializer = LaunchSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { StoredLaunch() },
    scope = scope,
    produceFile = file,
)

internal const val LAUNCH_FILE = "launch.json"

internal fun openLaunch(context: Context): DataStore<StoredLaunch> = openLaunch { context.dataStoreFile(LAUNCH_FILE) }
