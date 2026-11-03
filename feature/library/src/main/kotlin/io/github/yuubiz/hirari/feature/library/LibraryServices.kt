package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.BookRepository
import io.github.yuubiz.hirari.core.data.BookmarkRepository
import io.github.yuubiz.hirari.core.data.CoverIndex
import io.github.yuubiz.hirari.core.data.HistoryRepository
import io.github.yuubiz.hirari.core.data.LaunchRepository
import io.github.yuubiz.hirari.core.data.LocationRepository
import io.github.yuubiz.hirari.core.data.ProgressRepository
import io.github.yuubiz.hirari.core.data.SettingsRepository
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.coroutines.CoroutineScope
import java.time.Instant

/** The persisted grants of SAF trees (docs/spec.md sections 4.2, 4.5 and 7). Each call is a Binder call off the main thread. */
interface TreeGrants {
    /** Takes the persistable read grant of [tree]; false when the provider offers none. */
    suspend fun take(tree: UriString): Boolean

    suspend fun release(tree: UriString)

    /** Takes the persistable read and write grant of [tree], a destination of 端末に保存 (docs/spec.md section 4.5); false when the provider offers none. */
    suspend fun takeWrite(tree: UriString): Boolean

    /** Gives back the write grant of [tree] and leaves its read grant, which a place may hold. */
    suspend fun releaseWrite(tree: UriString)

    suspend fun held(): Set<UriString>

    /** The folder's own display name, or the last name in its URI when the provider gives none. */
    suspend fun name(tree: UriString): String
}

/**
 * A direct child of a folder, from the one query of docs/spec.md section 4.3, 読み込み. [documentId] is the SAF
 * provider's id; an SMB entry has none.
 */
data class FolderEntry(
    val name: String,
    val documentId: String?,
    val directory: Boolean,
    val size: Long?,
    val modified: Instant?,
)

/**
 * Why a folder did not list. The last four are of SMB places (docs/spec.md sections 4.3 and 4.5): the server did
 * not answer, refused the user, the stored password cannot be opened, or the system blocks the local network.
 */
enum class ListFailure { NoPermission, NotFound, ReadError, Unreachable, AuthenticationFailed, PasswordUnreadable, NoLocalNetwork }

sealed interface Listing {
    /** [folderDocumentId] is the listed folder's own SAF id, which opening it as a book needs. */
    data class Listed(val folderDocumentId: String?, val entries: List<FolderEntry>) : Listing

    data class Failed(val reason: ListFailure) : Listing
}

/** Lists one folder of a SAF place off the main thread. */
interface FolderLister {
    /** The children of the folder at [path] in [tree]. A known [documentId] saves looking the path up. */
    suspend fun list(tree: UriString, path: String, documentId: String?): Listing
}

/**
 * What the library screens read and write. [appScope] lives as long as the process, for work that must end even
 * when the screen that asked for it is left.
 */
class LibraryServices(
    val locations: LocationRepository,
    val books: BookRepository,
    val history: HistoryRepository,
    val bookmarks: BookmarkRepository,
    val progress: ProgressRepository,
    val settings: SettingsRepository,
    val grants: TreeGrants,
    val folders: FolderLister,
    val smb: SmbPlaces,
    val archives: ArchiveOpener,
    val launch: LaunchRepository,
    val coverIndex: CoverIndex,
    val covers: Covers,
    val saves: DeviceSaves,
    val appScope: CoroutineScope,
    val clock: () -> Instant = Instant::now,
) {
    /** The choices of folder covers under way, by place and folder path. */
    internal val folderCoverRuns = LatestRuns<Pair<LocationId, String>>(appScope)
}
