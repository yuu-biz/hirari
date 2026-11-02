package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import io.github.yuubiz.hirari.core.model.LocationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** The folder of a SAF place the file list showed last. [documentId] saves looking the path up again. */
data class LastFolder(val location: LocationId, val path: String, val documentId: String?)

/** What the app knows at start-up about the starts before it (docs/spec.md section 4.3, 起動時の動作). */
data class LaunchRecord(
    /** Starts in a row that never reached their first screen. */
    val unfinishedStarts: Int = 0,
    val lastFolder: LastFolder? = null,
)

/** The start-up record. Device-local: it is not backed up and not exported (docs/spec.md section 7). */
interface LaunchRepository {
    /** Counts this start as unfinished until [finishStart], and returns the record from before it. */
    suspend fun beginStart(): LaunchRecord

    suspend fun finishStart()

    suspend fun setLastFolder(folder: LastFolder?)
}

@Serializable
internal data class StoredLaunch(
    val unfinishedStarts: Int = 0,
    val lastFolderLocation: Long? = null,
    val lastFolderPath: String? = null,
    val lastFolderDocumentId: String? = null,
) {
    fun toRecord() = LaunchRecord(
        unfinishedStarts = unfinishedStarts.coerceAtLeast(0),
        lastFolder = if (lastFolderLocation != null && lastFolderPath != null) {
            LastFolder(LocationId(lastFolderLocation), lastFolderPath, lastFolderDocumentId)
        } else {
            null
        },
    )
}

internal class DataStoreLaunchRepository(private val store: DataStore<StoredLaunch>) : LaunchRepository {
    override suspend fun beginStart(): LaunchRecord {
        var before = StoredLaunch()
        store.updateData {
            before = it
            it.copy(unfinishedStarts = it.unfinishedStarts.coerceAtLeast(0) + 1)
        }
        return before.toRecord()
    }

    override suspend fun finishStart() {
        store.updateData { it.copy(unfinishedStarts = 0) }
    }

    override suspend fun setLastFolder(folder: LastFolder?) {
        store.updateData {
            it.copy(lastFolderLocation = folder?.location?.value, lastFolderPath = folder?.path, lastFolderDocumentId = folder?.documentId)
        }
    }
}

/** A file that does not read as the record starts over: losing it only loses the last folder. */
internal object LaunchSerializer : Serializer<StoredLaunch> {
    override val defaultValue = StoredLaunch()

    override suspend fun readFrom(input: InputStream): StoredLaunch = try {
        SettingsJson.decodeFromString(StoredLaunch.serializer(), input.readBytes().decodeToString())
    } catch (_: SerializationException) {
        StoredLaunch()
    } catch (_: IllegalArgumentException) {
        StoredLaunch()
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
