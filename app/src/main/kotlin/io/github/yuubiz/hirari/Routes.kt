package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.NeighborOpening
import io.github.yuubiz.hirari.core.model.NeighborStart
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.normalizePath
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import android.net.Uri
import io.github.yuubiz.hirari.feature.library.ArchiveFile
import io.github.yuubiz.hirari.feature.library.ArchiveKey
import io.github.yuubiz.hirari.feature.library.CoverRangeKey
import io.github.yuubiz.hirari.feature.library.FolderKey
import io.github.yuubiz.hirari.feature.library.TextAddress
import io.github.yuubiz.hirari.feature.text.EntryArchive
import io.github.yuubiz.hirari.feature.text.TextBookRef
import io.github.yuubiz.hirari.feature.library.childToward
import io.github.yuubiz.hirari.feature.reader.SettingsTarget
import io.github.yuubiz.hirari.feature.settings.SettingsOpening
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data object HomeRoute

@Serializable
internal data object LicensesRoute

/** The settings screen, opened on [opening] (docs/spec.md section 4.8, メニュー). */
@Serializable
internal data class SettingsRoute(val opening: SettingsOpening = SettingsOpening.List)

/** Where the reader's 設定, コマンドバーの編集, and このアプリについて open the settings. */
internal fun SettingsTarget.toRoute() = SettingsRoute(
    when (this) {
        SettingsTarget.Settings -> SettingsOpening.List
        SettingsTarget.CommandBar -> SettingsOpening.CommandBar
        SettingsTarget.About -> SettingsOpening.About
    },
)

/** The SMB form (docs/spec.md section 4.5): a new server without [location], else that server. */
@Serializable
internal data class SmbServerRoute(val location: Long? = null)

/**
 * A folder of a SAF place (docs/spec.md section 4.3). Without [documentId] the list looks the path up. [reveal] is
 * the path of a row the list scrolls to when it first shows.
 */
@Serializable
internal data class FolderRoute(val location: Long, val path: String, val documentId: String? = null, val reveal: String? = null) {
    fun toKey() = FolderKey(LocationId(location), path, documentId)
}

/** 書庫内一覧 of an archive book in a place (docs/spec.md section 4.7). */
@Serializable
internal data class ArchiveRoute(val location: Long, val path: String, val documentId: String? = null) {
    fun toKey() = ArchiveKey(LocationId(location), path, documentId)
}

internal fun ArchiveKey.toRoute() = ArchiveRoute(location.value, path, documentId)

/** 範囲を選んで表紙にする (docs/spec.md section 4.4): the page with the `entryIndex` [entry] of the indexed book [book]. */
@Serializable
internal data class CoverRangeRoute(val book: String, val entry: Int) {
    fun toKey() = CoverRangeKey(BookId(book), entry)
}

internal fun CoverRangeKey.toRoute() = CoverRangeRoute(book.value, entry)

internal fun FolderKey.toRoute(reveal: String? = null) = FolderRoute(location.value, path, documentId, reveal)

/**
 * Goes up from the folder at [from] to the folder [to] of the same place (docs/spec.md section 4.3, 上部バー and
 * 戻ったとき). The folders below [to] on top of the back stack are popped. When [to] is under them, it keeps its
 * scroll position; when it is not, as above a pinned folder, it opens scrolled to the folder the way down went
 * through.
 */
internal fun NavController.goUpTo(to: FolderKey, from: String) = goUp(
    to,
    from,
    top = { currentBackStackEntry?.takeIf { it.destination.hasRoute<FolderRoute>() }?.toRoute<FolderRoute>() },
    pop = { popBackStack() },
    open = { navigate(it) },
)

/** [goUpTo] over a back stack whose [top] file list, `null` for any other screen, [pop] closes. */
internal fun goUp(to: FolderKey, from: String, top: () -> FolderRoute?, pop: () -> Unit, open: (FolderRoute) -> Unit) {
    while (true) {
        val folder = top() ?: break
        if (folder.location != to.location.value) break
        if (normalizePath(folder.path) == normalizePath(to.path)) return
        if (childToward(to.path, folder.path) == null) break
        pop()
    }
    open(to.toRoute(reveal = childToward(to.path, from)))
}

/**
 * The reader's arguments, flattened for the navigation arguments. [toRequest] and [toReaderRoute] convert at
 * this boundary, so the screens only see [OpenBookRequest].
 */
@Serializable
internal data class ReaderRoute(
    val kind: Kind,
    val uri: String = "",
    val location: Long = 0,
    val path: String = "",
    val documentId: String? = null,
    val startPage: Int? = null,
    val startImage: String? = null,
    val startEntry: Int? = null,
    val startOffset: Float = 0f,
) {
    enum class Kind { Document, Tree, LocationDocument, LocationFolder }

    fun toRequest(): OpenBookRequest {
        val book = when (kind) {
            Kind.Document -> BookAddress.Document(UriString(uri))
            Kind.Tree -> BookAddress.Tree(UriString(uri))
            Kind.LocationDocument -> BookAddress.InLocation(LocationId(location), path, folder = false, documentId)
            Kind.LocationFolder -> BookAddress.InLocation(LocationId(location), path, folder = true, documentId)
        }
        val start = when {
            startPage != null -> StartAt.Page(startPage)
            startImage != null -> StartAt.Image(startImage)
            startEntry != null -> StartAt.Entry(startEntry, startOffset)
            else -> StartAt.Saved
        }
        return OpenBookRequest(book, start)
    }
}

internal fun OpenBookRequest.toReaderRoute(): ReaderRoute {
    val start = start
    val route = when (val book = book) {
        is BookAddress.Document -> ReaderRoute(ReaderRoute.Kind.Document, uri = book.uri.value)
        is BookAddress.Tree -> ReaderRoute(ReaderRoute.Kind.Tree, uri = book.uri.value)
        is BookAddress.InLocation -> ReaderRoute(
            kind = if (book.folder) ReaderRoute.Kind.LocationFolder else ReaderRoute.Kind.LocationDocument,
            location = book.location.value,
            path = book.path,
            documentId = book.documentId,
        )
    }
    return when (start) {
        StartAt.Saved -> route
        is StartAt.Page -> route.copy(startPage = start.index)
        is StartAt.Image -> route.copy(startImage = start.name)
        is StartAt.Entry -> route.copy(startEntry = start.entryIndex, startOffset = start.offset)
    }
}

/** The text reader's arguments (docs/spec.md section 4.9), flattened like [ReaderRoute]. */
@Serializable
internal data class TextRoute(
    val kind: Kind,
    val uri: String = "",
    val location: Long = 0,
    val path: String = "",
    val documentId: String? = null,
    val entryIndex: Int = -1,
    val archiveId: String = "",
    /** Where the text opens: the neighboring text of 本の端での動作 opens at its start or end. */
    val start: NeighborStart = NeighborStart.Saved,
    val startOffset: Long? = null,
) {
    /** [LocationArchiveEntry] addresses an archive through its device or SMB place. */
    enum class Kind {
        Document, InLocation, ArchiveEntry,
        @SerialName("ServerArchiveEntry") LocationArchiveEntry,
    }

    fun toRef(): TextBookRef = when (kind) {
        Kind.Document -> TextBookRef.Document(Uri.parse(uri))
        Kind.InLocation -> TextBookRef.InLocation(LocationId(location), path, documentId)
        Kind.ArchiveEntry -> TextBookRef.ArchiveEntry(EntryArchive.Document(Uri.parse(uri)), entryIndex, BookId(archiveId))
        Kind.LocationArchiveEntry -> TextBookRef.ArchiveEntry(EntryArchive.InLocation(LocationId(location), path), entryIndex, BookId(archiveId))
    }
}

internal fun NeighborOpening<TextBookRef.InLocation>.toRoute(): TextRoute =
    TextRoute(TextRoute.Kind.InLocation, location = book.location.value, path = book.path, documentId = book.documentId, start = start)

internal fun TextAddress.toRoute(): TextRoute {
    val route = when (this) {
        is TextAddress.Document -> TextRoute(TextRoute.Kind.Document, uri = uri.value)
        is TextAddress.InLocation -> TextRoute(TextRoute.Kind.InLocation, location = location.value, path = path, documentId = documentId)
        is TextAddress.ArchiveEntry -> when (val archive = archive) {
            is ArchiveFile.Document -> TextRoute(TextRoute.Kind.ArchiveEntry, uri = archive.uri.value, entryIndex = entryIndex, archiveId = archiveId.value)
            is ArchiveFile.InLocation ->
                TextRoute(TextRoute.Kind.LocationArchiveEntry, location = archive.location.value, path = archive.path, entryIndex = entryIndex, archiveId = archiveId.value)
        }
    }
    return route.copy(startOffset = startOffset)
}
