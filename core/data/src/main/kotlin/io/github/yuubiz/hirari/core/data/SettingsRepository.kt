package io.github.yuubiz.hirari.core.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Safe to call from the main thread. */
interface SettingsRepository {
    /** Settings for running the app; a read failure supplies defaults. */
    val settings: Flow<GlobalSettings>

    /** Stored settings, with read failures propagated instead of replaced by defaults. */
    val storedSettings: Flow<GlobalSettings> get() = settings

    /** Takes the first read failure once per process, after [settings] has supplied defaults instead. */
    fun takeReadFailure(): IOException? = null

    suspend fun update(transform: (GlobalSettings) -> GlobalSettings)
}

internal class DataStoreSettingsRepository(private val store: DataStore<GlobalSettings>) : SettingsRepository {
    private val takenReadFailure = AtomicBoolean(false)
    private val readFailure = AtomicReference<IOException?>(null)

    override val storedSettings: Flow<GlobalSettings> = store.data.catch { failure ->
        if (failure is CorruptionException) emit(GlobalSettings()) else throw failure
    }

    override val settings: Flow<GlobalSettings> = storedSettings.catch { failure ->
        if (failure !is IOException) throw failure
        // A corrupt file is reset without a word (spec 4.3); only a file that cannot be read at all is reported.
        if (failure !is CorruptionException) readFailure.compareAndSet(null, failure)
        emit(GlobalSettings())
    }

    override fun takeReadFailure(): IOException? = readFailure.get()?.takeIf { takenReadFailure.compareAndSet(false, true) }

    override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) {
        store.updateData { transform(it).normalized() }
    }
}

/**
 * The file format of docs/spec.md section 7: the settings and [SETTINGS_FORMAT_KEY]. A file without the key was
 * written before the format had a version. Only a file that is not a JSON object is corrupt.
 */
internal object SettingsSerializer : Serializer<GlobalSettings> {
    override val defaultValue = GlobalSettings()

    override suspend fun readFrom(input: InputStream): GlobalSettings {
        val fields = try {
            SettingsJson.parseToJsonElement(input.readBytes().decodeToString()) as? JsonObject
        } catch (e: SerializationException) {
            throw CorruptionException("unreadable settings", e)
        } ?: throw CorruptionException("settings are not a JSON object")
        val format = (fields[SETTINGS_FORMAT_KEY] as? JsonPrimitive)?.intOrNull ?: 1
        return fields.upgradedFrom(format).toGlobalSettings()
    }

    override suspend fun writeTo(t: GlobalSettings, output: OutputStream) {
        val fields = SettingsJson.encodeToJsonElement(GlobalSettings.serializer(), t).jsonObject
        output.write(JsonObject(fields + (SETTINGS_FORMAT_KEY to JsonPrimitive(SETTINGS_FORMAT))).toString().encodeToByteArray())
    }
}

internal const val SETTINGS_FORMAT_KEY = "format"

internal fun JsonObject.toGlobalSettings(): GlobalSettings =
    SettingsJson.decodeFields(GlobalSettings.serializer(), this).normalized()

internal fun openSettings(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    file: () -> File,
): DataStore<GlobalSettings> = DataStoreFactory.create(
    serializer = SettingsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { GlobalSettings() },
    scope = scope,
    produceFile = file,
)

internal const val SETTINGS_FILE = "settings.json"

internal fun openSettings(context: Context): DataStore<GlobalSettings> =
    openSettings { context.dataStoreFile(SETTINGS_FILE) }
