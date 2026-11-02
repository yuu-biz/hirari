package io.github.yuubiz.hirari.core.model

/** Where the reader finds a book. */
sealed interface BookAddress {
    /** One document picked through SAF or handed over by another app. */
    data class Document(val uri: UriString) : BookAddress

    /** The images of a folder tree picked through SAF. */
    data class Tree(val uri: UriString) : BookAddress

    /**
     * A book at [path] inside a registered place (docs/spec.md section 2.2). [documentId] is the provider's id
     * of the document or folder when the caller already knows it; without it the reader looks the path up.
     */
    data class InLocation(
        val location: LocationId,
        val path: String,
        val folder: Boolean,
        val documentId: String? = null,
    ) : BookAddress
}

/** Which page the reader shows first. */
sealed interface StartAt {
    /** The stored position, or the first page of a book never read. */
    data object Saved : StartAt

    /** A bookmark's page (docs/spec.md section 4.6). */
    data class Page(val index: Int) : StartAt

    /** The page named [name], for an image file tapped in a file list (section 4.3). */
    data class Image(val name: String) : StartAt
}

data class OpenBookRequest(val book: BookAddress, val start: StartAt = StartAt.Saved)

/** How the library opens a book it found in the index. */
fun Book.address(): BookAddress {
    val folder = format == BookFormat.Images(Container.Folder)
    return when (val source = source) {
        is BookSource.InLocation -> BookAddress.InLocation(source.location, source.path, folder)
        is BookSource.External -> if (folder) BookAddress.Tree(source.uri) else BookAddress.Document(source.uri)
    }
}
