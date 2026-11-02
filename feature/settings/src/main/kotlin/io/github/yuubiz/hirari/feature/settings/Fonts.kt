package io.github.yuubiz.hirari.feature.settings

import android.content.ContentResolver
import android.net.Uri
import io.github.yuubiz.hirari.core.data.FontImport
import io.github.yuubiz.hirari.core.data.FontStore
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.source.documentInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/** The fonts imported for the text reader (docs/spec.md section 4.9), as the settings screen uses them. Every call runs its I/O off the main thread. */
interface FontLibrary {
    /** The stored file names in natural order. */
    suspend fun list(): List<String>

    /** Copies the font at [uri] into the library. */
    suspend fun import(uri: UriString): FontImport
}

/**
 * The types the font picker asks for. Providers label fonts inconsistently, and many give `application/octet-stream`,
 * so [FontStore] checks the bytes.
 */
internal val FONT_MIME_TYPES = arrayOf(
    "font/ttf", "font/otf", "font/collection", "font/sfnt",
    "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/font-sfnt",
    "application/octet-stream",
)

internal class StorageFonts(
    private val fonts: FontStore,
    private val resolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : FontLibrary {
    override suspend fun list(): List<String> = withContext(io) { fonts.list() }

    override suspend fun import(uri: UriString): FontImport = try {
        withContext(io) {
            val parsed = Uri.parse(uri.value)
            val name = try {
                resolver.documentInfo(parsed)?.name
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: parsed.lastPathSegment.orEmpty()
            (resolver.openInputStream(parsed) ?: throw IOException("no stream for $uri")).use { fonts.import(name, it) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: IOException) {
        FontImport.Unreadable
    } catch (_: SecurityException) {
        FontImport.Unreadable
    }
}

/** What the screen tells of a finished import. */
internal fun FontImport.toResult(): ActionResult = when (this) {
    is FontImport.Imported -> ActionResult.FontImported(fileName)
    FontImport.NotAFont -> ActionResult.Failed(Problem.NotAFont)
    FontImport.NoRoom -> ActionResult.Failed(Problem.NoRoomForFont)
    FontImport.Unreadable -> ActionResult.Failed(Problem.CannotReadFont)
}
