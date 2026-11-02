package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.model.UriString
import kotlinx.serialization.Serializable

@Serializable
internal data object HomeRoute

@Serializable
internal data object LicensesRoute

/** A folder of a SAF place (docs/spec.md section 4.3). Without [documentId] the list looks the path up. */
@Serializable
internal data class FolderRoute(val location: Long, val path: String, val documentId: String? = null)

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
    }
}
