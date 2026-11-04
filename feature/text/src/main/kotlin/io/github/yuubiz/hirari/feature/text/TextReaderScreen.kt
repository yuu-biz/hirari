package io.github.yuubiz.hirari.feature.text

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.widget.Toast
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndEffect
import io.github.yuubiz.hirari.core.model.Claims
import io.github.yuubiz.hirari.core.model.GutterStyle
import io.github.yuubiz.hirari.core.model.KeepAwake
import io.github.yuubiz.hirari.core.model.KeyCodes
import io.github.yuubiz.hirari.core.model.KeyResult
import io.github.yuubiz.hirari.core.model.NeighborOpening
import io.github.yuubiz.hirari.core.model.NeighborStart
import io.github.yuubiz.hirari.core.model.PageBarState
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import io.github.yuubiz.hirari.core.model.Sibling
import io.github.yuubiz.hirari.core.model.TextLayout
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.text.GlyphContent
import io.github.yuubiz.hirari.core.text.PageContent
import io.github.yuubiz.hirari.core.text.TextPage
import io.github.yuubiz.hirari.core.ui.BarSemantics
import io.github.yuubiz.hirari.core.ui.SEAM_SHADOW_SHARE
import io.github.yuubiz.hirari.core.ui.DefaultReaderBackground
import io.github.yuubiz.hirari.core.ui.HoldReaderWindow
import io.github.yuubiz.hirari.core.ui.readerBackgroundColor
import io.github.yuubiz.hirari.core.ui.ReaderWindow
import io.github.yuubiz.hirari.core.ui.drawSeamShadow
import io.github.yuubiz.hirari.core.ui.performTurnHaptic
import kotlin.math.roundToInt
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay

/**
 * Hands key events to the open text reader. The activity calls [dispatch] from `dispatchKeyEvent` before Compose
 * focus handling, which would take the arrow keys first, and passes on a key the reader does not take. The newest
 * reader takes them while the one it replaces is still going out.
 */
class TextKeyRelay {
    internal val handlers = Claims<(KeyEvent) -> Boolean>()

    fun dispatch(event: KeyEvent): Boolean = handlers.current?.invoke(event) == true
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
    startAt: NeighborStart = NeighborStart.Saved,
    onOpenText: (NeighborOpening<TextBookRef.InLocation>) -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as Application
    val viewModel = viewModel(key = "$ref/$startAt") { TextReaderViewModel(application, services, ref, startAt) }
    TextReaderScreen(viewModel, keys, onBack, onOpenSettings, onOpenText)
}

/** The panels over the reader. At most one is up. */
internal enum class TextPanel { Commands, Contents, Search, Bookmarks, Size, PageJump }

/**
 * The text reader for a [viewModel] the caller keeps. [onOpenText] opens the text beside this one, which replaces
 * the reader rather than stacking on it (docs/spec.md section 4.8, 本の端での動作).
 */
@Composable
fun TextReaderScreen(
    viewModel: TextReaderViewModel,
    keys: TextKeyRelay,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit = {},
    onOpenText: (NeighborOpening<TextBookRef.InLocation>) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val back by rememberUpdatedState(onBack)
    var panel by rememberSaveable { mutableStateOf<TextPanel?>(null) }
    var bookEndEdge by rememberSaveable { mutableStateOf<BookEdge?>(null) }
    var orientation by rememberSaveable { mutableStateOf<ReaderOrientation?>(null) }
    var touched by remember { mutableIntStateOf(0) }
    val touch = remember { TextTouch() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    val view = LocalView.current
    LaunchedEffect(viewModel, view) {
        for (tick in viewModel.turnHaptics) view.performTurnHaptic()
    }
    val settings = (state as? TextReaderUiState.Reading)?.settings
    HoldReaderWindow(
        ReaderWindow(
            hideSystemBars = settings?.hideSystemBars != false,
            orientation = orientation ?: settings?.readerOrientation ?: ReaderOrientation.System,
            brightness = settings?.readerBrightness,
            keepScreenOn = keepsAwake(settings?.keepAwake ?: KeepAwake.System, touched),
        ),
    )

    fun dismiss() = if (bookEndEdge != null) bookEndEdge = null else if (panel != null) panel = null else back()

    fun turn(turn: Turn, pulled: Float?) {
        val reading = viewModel.state.value as? TextReaderUiState.Reading ?: return
        val now = SystemClock.uptimeMillis()
        val result = viewModel.turn(turn.views)
        touch.motion = motionAfter(result, turn, pulled, reading.settings, reading.frame?.continuous == true, now)
    }

    fun act(action: ReaderAction) {
        touched++
        val on = TextActions(
            turn = { turn(it, null) },
            first = viewModel::first,
            last = viewModel::last,
            toggleMenu = { panel = if (panel == TextPanel.Commands) null else TextPanel.Commands },
            dismiss = ::dismiss,
        )
        touch.perform(action, on)
    }

    BackHandler { dismiss() }
    DisposableEffect(keys) {
        val claim = keys.handlers.claim handler@{ event ->
            val current = (viewModel.state.value as? TextReaderUiState.Reading)?.settings ?: return@handler false
            val covered = bookEndEdge != null || (panel != null && panel != TextPanel.Commands)
            if (covered && event.keyCode != KeyCodes.BACK) return@handler false
            when (val outcome = current.textKeys().onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.isShiftPressed)) {
                KeyResult.NotHandled -> false
                KeyResult.Consumed -> true
                is KeyResult.Act -> {
                    act(outcome.action)
                    true
                }
            }
        }
        onDispose { claim.release() }
    }

    when (val s = state) {
        TextReaderUiState.Opening -> Box(Modifier.fillMaxSize().background(DefaultReaderBackground), contentAlignment = Alignment.Center) { DelayedProgress() }
        is TextReaderUiState.Failed -> Failure(s.reason, onBack)
        is TextReaderUiState.Reading -> {
            val density = LocalDensity.current.density
            val bounds = remember(density) { TextPanelBounds(density) }
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
                    turn = ::turn,
                    accessible = panel == null,
                    onControl = { control ->
                        when (control) {
                            TextControl.NextPage -> act(ReaderAction.TurnView(Turn.Forward))
                            TextControl.PreviousPage -> act(ReaderAction.TurnView(Turn.Back))
                            TextControl.FirstPage -> act(ReaderAction.First)
                            TextControl.LastPage -> act(ReaderAction.Last)
                            TextControl.OpenPages -> panel = TextPanel.PageJump
                            TextControl.OpenCommands -> panel = TextPanel.Commands
                        }
                    },
                )
                TextPanels(panel, s, viewModel, bounds, touch.highlight, onPanel = { panel = it }, onClose = back, onCommand = run)
                BookEndEffects(viewModel, onAsk = { edge ->
                    panel = null
                    bookEndEdge = edge
                }, onClose = back, onOpen = onOpenText)
                bookEndEdge?.let { edge ->
                    TextBookEndSheet(edge, viewModel, onDismiss = { bookEndEdge = null }, onCloseReader = back, onOpen = onOpenText)
                }
            }
        }
    }
}

/** Does what running into an end of the text asks of the screen (docs/spec.md section 4.8, 本の端での動作). */
@Composable
private fun BookEndEffects(viewModel: TextReaderViewModel, onAsk: (BookEdge) -> Unit, onClose: () -> Unit, onOpen: (NeighborOpening<TextBookRef.InLocation>) -> Unit) {
    val effect = viewModel.bookEnd ?: return
    val context = LocalContext.current
    val last = stringResource(R.string.text_book_end_last_in_folder)
    val first = stringResource(R.string.text_book_end_first_in_folder)
    val noFolder = stringResource(R.string.text_book_end_no_folder)
    LaunchedEffect(effect) {
        viewModel.bookEndHandled()
        when (effect) {
            is BookEndEffect.Ask -> onAsk(effect.edge)
            is BookEndEffect.Open -> onOpen(effect.opening)
            is BookEndEffect.Missing -> {
                val message = when {
                    effect.sibling == Sibling.NoFolder -> noFolder
                    effect.edge == BookEdge.End -> last
                    else -> first
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            BookEndEffect.Close -> onClose()
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
    turn: (Turn, Float?) -> Unit,
    accessible: Boolean,
    onControl: (TextControl) -> Unit,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(state)
    val input = remember(viewModel, panelBounds) {
        TextInput({ current }, viewModel, touch, onRegionTouch, onPanel, panelBounds, onItem, { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove) }, turn)
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(readerBackgroundColor(state.settings.readerBackground))
            .onSizeChanged { viewModel.onViewport(Viewport(it.width, it.height, density.density, density.fontScale)) }
            .pointerInput(input) { with(input) { handle() } }
            .then(if (accessible) Modifier.textAccessibility(state, onControl) else Modifier.clearAndSetSemantics {}),
    ) {
        if (accessible) {
            BarSemantics(
                state.settings.barHeightDp.toFloat(),
                commandBar = stringResource(R.string.a11y_command_bar),
                pageBar = stringResource(R.string.a11y_page_bar),
                openCommands = stringResource(R.string.a11y_open_commands),
                openPages = stringResource(R.string.a11y_open_pages),
                onCommands = { onControl(TextControl.OpenCommands) },
                onPages = { onControl(TextControl.OpenPages) },
            )
        }
        val frame = state.frame
        val run = state.run
        if (frame != null && run != null) {
            if (frame.continuous) ContinuousPages(state, viewModel, touch) else ShownPages(state, viewModel, touch)
        } else {
            Box(Modifier.fillMaxSize().background(Color(argb(state.colors.paper))), contentAlignment = Alignment.Center) { DelayedProgress() }
        }
        (touch.pageBar as? PageBarState.Dragging)?.let { PageBarPreview(state, it, touch.pageBarRun) }
    }
}

/** The page a slide on the page bar goes to, grayed out while the slide is cancelled. */
@Composable
private fun PageBarPreview(state: TextReaderUiState.Reading, drag: PageBarState.Dragging, pressed: PageRun?) {
    val run = state.run ?: return
    val target = pageInLaterRun(pressed ?: run, drag.preview, run)
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

/** The frame time that a motion in [touch] is drawn at; the motion ends when it is done, unless a finger is pulling it. */
@Composable
private fun rememberMotionClock(touch: TextTouch): State<Long> {
    val motion = touch.motion
    val frameMs = remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    LaunchedEffect(motion) {
        if (motion == null || motion is TextMotion.Pulling) return@LaunchedEffect
        while (true) {
            frameMs.longValue = withFrameMillis { it }
            if (motion.isDoneAt(frameMs.longValue)) break
        }
        if (touch.motion == motion) touch.motion = null
    }
    return frameMs
}

/**
 * The shown pages of a single or spread layout, the first on the right, with the views of a turn effect, a pull or a
 * bounce in [touch]. A page still being laid out is bare paper.
 */
@Composable
private fun ShownPages(state: TextReaderUiState.Reading, viewModel: TextReaderViewModel, touch: TextTouch) {
    val frame = state.frame ?: return
    val run = state.run ?: return
    val motion = touch.motion
    val frameMs by rememberMotionClock(touch)
    val viewWidth = frame.pageWidth * frame.pagesPerView + frame.gutter * (frame.pagesPerView - 1)
    val density = LocalDensity.current.density
    val layers = textLayers(motion, state.current, frame.pagesPerView, run, state.settings.turnEffect, viewWidth, density, frameMs)
    val indices = layers.flatMap { it.head until minOf(run.size, it.head + frame.pagesPerView) }.distinct()
    val pages = indices.associateWith(state::page)
    val pictures = rememberPictures(pages.values.filterNotNull(), frame, viewModel)
    val painter = remember(state.typeface, state.colors) { PagePainter(viewModel.typeface(state.typeface), state.colors) }
    val decor = indices.associateWith { decorOf(state, it) }
    val shadow = frame.pagesPerView == 2 && state.settings.gutterStyle == GutterStyle.Shadow
    Canvas(Modifier.fillMaxSize()) {
        for (layer in layers) {
            val count = minOf(run.size, layer.head + frame.pagesPerView) - layer.head
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val faded = layer.alpha < 1f
                if (faded) native.saveLayerAlpha(null, (layer.alpha * 255).roundToInt())
                for (i in 0 until count) {
                    val left = size.width - (frame.pageWidth + frame.gutter) * i - frame.pageWidth + layer.shift
                    val index = layer.head + i
                    painter.draw(native, pages[index], frame, left, 0f, decor.getValue(index)) { pictures[it] }
                }
                if (faded) native.restore()
            }
            if (shadow && count == 2) {
                val seam = size.width - frame.pageWidth + layer.shift
                val share = frame.pageWidth * SEAM_SHADOW_SHARE
                drawSeamShadow(seam, seam + share, 0f, frame.pageHeight, layer.alpha)
                drawSeamShadow(seam, seam - share, 0f, frame.pageHeight, layer.alpha)
            }
        }
    }
}

/** The 連続 layout: every laid-out page in one row, right to left, scrolled by the finger; pages far from the view are bare paper. */
@Composable
private fun ContinuousPages(state: TextReaderUiState.Reading, viewModel: TextReaderViewModel, touch: TextTouch) {
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
        snapshotFlow { list.firstVisibleItemIndex }.collect { if (it != latest.current) viewModel.scrolledTo(it) }
    }
    val gutter = with(LocalDensity.current) { frame.gutter.toDp() }
    val clock = rememberMotionClock(touch)
    val density = LocalDensity.current.density
    LazyRow(Modifier.fillMaxSize().graphicsLayer { translationX = bounceShift(touch.motion, density, clock.value) }, state = list, reverseLayout = true, horizontalArrangement = Arrangement.spacedBy(gutter)) {
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
        Modifier.fillMaxSize().background(DefaultReaderBackground).padding(24.dp),
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
    val loading = stringResource(R.string.a11y_loading)
    if (shown) CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
}

private const val PROGRESS_DELAY_MS = 300L

internal val PANEL_BACKGROUND = Color(0x80000070)

/** Whether 画面を消さない (docs/spec.md section 4.10) keeps the screen on. [touched] changes on every input, which restarts the ten minutes. */
@Composable
private fun keepsAwake(mode: KeepAwake, touched: Int): Boolean {
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(mode, touched) {
        awake = mode != KeepAwake.System
        if (mode == KeepAwake.TenMinutes) {
            delay(10 * 60 * 1000L)
            awake = false
        }
    }
    return awake
}

/** 組み方 of the command bar: the next of 1 ページ, 見開き, 連続 and 自動. */
internal fun GlobalSettings.nextLayout() = copy(textLayout = TextLayout.entries[(textLayout.ordinal + 1) % TextLayout.entries.size])
