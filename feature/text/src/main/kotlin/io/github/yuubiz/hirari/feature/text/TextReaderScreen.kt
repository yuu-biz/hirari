package io.github.yuubiz.hirari.feature.text

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.KeepAwake
import io.github.yuubiz.hirari.core.model.KeyCodes
import io.github.yuubiz.hirari.core.model.KeyResult
import io.github.yuubiz.hirari.core.model.PageBarState
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.TextLayout
import io.github.yuubiz.hirari.core.text.GlyphContent
import io.github.yuubiz.hirari.core.text.PageContent
import io.github.yuubiz.hirari.core.text.TextPage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay

/**
 * Hands key events to the open text reader. The activity calls [dispatch] from `dispatchKeyEvent` before Compose
 * focus handling, which would take the arrow keys first, and passes on a key the reader does not take.
 */
class TextKeyRelay {
    internal var handler: ((KeyEvent) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}

/**
 * The text reader (docs/spec.md section 4.9) for the text [ref] names. It builds its [TextReaderViewModel] from
 * [services] and keeps it per [ref]. [onBack] leaves it; the position is written out by then. [onOpenSettings]
 * opens the settings screen from the 設定 command. [keys] must be dispatched by the activity.
 */
@Composable
fun TextReaderScreen(
    services: Deferred<TextReaderServices>,
    ref: TextBookRef,
    keys: TextKeyRelay,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel = viewModel(key = ref.toString()) { TextReaderViewModel(application, services, ref) }
    TextReaderScreen(viewModel, keys, onBack, onOpenSettings)
}

/** The panels over the reader. At most one is up. */
internal enum class TextPanel { Commands, Contents, Search, Bookmarks, Size, PageJump }

/** The text reader for a [viewModel] the caller keeps. */
@Composable
fun TextReaderScreen(viewModel: TextReaderViewModel, keys: TextKeyRelay, onBack: () -> Unit, onOpenSettings: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    var panel by rememberSaveable { mutableStateOf<TextPanel?>(null) }
    var orientation by rememberSaveable { mutableStateOf<ReaderOrientation?>(null) }
    var touched by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    val settings = (state as? TextReaderUiState.Reading)?.settings
    if (settings?.hideSystemBars != false) HideSystemBars()
    OrientationLock(orientation ?: settings?.readerOrientation ?: ReaderOrientation.System)
    KeepAwake(settings?.keepAwake ?: KeepAwake.System, touched)

    fun dismiss() = if (panel != null) panel = null else back()

    fun act(action: ReaderAction) {
        touched++
        when (action) {
            is ReaderAction.TurnView, is ReaderAction.StepStops -> action.views()?.let(viewModel::turn)
            is ReaderAction.Shift -> Unit
            ReaderAction.First -> viewModel.first()
            ReaderAction.Last -> viewModel.last()
            ReaderAction.OpenMenu -> panel = if (panel == TextPanel.Commands) null else TextPanel.Commands
            ReaderAction.Dismiss -> dismiss()
        }
    }

    BackHandler { dismiss() }
    DisposableEffect(keys) {
        keys.handler = handler@{ event ->
            val current = (viewModel.state.value as? TextReaderUiState.Reading)?.settings ?: return@handler false
            if (panel != null && panel != TextPanel.Commands && event.keyCode != KeyCodes.BACK) return@handler false
            when (val outcome = current.textKeys().onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.isShiftPressed)) {
                KeyResult.NotHandled -> false
                KeyResult.Consumed -> true
                is KeyResult.Act -> {
                    act(outcome.action)
                    true
                }
            }
        }
        onDispose { keys.handler = null }
    }

    when (val s = state) {
        TextReaderUiState.Opening -> Box(Modifier.fillMaxSize().background(DEFAULT_BACKGROUND), contentAlignment = Alignment.Center) { DelayedProgress() }
        is TextReaderUiState.Failed -> Failure(s.reason, onBack)
        is TextReaderUiState.Reading -> {
            val density = LocalDensity.current.density
            val bounds = remember(density) { TextPanelBounds(density) }
            val touch = remember { TextTouch() }
            val run = rememberCommandRunner(viewModel, onPanel = { panel = it }, onOpenSettings) {
                orientation = when (orientation ?: s.settings.readerOrientation) {
                    ReaderOrientation.System -> ReaderOrientation.Portrait
                    ReaderOrientation.Portrait -> ReaderOrientation.Landscape
                    ReaderOrientation.Landscape -> ReaderOrientation.System
                }
            }
            Box(Modifier.fillMaxSize()) {
                Reading(
                    s, viewModel, touch, bounds,
                    onRegionTouch = { touched++ },
                    onPanel = { panel = it },
                    onItem = { item ->
                        when (item) {
                            TextPanelItem.Close -> back()
                            is TextPanelItem.Command -> run(item.command)
                        }
                    },
                    act = ::act,
                )
                TextPanels(panel, s, viewModel, bounds, touch.highlight, onPanel = { panel = it }, onClose = back, onCommand = run)
            }
        }
    }
}

@Composable
private fun Reading(
    state: TextReaderUiState.Reading,
    viewModel: TextReaderViewModel,
    touch: TextTouch,
    panelBounds: TextPanelBounds,
    onRegionTouch: () -> Unit,
    onPanel: (TextPanel?) -> Unit,
    onItem: (TextPanelItem) -> Unit,
    act: (ReaderAction) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(state)
    val input = remember(viewModel, panelBounds) {
        TextInput({ current }, viewModel, touch, onRegionTouch, onPanel, panelBounds, onItem, { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, act)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(readerBackground(state.settings.readerBackground))
            .onSizeChanged { viewModel.onViewport(Viewport(it.width, it.height, density.density, density.fontScale)) }
            .pointerInput(input) { with(input) { handle() } },
    ) {
        val frame = state.frame
        val run = state.run
        if (frame != null && run != null) {
            if (frame.continuous) ContinuousPages(state, viewModel) else ShownPages(state, viewModel)
        } else {
            Box(Modifier.fillMaxSize().background(Color(argb(state.colors.paper))), contentAlignment = Alignment.Center) { DelayedProgress() }
        }
        (touch.pageBar as? PageBarState.Dragging)?.let { PageBarPreview(state, it) }
    }
}

/** The page a slide on the page bar goes to, grayed out while the slide is cancelled. */
@Composable
private fun PageBarPreview(state: TextReaderUiState.Reading, drag: PageBarState.Dragging) {
    val run = state.run ?: return
    val target = drag.preview.coerceIn(0, run.lastIndex)
    Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.TopCenter) {
        Text(
            pageNumberText(run.number(target)),
            color = if (drag.cancelled) Color.Gray else Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.alpha(if (drag.cancelled) 0.4f else 1f).background(PANEL_BACKGROUND)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** The shown pages of a single or spread layout, the first on the right; a page still being laid out is bare paper. */
@Composable
private fun ShownPages(state: TextReaderUiState.Reading, viewModel: TextReaderViewModel) {
    val frame = state.frame ?: return
    val shown = state.shown.map(state::page)
    val pictures = rememberPictures(shown.filterNotNull(), frame, viewModel)
    val painter = remember(state.typeface, state.colors) { PagePainter(viewModel.typeface(state.typeface), state.colors) }
    val decor = shown.indices.map { decorOf(state, state.current + it) }
    Canvas(Modifier.fillMaxSize()) {
        drawIntoCanvas { canvas ->
            shown.forEachIndexed { i, page ->
                val left = size.width - (frame.pageWidth + frame.gutter) * i - frame.pageWidth
                painter.draw(canvas.nativeCanvas, page, frame, left, 0f, decor[i]) { pictures[it] }
            }
        }
    }
}

/** The 連続 layout: every laid-out page in one row, right to left, scrolled by the finger; pages far from the view are bare paper. */
@Composable
private fun ContinuousPages(state: TextReaderUiState.Reading, viewModel: TextReaderViewModel) {
    val frame = state.frame ?: return
    val run = state.run ?: return
    val list = rememberLazyListState(state.current)
    val painter = remember(state.typeface, state.colors) { PagePainter(viewModel.typeface(state.typeface), state.colors) }
    val widthDp = with(LocalDensity.current) { frame.pageWidth.toDp() }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(run, state.current) {
        if (list.firstVisibleItemIndex != state.current) list.scrollToItem(state.current)
    }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex }.collect { if (it != latest.current) viewModel.showPage(it) }
    }
    val gutter = with(LocalDensity.current) { frame.gutter.toDp() }
    LazyRow(Modifier.fillMaxSize(), state = list, reverseLayout = true, horizontalArrangement = Arrangement.spacedBy(gutter)) {
        items(run.size) { index ->
            val page = state.page(index)
            val pictures = rememberPictures(listOfNotNull(page), frame, viewModel)
            val decor = decorOf(state, index)
            Canvas(Modifier.size(widthDp, with(LocalDensity.current) { frame.pageHeight.toDp() })) {
                drawIntoCanvas { painter.draw(it.nativeCanvas, page, frame, 0f, 0f, decor) { path -> pictures[path] } }
            }
        }
    }
}

@Composable
private fun decorOf(state: TextReaderUiState.Reading, index: Int): PageDecor {
    val run = state.run
    val number = run?.number(index)?.let { pageNumberText(it) }.orEmpty()
    val highlight = state.highlight?.takeIf { run != null && index in 0 until run.size && it.first < run.end(index) && it.last >= run.start(index) }
    return PageDecor(state.title, number, highlight)
}

@Composable
internal fun pageNumberText(number: PageNumber): String {
    val current = if (number.currentExact) number.current.toString() else stringResource(R.string.text_about, number.current)
    val total = if (number.totalExact) number.total.toString() else stringResource(R.string.text_about, number.total)
    return stringResource(R.string.text_page_number, current, total)
}

/**
 * The decoded 挿絵 of [pages] at the size of [frame], loading in the background; a picture draws once it is ready.
 * Only the pictures of these pages are held here, so the view model's bounded cache keeps the rest.
 */
@Composable
private fun rememberPictures(pages: List<TextPage>, frame: PageFrame, viewModel: TextReaderViewModel): Map<String, Bitmap> {
    val paths = pages.flatMap { page ->
        when (val content = page.content) {
            is PageContent.Picture -> listOf(content.path)
            is PageContent.Lines -> content.lines.flatMap { line ->
                line.glyphs.mapNotNull { (it.content as? GlyphContent.Image)?.path }
            }
        }
    }.distinct()
    val width = frame.params.width.toInt()
    val height = frame.params.height.toInt()
    val shown = remember(paths, width, height) {
        mutableStateMapOf<String, Bitmap>().apply {
            for (path in paths) viewModel.cachedPicture(path, width, height)?.let { put(path, it) }
        }
    }
    LaunchedEffect(shown) {
        for (path in paths) {
            if (path !in shown) viewModel.picture(path, width, height)?.let { shown[path] = it }
        }
    }
    return shown
}

@Composable
private fun Failure(reason: TextFailure, onBack: () -> Unit) {
    val text = when (reason) {
        TextFailure.NotFound -> R.string.text_failed_not_found
        TextFailure.NoPermission -> R.string.text_failed_no_permission
        TextFailure.NoRoomForCopy -> R.string.text_failed_no_room
        TextFailure.Encrypted -> R.string.text_failed_encrypted
        TextFailure.TooLarge -> R.string.text_failed_too_large
        TextFailure.ReadError -> R.string.text_failed_read
    }
    Column(
        Modifier.fillMaxSize().background(DEFAULT_BACKGROUND).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(text), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.text_back)) }
    }
}

/** A progress indicator that shows only once loading has taken [PROGRESS_DELAY_MS] (docs/spec.md section 4.8). */
@Composable
private fun DelayedProgress() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROGRESS_DELAY_MS)
        shown = true
    }
    if (shown) CircularProgressIndicator()
}

private const val PROGRESS_DELAY_MS = 300L

/** The reader background of docs/spec.md section 4.10, around the pages and between them. */
@Composable
private fun readerBackground(background: ReaderBackground): Color = when (background) {
    ReaderBackground.Black -> Color.Black
    ReaderBackground.DarkGray -> DEFAULT_BACKGROUND
    ReaderBackground.White -> Color.White
    ReaderBackground.Theme -> MaterialTheme.colorScheme.background
    is ReaderBackground.Custom -> Color(argb(background.color))
}

private val DEFAULT_BACKGROUND = Color(0xFF616161)
internal val PANEL_BACKGROUND = Color(0x80000070)

@Composable
private fun HideSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun OrientationLock(orientation: ReaderOrientation) {
    val activity = LocalView.current.context.findActivity()
    DisposableEffect(activity, orientation) {
        activity?.requestedOrientation = when (orientation) {
            ReaderOrientation.System -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReaderOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReaderOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { }
    }
    DisposableEffect(activity) {
        onDispose { if (activity?.isChangingConfigurations == false) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
}

/** 画面を消さない of docs/spec.md section 4.10. [touched] changes on every input, which restarts the ten minutes. */
@Composable
private fun KeepAwake(mode: KeepAwake, touched: Int) {
    val view = LocalView.current
    LaunchedEffect(mode, touched) {
        when (mode) {
            KeepAwake.System -> view.keepScreenOn = false
            KeepAwake.Always -> view.keepScreenOn = true
            KeepAwake.TenMinutes -> {
                view.keepScreenOn = true
                delay(10 * 60 * 1000L)
                view.keepScreenOn = false
            }
        }
    }
    DisposableEffect(view) { onDispose { view.keepScreenOn = false } }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 組み方 of the command bar: the next of 1 ページ, 見開き, 連続 and 自動. */
internal fun GlobalSettings.nextLayout() = copy(textLayout = TextLayout.entries[(textLayout.ordinal + 1) % TextLayout.entries.size])
