package io.github.yuubiz.hirari

import io.github.yuubiz.hirari.core.image.isPdf
import io.github.yuubiz.hirari.core.image.sniffImage
import kotlinx.serialization.Serializable

/** What another app or a home screen shortcut hands the app (docs/spec.md section 4.11). */
internal sealed interface Incoming {
    /** A document by its content URI, from `ACTION_VIEW` or `ACTION_SEND`. */
    data class Document(val uri: String) : Incoming

    /** A folder or book of a place, from a shortcut the app made. */
    data class Shortcut(val location: Long, val path: String, val folder: Boolean) : Incoming
}

/** The action of the intents of the app's shortcuts. */
internal const val ACTION_OPEN_PLACE = "io.github.yuubiz.hirari.action.OPEN_PLACE"
internal const val EXTRA_LOCATION = "io.github.yuubiz.hirari.extra.LOCATION"
internal const val EXTRA_PATH = "io.github.yuubiz.hirari.extra.PATH"
internal const val EXTRA_FOLDER = "io.github.yuubiz.hirari.extra.FOLDER"

private const val ACTION_VIEW = "android.intent.action.VIEW"
private const val ACTION_SEND = "android.intent.action.SEND"

/**
 * What an intent asks for, from its parts: [data] of `ACTION_VIEW`, [stream] (`EXTRA_STREAM`) of `ACTION_SEND`, and
 * the extras of [ACTION_OPEN_PLACE]. Only `content:` URIs are read, since the app holds no storage permission.
 * Anything else, a launcher start among them, asks for nothing.
 */
internal fun incoming(action: String?, data: String?, stream: String?, location: Long?, path: String?, folder: Boolean): Incoming? {
    fun content(uri: String?) = uri?.takeIf { it.startsWith("content:", ignoreCase = true) }?.let(Incoming::Document)
    return when (action) {
        ACTION_VIEW -> content(data)
        ACTION_SEND -> content(stream)
        ACTION_OPEN_PLACE -> if (location != null && path != null) Incoming.Shortcut(location, path, folder) else null
        else -> null
    }
}

/** Which reader a handed-over document goes to, by its content (docs/spec.md section 3.2). */
internal enum class IncomingKind { Images, Text }

/** The bytes [incomingKind] looks at. */
internal const val INCOMING_PREFIX_BYTES = 4096

/**
 * The text row of the table of docs/spec.md section 3.2: none of the other formats, and no NUL in the first 4 KB
 * unless a UTF-16 byte order mark starts it. Everything else goes to the image reader, which says why when it does
 * not open.
 */
internal fun incomingKind(prefix: ByteArray): IncomingKind {
    val head = prefix.copyOf(minOf(prefix.size, INCOMING_PREFIX_BYTES))
    if (sniffImage(head) != null || isPdf(head) || isArchive(head)) return IncomingKind.Images
    val utf16 = head.startsWith(0xFF, 0xFE) || head.startsWith(0xFE, 0xFF)
    return if (utf16 || head.none { it == 0.toByte() }) IncomingKind.Text else IncomingKind.Images
}

private fun isArchive(head: ByteArray): Boolean =
    head.startsWith(0x50, 0x4B, 0x03, 0x04) || head.startsWith(0x50, 0x4B, 0x05, 0x06) ||
        head.startsWith(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) ||
        head.startsWith(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) ||
        head.startsWith(0x75, 0x73, 0x74, 0x61, 0x72, at = 257)

private fun ByteArray.startsWith(vararg bytes: Int, at: Int = 0): Boolean =
    size >= at + bytes.size && bytes.indices.all { this[at + it] == bytes[it].toByte() }

/** A document handed over by another app, whose kind is read off the main thread before a reader opens it. */
@Serializable
internal data class IncomingRoute(val uri: String)

/** A shortcut's folder or book, looked up before it opens. */
@Serializable
internal data class ShortcutRoute(val location: Long, val path: String, val folder: Boolean)

internal fun Incoming.toRoute(): Any = when (this) {
    is Incoming.Document -> IncomingRoute(uri)
    is Incoming.Shortcut -> ShortcutRoute(location, path, folder)
}
