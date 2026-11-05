package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.BookmarkScope
import io.github.yuubiz.hirari.core.model.BookSource
import io.github.yuubiz.hirari.core.model.BookmarkSort
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.flow.first

internal data class BookmarkDeletion(val scope: BookmarkScope, val count: Int)

/** A null scope asks storage which books are missing; failures never count as absence. */
internal suspend fun LibraryServices.bookmarkDeletion(requested: BookmarkScope?): BookmarkDeletion {
    val groups = bookmarks.observe(BookmarkSort.Newest).first()
    val places = locations.locations.first().associateBy { it.id }
    val listings = mutableMapOf<Pair<LibraryLocation, String>, Listing>()
    suspend fun listing(place: LibraryLocation, path: String): Listing = listings.getOrPut(place to path) {
        when (place) {
            is LibraryLocation.SafTree -> if (place.permissionHeld) folders.list(place.treeUri, path, null) else Listing.Failed(ListFailure.NoPermission)
            is LibraryLocation.SmbShare -> smb.list(place, path)
        }
    }
    val scope = requested ?: BookmarkScope.Books(groups.filter { group ->
        val source = group.info?.source as? BookSource.InLocation ?: return@filter false
        val place = places[source.location] ?: return@filter true
        if (listing(place, "") !is Listing.Listed) return@filter false
        // Archive text entries share the outer file's path, so existence checks use that file.
        val path = normalizePath(source.path)
        val parent = path.substringBeforeLast('/', "")
        when (val result = listing(place, parent)) {
            is Listing.Listed -> path.isNotEmpty() && result.entries.none { normalizePath(it.name) == path.substringAfterLast('/') }
            is Listing.Failed -> result.reason == ListFailure.NotFound
        }
    }.map { it.book })
    val count = groups.filter { group ->
        val source = group.info?.source as? BookSource.InLocation
        when (scope) {
            BookmarkScope.All -> true
            is BookmarkScope.Books -> group.book in scope.books
            is BookmarkScope.InLocation -> source?.location == scope.location
            is BookmarkScope.UnderFolder -> source?.location == scope.location && normalizePath(source.path).let { path ->
                val folder = normalizePath(scope.folder)
                folder.isEmpty() || path.startsWith("$folder/")
            }
            BookmarkScope.OnDevice -> source == null || places[source.location] !is LibraryLocation.SmbShare
        }
    }.sumOf { it.bookmarks.size }
    return BookmarkDeletion(scope, count)
}
