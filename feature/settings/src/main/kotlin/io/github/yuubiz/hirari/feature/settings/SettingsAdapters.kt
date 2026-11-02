package io.github.yuubiz.hirari.feature.settings

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import io.github.yuubiz.hirari.core.archive.ExtractionCache
import io.github.yuubiz.hirari.core.data.ImportPreparation
import io.github.yuubiz.hirari.core.data.SettingsTransfer
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Connects the settings screen to the app's [storage]. */
fun settingsServices(storage: Storage, context: Context): SettingsServices {
    val application = context.applicationContext
    return SettingsServices(
        settings = storage.settings,
        transfer = StorageTransfer(storage.transfer),
        documents = ContentResolverDocuments(application.contentResolver),
        clearThumbnails = storage.thumbnails::clear,
        clearExtraction = { ExtractionCache.under(application.cacheDir).clear() },
    )
}

internal class StorageTransfer(private val transfer: SettingsTransfer) : SettingsFileTransfer {
    override suspend fun export(includeReading: Boolean): String = transfer.export(includeReading)

    override suspend fun prepare(text: String): PreparedImport? = when (val prepared = transfer.prepare(text)) {
        ImportPreparation.NotASettingsFile -> null
        is ImportPreparation.Ready -> object : PreparedImport {
            override val changes = prepared.changes

            override suspend fun apply() = transfer.apply(prepared.plan)
        }
    }
}

/** A settings file with every position, bookmark and history entry stays far below this. */
internal const val MAX_SETTINGS_FILE_BYTES = 32L * 1024 * 1024

internal class ContentResolverDocuments(
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : DocumentStore {
    override suspend fun write(uri: UriString, text: String) = withContext(io) {
        val stream = resolver.openOutputStream(Uri.parse(uri.value), "wt") ?: throw IOException("the document cannot be opened for writing")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun read(uri: UriString): String = withContext(io) {
        val stream = resolver.openInputStream(Uri.parse(uri.value)) ?: throw IOException("the document cannot be opened for reading")
        stream.use { input ->
            val bytes = ByteArrayOutputStream()
            val chunk = ByteArray(CHUNK_BYTES)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (bytes.size() + count > MAX_SETTINGS_FILE_BYTES) throw DocumentTooLargeException()
                bytes.write(chunk, 0, count)
            }
            String(bytes.toByteArray(), Charsets.UTF_8)
        }
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
