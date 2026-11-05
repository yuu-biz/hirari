package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.model.Book
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.text.GlyphMetrics
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/** Why a text did not open. */
enum class TextFailure {
    NotFound,
    NoPermission,

    /** The provider cannot seek and the copy does not fit (docs/spec.md section 3.1). */
    NoRoomForCopy,

    /** The archive, or the entry, is encrypted (section 3.8). */
    Encrypted,

    /** Longer than [MAX_TEXT_BYTES], or more than the memory for texts holds (section 3.11). */
    TooLarge,
    ReadError,
}

/** The longest text the reader reads, whole, into memory (docs/spec.md section 3.11). */
const val MAX_TEXT_BYTES = 64 shl 20

/** The input and decode budget of one illustration (docs/spec.md section 4.9). */
internal fun pictureBudgetBytes(): Int = minOf(32L shl 20, Runtime.getRuntime().maxMemory() / 8).toInt()

/** Reads the 挿絵 at [path], resolved against the text, from the container the text came from. Null when it is not there. */
fun interface PictureReader {
    suspend fun read(path: String): ByteArray?
}

/**
 * A text opened on its source. [path] is where the text sits in its place or archive, for the 挿絵, and [pictures] reads
 * them; a single document has no reachable neighbors, so it has none. [book] is the entry for the library index,
 * or null when the source has no form there. [close] releases the source.
 */
class OpenedText(
    val id: BookId,
    val title: String,
    val path: String,
    val book: Book?,
    val pictures: PictureReader?,
    val close: () -> Unit,
)

sealed interface TextOpenResult {
    /** [bytes] is the whole text, which the reader drops once it is decoded; [text] stays open with the reader. */
    class Opened(val text: OpenedText, val bytes: ByteArray) : TextOpenResult

    data class Failed(val reason: TextFailure) : TextOpenResult
}

/**
 * Opens one text. It does its own I/O off the main thread and throws nothing but cancellation; a cancelled open
 * leaves nothing open.
 */
fun interface TextOpener {
    suspend fun open(): TextOpenResult
}

/**
 * [open] on [context]. When the caller is cancelled after [open] made its text, `withContext` drops the text; it is
 * closed here before the cancellation goes on, so the source is released either way.
 */
internal suspend fun openOn(context: CoroutineContext, open: suspend () -> TextOpenResult): TextOpenResult {
    val made = AtomicReference<TextOpenResult.Opened?>()
    try {
        return withContext(context) { open().also { if (it is TextOpenResult.Opened) made.set(it) } }
    } catch (e: CancellationException) {
        made.get()?.text?.close?.invoke()
        throw e
    }
}

/** The typefaces the reader lays out and draws with. */
interface TextFonts {
    /** [typeface], or Mincho when it names an imported font that is gone (docs/spec.md section 4.9). */
    suspend fun available(typeface: TextTypeface): TextTypeface

    /** Measures with [typeface]. One layout uses the result at a time. */
    suspend fun metrics(typeface: TextTypeface): GlyphMetrics
}

/** The library index and the history for one opened text (docs/spec.md sections 2.2, 2.8 and 4.6). */
interface TextHistory {
    /** The text opened at [openedAt]; [book] is null when it has no index entry. */
    fun opened(id: BookId, book: Book?, openedAt: java.time.Instant, limit: Int)

    /** The reader stopped or closed. Only the first call after [opened] writes. */
    fun stop()
}
