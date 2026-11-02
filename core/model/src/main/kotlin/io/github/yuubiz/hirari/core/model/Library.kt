package io.github.yuubiz.hirari.core.model

import java.nio.charset.Charset
import java.time.Instant

/** The string form of an Android `Uri`, so this module stays free of Android types. */
@JvmInline
value class UriString(val value: String)

@JvmInline
value class LocationId(val value: Long)

/** The SHA-256 of the normalized source. It never contains credentials. */
@JvmInline
value class BookId(val value: String)

/** The Keystore alias of an encrypted secret. */
@JvmInline
value class CredentialRef(val alias: String)

sealed interface LibraryLocation {
    val id: LocationId
    val displayName: String

    data class SafTree(
        override val id: LocationId,
        override val displayName: String,
        val treeUri: UriString,
    ) : LibraryLocation

    data class SmbShare(
        override val id: LocationId,
        override val displayName: String,
        val host: String,
        val port: Int = 445,
        val share: String,
        val basePath: String,
        val domain: String?,
        /** `null` connects anonymously. */
        val user: String?,
        val credential: CredentialRef?,
    ) : LibraryLocation
}

sealed interface BookSource {
    data class InLocation(val location: LocationId, val path: String) : BookSource
    data class External(val uri: UriString) : BookSource
}

enum class Container { Zip, Rar, SevenZip, Tar, Folder, Pdf }

sealed interface BookFormat {
    data class Images(val container: Container) : BookFormat

    /** A `null` charset is detected automatically. */
    data class Text(val charset: Charset?) : BookFormat
}

data class Book(
    val id: BookId,
    val source: BookSource,
    val format: BookFormat,
    val title: String,
    val byteSize: Long,
    val modified: Instant,
    /** Unknown until the book is opened once. */
    val pageCount: Int?,
)
