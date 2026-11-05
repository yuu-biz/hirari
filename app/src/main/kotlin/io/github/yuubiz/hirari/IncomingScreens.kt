package io.github.yuubiz.hirari

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.system.Os
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.feature.library.PlaceShortcut
import io.github.yuubiz.hirari.feature.library.ShortcutTarget
import io.github.yuubiz.hirari.feature.library.resolve
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads the head and, for possible text, the tail of the document off the main thread and opens it in the reader its
 * content calls for (docs/spec.md sections 4.2 and 4.11). It keeps the grant when the provider offers a lasting one;
 * otherwise the document opens on the grant of this start alone.
 */
@Composable
internal fun IncomingDocument(route: IncomingRoute, nav: NavController, resolver: ContentResolver) {
    LaunchedEffect(route) {
        val uri = Uri.parse(route.uri)
        val target = withContext(Dispatchers.IO) {
            try {
                resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
            }
            val prefix = try {
                resolver.openInputStream(uri)?.use { it.readPrefix() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            val tail = if (prefix != null && incomingKind(prefix) == IncomingKind.Text) resolver.readTail(uri) else null
            route.readerRoute(prefix, tail)
        }
        nav.navigate(target) { popUpTo<IncomingRoute> { inclusive = true } }
    }
    Waiting()
}

/**
 * Looks the shortcut's place and path up and opens it over home, or opens home alone and calls [onMissing] when
 * they are gone (docs/spec.md section 4.11).
 */
@Composable
internal fun ShortcutOpen(route: ShortcutRoute, nav: NavController, app: HirariApp, onMissing: () -> Unit) {
    LaunchedEffect(route) {
        val target = app.library.await().resolve(PlaceShortcut(LocationId(route.location), route.path, route.folder, label = ""))
        nav.navigate(HomeRoute) { popUpTo<ShortcutRoute> { inclusive = true } }
        when (target) {
            is ShortcutTarget.Folder -> nav.navigate(target.key.toRoute())
            is ShortcutTarget.Book -> nav.navigate(target.request.toReaderRoute())
            is ShortcutTarget.Text -> nav.navigate(target.address.toRoute())
            ShortcutTarget.Missing -> onMissing()
        }
    }
    Waiting()
}

@Composable
private fun Waiting() {
    Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
}

private fun InputStream.readPrefix(): ByteArray {
    val bytes = ByteArray(INCOMING_PREFIX_BYTES)
    var filled = 0
    while (filled < bytes.size) {
        val read = read(bytes, filled, bytes.size - filled)
        if (read < 0) break
        filled += read
    }
    return bytes.copyOf(filled)
}

/** A provider may supply a pipe or an unknown size; either leaves the head-only decision in place. */
private fun ContentResolver.readTail(uri: Uri): ByteArray? = try {
    openFileDescriptor(uri, "r")?.use { descriptor ->
        val size = descriptor.statSize
        if (size < 0) return@use null
        val bytes = ByteArray(minOf(size, INCOMING_TAIL_BYTES.toLong()).toInt())
        val start = size - bytes.size
        var filled = 0
        while (filled < bytes.size) {
            val read = Os.pread(descriptor.fileDescriptor, bytes, filled, bytes.size - filled, start + filled)
            if (read <= 0) return@use null
            filled += read
        }
        bytes
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/**
 * Asks the launcher to pin [shortcut] (docs/spec.md section 4.11). The intent names the place and the path, never a
 * credential. False when the launcher cannot pin shortcuts. Binder calls, so off the main thread.
 */
internal suspend fun Context.pinShortcut(shortcut: PlaceShortcut): Boolean = withContext(Dispatchers.IO) {
    val manager = getSystemService(ShortcutManager::class.java)
    if (manager == null || !manager.isRequestPinShortcutSupported) return@withContext false
    val intent = Intent(ACTION_OPEN_PLACE)
        .setClass(this@pinShortcut, MainActivity::class.java)
        .putExtra(EXTRA_LOCATION, shortcut.location.value)
        .putExtra(EXTRA_PATH, shortcut.path)
        .putExtra(EXTRA_FOLDER, shortcut.folder)
    val kind = if (shortcut.folder) "folder" else "book"
    val info = ShortcutInfo.Builder(this@pinShortcut, "place-${shortcut.location.value}-$kind-${shortcut.path}")
        .setShortLabel(shortcut.label)
        .setLongLabel(shortcut.label)
        .setIcon(Icon.createWithResource(this@pinShortcut, if (shortcut.folder) R.drawable.ic_shortcut_folder else R.drawable.ic_shortcut_book))
        .setIntent(intent)
        .build()
    manager.requestPinShortcut(info, null)
}
