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
import kotlinx.serialization.json.JsonObject
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

/** The file format of docs/spec.md section 7. Only a file that is not a JSON object is corrupt. */
internal object SettingsSerializer : Serializer<GlobalSettings> {
    override val defaultValue = GlobalSettings()

    override suspend fun readFrom(input: InputStream): GlobalSettings {
        val fields = try {
            SettingsJson.parseToJsonElement(input.readBytes().decodeToString()) as? JsonObject
        } catch (e: SerializationException) {
            throw CorruptionException("unreadable settings", e)
        } ?: throw CorruptionException("settings are not a JSON object")
        return fields.toGlobalSettings()
    }

    override suspend fun writeTo(t: GlobalSettings, output: OutputStream) {
        output.write(SettingsJson.encodeToString(GlobalSettings.serializer(), t).encodeToByteArray())
    }
}

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

internal fun openSettings(context: Context): DataStore<GlobalSettings> =
    openSettings { context.dataStoreFile("settings.json") }
