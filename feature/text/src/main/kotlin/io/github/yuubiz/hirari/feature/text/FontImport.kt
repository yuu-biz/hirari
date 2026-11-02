package io.github.yuubiz.hirari.feature.text

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import io.github.yuubiz.hirari.core.data.FontImport
import io.github.yuubiz.hirari.core.data.FontStore
import io.github.yuubiz.hirari.core.source.documentInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * The types the font picker asks for. Providers label fonts inconsistently, and many give `application/octet-stream`,
 * so the store checks the bytes (docs/spec.md section 4.9, 書体と色).
 */
val FONT_MIME_TYPES = arrayOf(
    "font/ttf", "font/otf", "font/collection", "font/sfnt",
    "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/font-sfnt",
    "application/octet-stream",
)

/** Copies the font at [uri] into [fonts] under the provider's name for it. Runs its I/O off the main thread. */
suspend fun importFont(resolver: ContentResolver, uri: Uri, fonts: FontStore): FontImport = try {
    withContext(Dispatchers.IO) {
        val name = try {
            resolver.documentInfo(uri)?.name
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment.orEmpty()
        (resolver.openInputStream(uri) ?: throw IOException("no stream for $uri")).use { fonts.import(name, it) }
    }
} catch (e: CancellationException) {
    throw e
} catch (_: IOException) {
    FontImport.Unreadable
} catch (_: SecurityException) {
    FontImport.Unreadable
}

/**
 * A launcher of the SAF picker that imports the picked font into [fonts] and reports the outcome to [onResult] on
 * the main thread. The settings screen sets `textTypeface` to `TextTypeface.Imported(fileName)` on
 * [FontImport.Imported].
 */
@Composable
fun rememberFontImport(fonts: FontStore, onResult: (FontImport) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val report by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { report(importFont(resolver, uri, fonts)) }
    }
    return { launcher.launch(FONT_MIME_TYPES) }
}
