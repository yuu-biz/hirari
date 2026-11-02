package io.github.yuubiz.hirari.core.model

import java.security.MessageDigest
import java.text.Normalizer

/** The bytes of a document that identify it (Q10 in docs/spec.md section 10). */
const val BOOK_ID_PREFIX_BYTES = 64 * 1024

/**
 * The id of a book picked as a single document: the SHA-256 of its first [BOOK_ID_PREFIX_BYTES] bytes and its
 * size. [prefix] holds the first bytes of the document, all of them when it is shorter. The id stays the same
 * when another app or a new grant hands over the same file under a different URI.
 */
fun documentBookId(prefix: ByteArray, size: Long): BookId {
    val digest = sha256()
    digest.update("document\u0000".toByteArray(Charsets.UTF_8))
    digest.update(prefix, 0, minOf(prefix.size, BOOK_ID_PREFIX_BYTES))
    digest.update(size.toString().toByteArray(Charsets.UTF_8))
    return BookId(digest.digest().toHex())
}

/** The id of a folder picked as a book, from the URI of its tree, which the persisted grant keeps stable. */
fun folderBookId(treeUri: UriString): BookId {
    val digest = sha256()
    digest.update("folder\u0000".toByteArray(Charsets.UTF_8))
    digest.update(treeUri.value.toByteArray(Charsets.UTF_8))
    return BookId(digest.digest().toHex())
}

/**
 * The id of a book in a registered location, from what names the place and the path inside it (docs/spec.md
 * section 2.2). It never uses the row id of the location, so registering the same folder or server again, or
 * importing a settings file, finds the same books. It never uses the user name or the password either.
 */
fun locationBookId(location: LibraryLocation, path: String): BookId {
    val (place, inside) = when (location) {
        is LibraryLocation.SafTree -> "saf\u0000${location.treeUri.value}" to path
        is LibraryLocation.SmbShare ->
            "smb\u0000${location.host.lowercase()}\u0000${location.port}\u0000${location.share.lowercase()}" to
                "${location.basePath}/$path"
    }
    val digest = sha256()
    digest.update("location\u0000$place\u0000${normalizePath(inside)}".toByteArray(Charsets.UTF_8))
    return BookId(digest.digest().toHex())
}

/**
 * The id of a text entry inside an archive book (docs/spec.md section 2.2): the id of the archive and the raw bytes
 * of the entry name, so picking another name charset (section 3.4) keeps the entry's progress and bookmarks.
 */
fun entryBookId(archive: BookId, rawName: ByteArray): BookId {
    val digest = sha256()
    digest.update("entry\u0000${archive.value}\u0000".toByteArray(Charsets.UTF_8))
    digest.update(rawName)
    return BookId(digest.digest().toHex())
}

/** NFC, `/` between names, and no empty or `.` names, so `a\\b/` and `/a/./b` name the same entry. */
fun normalizePath(path: String): String =
    Normalizer.normalize(path, Normalizer.Form.NFC)
        .split('/', '\\')
        .filter { it.isNotEmpty() && it != "." }
        .joinToString("/")

private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
