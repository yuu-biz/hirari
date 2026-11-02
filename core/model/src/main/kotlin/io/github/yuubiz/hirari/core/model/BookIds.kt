package io.github.yuubiz.hirari.core.model

import java.security.MessageDigest

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

private fun sha256(): MessageDigest = MessageDigest.getInstance("SHA-256")

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
