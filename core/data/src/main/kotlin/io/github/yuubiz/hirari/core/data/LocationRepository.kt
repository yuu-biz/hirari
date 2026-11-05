package io.github.yuubiz.hirari.core.data

import androidx.room3.withWriteTransaction
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** What the SMB form of docs/spec.md section 4.5 enters, without the password. */
data class SmbServer(
    val displayName: String,
    val host: String,
    val port: Int = 445,
    val share: String,
    val basePath: String = "",
    val domain: String? = null,
    /** `null` connects anonymously. */
    val user: String? = null,
)

/** The edit form shows no password, so an edit says what happens to the stored one. */
sealed interface PasswordChange {
    data object Keep : PasswordChange
    data object Remove : PasswordChange

    /** The caller clears [secret] after the call. */
    class Set(val secret: CharArray) : PasswordChange {
        override fun toString() = "Set(***)"
    }
}

data class PinnedFolder(val id: Long, val location: LocationId, val path: String)

enum class FavoriteKind { Book, Folder }

data class Favorite(val location: LocationId, val path: String, val kind: FavoriteKind, val displayName: String, val addedAt: Instant)

/** The cover a user chose for a folder: an image file, or a page of a book with its `entryIndex`. */
data class FolderCover(val imagePath: String, val entry: Int? = null)

/** Places, pins, favorites and folder covers (docs/spec.md sections 4.2–4.6). Safe on the main thread. */
interface LocationRepository {
    val locations: Flow<List<LibraryLocation>>

    suspend fun get(id: LocationId): LibraryLocation?

    suspend fun addSafTree(displayName: String, treeUri: UriString): LocationId

    /** [password] is left for the caller to clear. */
    suspend fun addSmbShare(server: SmbServer, password: CharArray?): LocationId

    suspend fun updateSmbShare(id: LocationId, server: SmbServer, password: PasswordChange)

    suspend fun rename(id: LocationId, displayName: String)

    /**
     * Removes the place with its pins, favorites, folder covers and password, and returns it so the caller can release
     * its SAF grant. Reading progress stays, so registering the place again brings it back (docs/spec.md 2.2).
     */
    suspend fun delete(id: LocationId): LibraryLocation?

    /** Marks each SAF place by whether its tree is among the grants the app holds, as after a restore. */
    suspend fun syncGrants(granted: Set<UriString>)

    val pinned: Flow<List<PinnedFolder>>

    suspend fun pin(location: LocationId, path: String, at: Instant)

    suspend fun unpin(id: Long)

    val favorites: Flow<List<Favorite>>

    /** Adds a normalized address once; adding it again keeps its original name and time. */
    suspend fun addFavorite(location: LocationId, path: String, kind: FavoriteKind, displayName: String, at: Instant)

    suspend fun removeFavorite(location: LocationId, path: String)

    fun folderCover(location: LocationId, path: String): Flow<FolderCover?>

    /** `null` goes back to the first readable image (docs/spec.md section 4.4). */
    suspend fun setFolderCover(location: LocationId, path: String, cover: FolderCover?)
}

internal class RoomLocationRepository(
    private val database: HirariDatabase,
    private val credentials: CredentialStore,
) : LocationRepository {
    private val dao = database.locations()
    private val folders = database.folders()

    override val locations: Flow<List<LibraryLocation>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun get(id: LocationId): LibraryLocation? = dao.get(id.value)?.toModel()

    override suspend fun addSafTree(displayName: String, treeUri: UriString): LocationId = LocationId(
        dao.insert(LocationRow(kind = LocationKind.Saf, displayName = displayName, treeUri = treeUri.value, permissionHeld = true)),
    )

    override suspend fun addSmbShare(server: SmbServer, password: CharArray?): LocationId {
        val ref = password?.let { credentials.store(it) }
        return LocationId(dao.insert(server.toRow(id = 0, credential = ref)))
    }

    override suspend fun updateSmbShare(id: LocationId, server: SmbServer, password: PasswordChange) {
        val stored = dao.get(id.value)?.takeIf { it.kind == LocationKind.Smb } ?: return
        val kept = stored.credentialAlias?.let(::CredentialRef)
        val ref = when (password) {
            PasswordChange.Keep -> kept
            PasswordChange.Remove -> null.also { kept?.let { credentials.delete(it) } }
            is PasswordChange.Set -> kept?.also { credentials.replace(it, password.secret) } ?: credentials.store(password.secret)
        }
        dao.update(server.toRow(id = id.value, credential = ref))
    }

    override suspend fun rename(id: LocationId, displayName: String) = dao.rename(id.value, displayName)

    override suspend fun delete(id: LocationId): LibraryLocation? {
        val row = database.withWriteTransaction<LocationRow?> {
            dao.get(id.value)?.also {
                folders.unpinLocation(id.value)
                folders.clearLocationFavorites(id.value)
                folders.clearLocationCovers(id.value)
                dao.delete(id.value)
            }
        } ?: return null
        row.credentialAlias?.let { credentials.delete(CredentialRef(it)) }
        return row.toModel()
    }

    override suspend fun syncGrants(granted: Set<UriString>) = dao.setGrants(granted.map { it.value })

    override val pinned: Flow<List<PinnedFolder>> = folders.observePinned().map { rows ->
        rows.map { PinnedFolder(it.id, LocationId(it.locationId), it.path) }
    }

    override suspend fun pin(location: LocationId, path: String, at: Instant) =
        folders.pin(PinnedFolderRow(locationId = location.value, path = normalizePath(path), pinnedAt = at.toEpochMilli()))

    override suspend fun unpin(id: Long) = folders.unpin(id)

    override val favorites: Flow<List<Favorite>> = folders.observeFavorites().map { rows ->
        rows.map { Favorite(LocationId(it.locationId), it.path, it.kind, it.displayName, Instant.ofEpochMilli(it.addedAt)) }
    }

    override suspend fun addFavorite(location: LocationId, path: String, kind: FavoriteKind, displayName: String, at: Instant) =
        folders.addFavorite(FavoriteRow(location.value, normalizePath(path), kind, displayName, at.toEpochMilli()))

    override suspend fun removeFavorite(location: LocationId, path: String) = folders.removeFavorite(location.value, normalizePath(path))

    override fun folderCover(location: LocationId, path: String): Flow<FolderCover?> =
        folders.observeCover(location.value, normalizePath(path)).map { row -> row?.let { FolderCover(it.imagePath, it.entry) } }

    override suspend fun setFolderCover(location: LocationId, path: String, cover: FolderCover?) {
        val folder = normalizePath(path)
        if (cover == null) {
            folders.clearCover(location.value, folder)
        } else {
            folders.setCover(FolderCoverRow(location.value, folder, normalizePath(cover.imagePath), cover.entry))
        }
    }
}

internal fun SmbServer.toRow(id: Long, credential: CredentialRef?) = LocationRow(
    id = id,
    kind = LocationKind.Smb,
    displayName = displayName,
    host = host,
    port = port,
    share = share,
    basePath = normalizePath(basePath),
    domain = domain,
    user = user,
    credentialAlias = credential?.alias,
)
