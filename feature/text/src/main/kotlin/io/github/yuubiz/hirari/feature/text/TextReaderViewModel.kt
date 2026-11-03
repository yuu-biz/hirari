package io.github.yuubiz.hirari.feature.text

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.os.Build
import android.os.Trace
import android.util.LruCache
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.FontStore
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.ProgressWriter
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndEffect
import io.github.yuubiz.hirari.core.model.Bookmark
import io.github.yuubiz.hirari.core.model.NeighborStart
import io.github.yuubiz.hirari.core.model.Sibling
import io.github.yuubiz.hirari.core.model.Siblings
import io.github.yuubiz.hirari.core.model.TextTypeface
import io.github.yuubiz.hirari.core.text.Heading
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the app shares with every text reader, one per process. Build it from the app's [Storage], the same
 * [ProgressWriter] the image reader uses, a scope that outlives every screen on `Dispatchers.IO`, and the files of
 * the SMB places through the connection the library shares. [siblings] finds the texts beside the open one.
 */
class TextReaderServices(
    val storage: Storage,
    val progress: ProgressWriter,
    val appScope: CoroutineScope,
    val smb: SmbFiles,
    val siblings: Siblings<TextBookRef, TextBookRef.InLocation> = Siblings.None,
)

/**
 * One open text (docs/spec.md section 4.9). The state is immutable and comes from [TextReaderModel]; this class
 * wires the model to Android: the document or archive, the typefaces, the 挿絵 and the lifecycle.
 */
class TextReaderViewModel(
    private val application: Application,
    private val services: Deferred<TextReaderServices>,
    private val ref: TextBookRef,
    private val startAt: NeighborStart = NeighborStart.Saved,
) : ViewModel() {
    private val pending = MutableStateFlow<TextReaderUiState>(TextReaderUiState.Opening)
    private var model: TextReaderModel? = null
    private var typefaces: TypefaceCache? = null
    private var stopped = false
    private var viewport: Viewport? = null
    private val early = EarlyTurn()
    private val ends = BookEnds(viewModelScope) { edge -> services.await().siblings.beside(ref, edge) }

    /** Decoded 挿絵, by path and size, so a turn back does not decode again. */
    private val pictures = object : LruCache<String, Bitmap>(PICTURE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    val state: StateFlow<TextReaderUiState> = pending.asStateFlow()

    /** A tick of 「めくりで振動」 for the screen to feel, one for each change of the view that a turn or a jump made. */
    val turnHaptics = Channel<Unit>(Channel.CONFLATED)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.beginAsyncSection(OPEN_SECTION, 0)
        viewModelScope.launch {
            val services = services.await()
            val copyDir = withContext(Dispatchers.IO) { File(application.cacheDir, "copy") }
            val fonts = TypefaceCache(services.storage.fonts)
            typefaces = fonts
            @OptIn(ExperimentalCoroutinesApi::class)
            val model = TextReaderModel(
                scope = viewModelScope,
                work = Dispatchers.Default.limitedParallelism(1),
                opener = opener(
                    ref,
                    TextSources(application.contentResolver, services.storage.locations, services.smb, copyDir) { entryCharsets(it, services.storage.books, services.storage.settings) },
                ),
                settings = services.storage.settings.settings,
                progress = services.storage.progress,
                writer = services.progress,
                bookmarks = services.storage.bookmarks,
                fonts = fonts,
                history = RepositoryTextHistory(services.storage.books, services.storage.history, services.appScope),
                startAt = startAt,
                onEdge = ::edgeReached,
                onTurned = { turnHaptics.trySend(Unit) },
            )
            this@TextReaderViewModel.model = model
            early.handOver(model)
            launch { model.state.collect { pending.value = it } }
            model.start()
            viewport?.let(model::onViewport)
            if (stopped) model.onStop()
            launch {
                model.state.first { it is TextReaderUiState.Failed || (it as? TextReaderUiState.Reading)?.run != null }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Trace.endAsyncSection(OPEN_SECTION, 0)
            }
        }
    }

    /** The typeface the pages of [typeface] were laid out with. */
    fun typeface(typeface: TextTypeface): Typeface = typefaces?.typeface(typeface) ?: Typeface.SERIF

    fun onViewport(viewport: Viewport) {
        this.viewport = viewport
        model?.onViewport(viewport)
    }

    /** Turns [views] views; a turn that runs into an end of the text goes on to [bookEnd] too. */
    fun turn(views: Int): TurnResult {
        ends.pageActed()
        val model = model
        val result = if (model != null) {
            model.turn(views)
        } else {
            early.queue(views)
            TurnResult.Waiting
        }
        if (result is TurnResult.Edge) edgeReached(result.edge)
        return result
    }

    /** What running into an end of the text asks of the screen, until it says it is handled. */
    val bookEnd: BookEndEffect<TextBookRef.InLocation>? get() = ends.effect

    fun bookEndHandled() = ends.handled()

    /** The text beside this one in its folder toward [edge], asked of the library once per edge. */
    suspend fun sibling(edge: BookEdge): Sibling<TextBookRef.InLocation> = ends.sibling(edge)

    private fun edgeReached(edge: BookEdge) {
        val action = (pending.value as? TextReaderUiState.Reading)?.settings?.atBookEnd ?: return
        ends.reached(edge, action)
    }

    /** Runs [move] as a page action: an end of the text still waiting for the library is dropped, since the reader moved on. */
    private inline fun moving(move: (TextReaderModel) -> Unit) {
        ends.pageActed()
        model?.let(move)
    }

    /** 位置を戻して閉じる: the text gets back the position and state it had when it opened. The screen closes next. */
    fun restorePosition() = model?.restorePosition() ?: Unit

    fun scrolledTo(index: Int) = moving { it.scrolledTo(index) }

    fun jumpTo(offset: Int) = moving { it.jumpTo(offset) }

    /** Saves a change to the settings, as the 文字の大きさ and 組み方 commands make. The reader follows the new settings. */
    fun updateSettings(transform: (GlobalSettings) -> GlobalSettings) {
        viewModelScope.launch { services.await().storage.settings.update(transform) }
    }

    suspend fun fonts(): FontStore = services.await().storage.fonts

    /** The imported fonts, read off the main thread. */
    suspend fun importedFonts(): List<String> = withContext(Dispatchers.IO) { fonts().list() }

    fun first() = moving { it.first() }

    fun last() = moving { it.last() }

    fun goToFraction(fraction: Float) = moving { it.goToFraction(fraction) }

    fun goTo(heading: Heading) = moving { it.goTo(heading) }

    fun goTo(bookmark: Bookmark) = moving { it.goTo(bookmark) }

    fun show(hit: SearchHit) = moving { it.show(hit) }

    fun search(query: String) = model?.search(query) ?: Unit

    fun clearSearch() = model?.clearSearch() ?: Unit

    fun addBookmark() = model?.addBookmark() ?: Unit

    fun deleteBookmark(bookmark: Bookmark) = model?.deleteBookmark(bookmark) ?: Unit

    /** The 挿絵 at [path] scaled to fit [maxWidth] by [maxHeight], decoded off the main thread, or null when unreadable. */
    suspend fun picture(path: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        cachedPicture(path, maxWidth, maxHeight)?.let { return it }
        val bytes = model?.picture(path) ?: return null
        val bitmap = withContext(Dispatchers.Default) { decodeFitting(bytes, maxWidth, maxHeight) } ?: return null
        pictures.put(pictureKey(path, maxWidth, maxHeight), bitmap)
        return bitmap
    }

    /** The 挿絵 [picture] decoded last for [path] at this size, while the cache still holds it. */
    fun cachedPicture(path: String, maxWidth: Int, maxHeight: Int): Bitmap? = pictures.get(pictureKey(path, maxWidth, maxHeight))

    private fun pictureKey(path: String, maxWidth: Int, maxHeight: Int) = "$maxWidth×$maxHeight/$path"

    /** The reader left the screen. The process may end before [onCleared], so the position and history go out now. */
    fun onStop() {
        stopped = true
        model?.onStop()
    }

    override fun onCleared() {
        model?.close()
        pictures.evictAll()
    }

    private companion object {
        const val OPEN_SECTION = "TextOpen"
        const val PICTURE_CACHE_BYTES = 32 shl 20
    }
}

private fun decodeFitting(bytes: ByteArray, maxWidth: Int, maxHeight: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val scale = minOf(1f, maxWidth.toFloat() / info.size.width, maxHeight.toFloat() / info.size.height)
        decoder.setTargetSize(maxOf(1, (info.size.width * scale).toInt()), maxOf(1, (info.size.height * scale).toInt()))
    }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
