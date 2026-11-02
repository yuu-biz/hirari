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
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** Safe to call from the main thread. */
interface SettingsRepository {
    val settings: Flow<GlobalSettings>

    suspend fun update(transform: (GlobalSettings) -> GlobalSettings)
}

internal class DataStoreSettingsRepository(private val store: DataStore<GlobalSettings>) : SettingsRepository {
    override val settings: Flow<GlobalSettings> = store.data

    override suspend fun update(transform: (GlobalSettings) -> GlobalSettings) {
        store.updateData { transform(it).normalized() }
    }
}

/** The file format of docs/spec.md section 7. A file that is not valid JSON is corrupt. */
internal object SettingsSerializer : Serializer<GlobalSettings> {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }

    override val defaultValue = GlobalSettings()

    override suspend fun readFrom(input: InputStream): GlobalSettings = try {
        json.decodeFromString<GlobalSettings>(input.readBytes().decodeToString()).normalized()
    } catch (e: SerializationException) {
        throw CorruptionException("unreadable settings", e)
    }

    override suspend fun writeTo(t: GlobalSettings, output: OutputStream) {
        output.write(json.encodeToString(t).encodeToByteArray())
    }
}

internal fun openSettings(
    scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    file: () -> File,
): DataStore<GlobalSettings> = DataStoreFactory.create(
    serializer = SettingsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { GlobalSettings() },
    scope = scope,
    produceFile = file,
)

internal fun openSettings(context: Context): DataStore<GlobalSettings> =
    openSettings { context.dataStoreFile("settings.json") }
