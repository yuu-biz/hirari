package io.github.yuubiz.hirari.feature.reader

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.BookEndEffect
import io.github.yuubiz.hirari.core.model.BookId
import io.github.yuubiz.hirari.core.model.Claims
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.KeepAwake
import io.github.yuubiz.hirari.core.model.KeyResult
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.PageBarState
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.Sibling
import io.github.yuubiz.hirari.core.ui.HoldReaderWindow
import io.github.yuubiz.hirari.core.ui.ReaderBackdrop
import io.github.yuubiz.hirari.core.ui.ReaderWindow
import io.github.yuubiz.hirari.core.ui.announced
import io.github.yuubiz.hirari.core.ui.isLight
import io.github.yuubiz.hirari.core.ui.keepsAwake
import io.github.yuubiz.hirari.core.ui.readableOn
import io.github.yuubiz.hirari.core.ui.readerBackgroundColor
import io.github.yuubiz.hirari.core.ui.readerInsets
import kotlinx.coroutines.delay

/**
 * Hands the activity's key events to the reader while it is on screen (docs/spec.md section 4.8, キー操作), the newest
 * one when a reader opening a book in place of another is up with it.
 */
class KeyRelay {
    internal val handlers = Claims<(KeyEvent) -> Boolean>()

    /** Whether the reader took [event]. A key it does not take, such as a volume key that is off, goes to the system. */
    fun dispatch(event: KeyEvent): Boolean = handlers.current?.invoke(event) == true
}

/**
 * The image reader. [onClose] leaves it; the position is already saved by then. [onOpenBook] leaves it for
 * another book, the neighbor of 本の端での動作. [onCoverRange] leaves it for the screen that frames a range of the page
 * with the `entryIndex` as the cover of the book (docs/spec.md section 4.4). [onOpenSettings] opens the settings
 * over the reader; coming back, the reader follows what changed there.
 */
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    keys: KeyRelay,
    onClose: () -> Unit,
    onOpenBook: (OpenBookRequest) -> Unit,
    onCoverRange: (BookId, Int) -> Unit,
    onOpenSettings: (SettingsTarget) -> Unit,
) {
    val density = LocalDensity.current.density
    val ui = remember { ReaderUi() }
    val stoppingKey = remember { StoppingKey() }
    val close by rememberUpdatedState(onClose)
    ReaderWindowHold(viewModel, ui)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }

    fun dismiss() {
        val panelOpen = ui.commandBar.panelVisible || ui.sheet != null
        val autoPlaying = viewModel.state?.autoPlay != null
        when (dismissTarget(autoPlaying, guideShown = ui.guideShown, panelOpen = panelOpen, opening = viewModel.phase == ReaderPhase.Opening)) {
            DismissTarget.AutoPlay -> viewModel.dispatch(ReaderEvent.AutoPlayStopped)
            DismissTarget.Guide -> ui.guideShown = false
            DismissTarget.Panel -> ui.closePanels()
            DismissTarget.Opening, DismissTarget.Reader -> close()
        }
    }

    DisposableEffect(keys, viewModel) {
        val claim = keys.handlers.claim handler@{ event ->
            val pressed = event.action == KeyEvent.ACTION_DOWN
            if (pressed) ui.touched++
            when (stoppingKey.onKey(event.keyCode, pressed, event.repeatCount > 0, viewModel.state?.autoPlay != null)) {
                KeyGate.StopAutoPlay -> {
                    viewModel.dispatch(ReaderEvent.AutoPlayStopped)
                    return@handler true
                }
                KeyGate.Swallow -> return@handler true
                KeyGate.Pass -> Unit
            }
            val config = viewModel.state?.config ?: ReaderConfig()
            when (val result = config.keys.onKey(event.keyCode, pressed, event.isShiftPressed, ui.sheet)) {
                KeyResult.NotHandled -> false
                KeyResult.Consumed -> true
                is KeyResult.Act -> {
                    when (val action = result.action) {
                        ReaderAction.Dismiss -> dismiss()
                        ReaderAction.OpenMenu -> ui.toggleMenu()
                        else -> viewModel.act(action, SystemClock.uptimeMillis())
                    }
                    true
                }
            }
        }
        onDispose { claim.release() }
    }
    BackHandler { dismiss() }

    ReaderBackdrop(
        viewModel.background,
        insets = readerInsets(viewModel.hideSystemBars),
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewModel.onViewport(Viewport(it.width.toFloat(), it.height.toFloat(), density)) }
            .pointerInput(ui) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).changes.any { it.changedToDownIgnoreConsumed() }) ui.touched++
                    }
                }
            },
    ) {
        when (val phase = viewModel.phase) {
            ReaderPhase.Opening -> DelayedProgress(Modifier.align(Alignment.Center))
            is ReaderPhase.Failed -> BookError(stringResource(phase.reason.message), viewModel.background, onClose)
            ReaderPhase.NoPages -> BookError(stringResource(R.string.reader_no_pages), viewModel.background, onClose)
            ReaderPhase.Reading -> viewModel.state?.let { state ->
                AutoPlayClock(viewModel, state.autoPlay)
                // Behind a sheet or the guide the page is out of reach of a screen reader, as it is of a finger.
                val behindPanel = ui.sheet != null || ui.guideShown
                Box(Modifier.fillMaxSize().then(if (behindPanel) Modifier.clearAndSetSemantics {} else Modifier.semantics { isTraversalGroup = true })) {
                    ReaderSurface(viewModel, state, ui, onClose)
                }
                BreathControl(viewModel, ui, Modifier.align(Alignment.TopEnd))
                when (val sheet = ui.sheet) {
                    is ReaderSheet.Pages -> PagePanelSheet(viewModel, state, sheet, onClose = { ui.sheet = null })
                    is ReaderSheet.BookEnd -> BookEndSheet(viewModel, sheet, onClose = { ui.sheet = null }, onCloseReader = onClose, onOpenBook = onOpenBook)
                    ReaderSheet.Menu -> MenuSheet(
                        viewModel,
                        state,
                        onCloseMenu = { ui.sheet = null },
                        onCloseReader = onClose,
                        onEffect = ui::follow,
                        onGuide = { ui.guideShown = true },
                        onCoverRange = { chooseCoverRange(state, viewModel, ui, onCoverRange) },
                        onNeighbor = { ui.sheet = ReaderSheet.BookEnd(it, fromMenu = true) },
                    )
                    ReaderSheet.Adjust -> AdjustSheet(viewModel, state, onClose = { ui.sheet = null })
                    is ReaderSheet.SharePick -> SharePickSheet(sheet.view, state.config.direction, R.string.share_which_page, onPick = { ui.follow(CommandEffect.Share(it)) }, onClose = { ui.sheet = null })
                    is ReaderSheet.CoverPick -> SharePickSheet(
                        sheet.view,
                        state.config.direction,
                        if (sheet.range) R.string.cover_range_which_page else R.string.cover_which_page,
                        onPick = { page ->
                            ui.sheet = null
                            if (sheet.range) openCoverRange(page, viewModel, ui, onCoverRange) else ui.follow(CommandEffect.Cover(page))
                        },
                        onClose = { ui.sheet = null },
                    )
                    is ReaderSheet.AddBookmark -> AddBookmarkSheet(
                        sheet.page,
                        state.pagination.shapes.size,
                        onAdd = { label ->
                            ui.sheet = null
                            viewModel.addBookmark(sheet.page, label) { added -> ui.message = if (added) R.string.bookmark_added else R.string.bookmark_add_failed }
                        },
                        onClose = { ui.sheet = null },
                    )
                    ReaderSheet.Bookmarks -> BookmarksSheet(
                        viewModel,
                        onAdd = { ui.sheet = ReaderSheet.AddBookmark(state.current.pages.first()) },
                        onClose = { ui.sheet = null },
                    )
                    is ReaderSheet.BookSettings -> BookSettingsSheet(viewModel, state, sheet, onMessage = { ui.message = it }, onEffect = ui::follow, onClose = { ui.sheet = null })
                    null -> Unit
                }
                if (viewModel.allPagesFailed) BookError(stringResource(R.string.reader_all_pages_failed), viewModel.background, onClose)
                BookEndEffects(viewModel, ui, onClose, onOpenBook)
                GuideEffects(viewModel, ui)
                if (ui.guideShown) GuideOverlay(state.config, onDismiss = { ui.guideShown = false })
            }
        }
        SharePageEffect(viewModel, ui)
        CoverEffect(viewModel, ui)
        ui.settings?.let { target ->
            LaunchedEffect(target) {
                ui.settings = null
                onOpenSettings(target)
            }
        }
        viewModel.reordered?.let { request ->
            LaunchedEffect(request) { onOpenBook(request) }
        }
        ui.reopening?.let { request ->
            LaunchedEffect(request) {
                ui.reopening = null
                onOpenBook(request)
            }
        }
        ui.message?.let { message ->
            LaunchedEffect(message) {
                delay(MESSAGE_MS)
                ui.message = null
            }
            Text(
                stringResource(message),
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .announced()
                    .background(Color(0xCC000000), MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private const val MESSAGE_MS = 1500L

/**
 * What the reader asks of the window (docs/spec.md section 4.10). The screen stays on while 自動再生 runs or the
 * microphone listens (section 4.11), and otherwise as 画面を消さない says. Only this reads [ReaderUi.touched], so a
 * touch recomposes it alone.
 */
@Composable
private fun ReaderWindowHold(viewModel: ReaderViewModel, ui: ReaderUi) {
    val setting = keepsAwake(viewModel.state?.config?.keepAwake ?: KeepAwake.System, ui.touched)
    val awake = setting || viewModel.state?.autoPlay != null || viewModel.breath.view is BreathView.Listening
    HoldReaderWindow(
        ReaderWindow(
            hideSystemBars = viewModel.hideSystemBars,
            orientation = viewModel.orientation,
            brightness = viewModel.screenBrightness,
            keepScreenOn = awake,
            lightBackground = isLight(readerBackgroundColor(viewModel.background)),
        ),
    )
}

/** The screen state that is not the book's: the bars and the panels. */
internal class ReaderUi {
    var commandBar: CommandBarState by mutableStateOf(CommandBarState.Closed)
    var pageBar: PageBarState by mutableStateOf(PageBarState.Idle)
    var sheet: ReaderSheet? by mutableStateOf(null)
    var message: Int? by mutableStateOf(null)

    /** Changes on every touch and key press, which 画面を消さない counts its ten minutes from. */
    var touched: Int by mutableIntStateOf(0)

    /** 操作ガイド, over everything else (docs/spec.md section 4.8). */
    var guideShown: Boolean by mutableStateOf(false)

    /** The page ページを共有 is writing for the share sheet. */
    var sharing: Int? by mutableStateOf(null)

    /** 息で操作 was chosen, for [BreathControl] to run. */
    var breathToggled: Boolean by mutableStateOf(false)

    /** The page 表紙にする is making the book's cover. */
    var covering: Int? by mutableStateOf(null)

    /** The book the screen is about to open again in its new order. */
    var reopening: OpenBookRequest? by mutableStateOf(null)

    /** The settings the screen is about to open over the reader. */
    var settings: SettingsTarget? by mutableStateOf(null)

    fun closePanels() {
        commandBar = CommandBarState.Closed
        sheet = null
        guideShown = false
    }

    /** Does what a command left to the screen, such as opening its sheet. */
    fun follow(effect: CommandEffect?) {
        when (effect) {
            is CommandEffect.Show -> {
                closePanels()
                sheet = effect.sheet
            }
            is CommandEffect.Share -> {
                closePanels()
                sharing = effect.page
            }
            CommandEffect.ToggleBreath -> breathToggled = true
            is CommandEffect.Cover -> {
                closePanels()
                covering = effect.page
            }
            is CommandEffect.Reopen -> {
                closePanels()
                reopening = effect.request
            }
            is CommandEffect.OpenSettings -> {
                closePanels()
                settings = effect.target
            }
            else -> Unit
        }
    }

    /** The Menu key and the panel's メニュー: the menu opens over anything else, and the key closes it again. */
    fun toggleMenu() {
        val open = sheet == ReaderSheet.Menu
        closePanels()
        if (!open) sheet = ReaderSheet.Menu
    }
}

private val BookFailure.message: Int
    get() = when (this) {
        BookFailure.NotFound -> R.string.reader_failed_not_found
        BookFailure.NoPermission -> R.string.reader_failed_no_permission
        BookFailure.NoRoomForCopy -> R.string.reader_failed_no_room
        BookFailure.Encrypted -> R.string.reader_failed_encrypted
        BookFailure.Corrupt -> R.string.reader_failed_corrupt
        BookFailure.Truncated -> R.string.reader_failed_truncated
        BookFailure.ReadError -> R.string.reader_failed_read
    }

/** A progress indicator that shows only once loading has taken [PROGRESS_DELAY_MS]. */
@Composable
internal fun DelayedProgress(modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROGRESS_DELAY_MS)
        shown = true
    }
    val loading = stringResource(R.string.a11y_loading)
    if (shown) CircularProgressIndicator(modifier.semantics { contentDescription = loading })
}

/** docs/spec.md section 4.8, 重ねて表示する情報. */
internal const val PROGRESS_DELAY_MS = 300L

@Composable
private fun BookError(text: String, background: ReaderBackground, onBack: () -> Unit) {
    val color = readerBackgroundColor(background)
    Column(
        Modifier.fillMaxSize().background(color).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = readableOn(color), style = MaterialTheme.typography.titleMedium)
        Button(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.reader_back)) }
    }
}

/** Does what running into an edge of the book asks of the screen (docs/spec.md section 4.8, 本の端での動作). */
@Composable
private fun BookEndEffects(viewModel: ReaderViewModel, ui: ReaderUi, onClose: () -> Unit, onOpenBook: (OpenBookRequest) -> Unit) {
    val effect = viewModel.bookEnd ?: return
    LaunchedEffect(effect) {
        viewModel.bookEndHandled()
        when (effect) {
            is BookEndEffect.Ask -> {
                ui.closePanels()
                ui.sheet = ReaderSheet.BookEnd(effect.edge)
            }
            is BookEndEffect.Open -> onOpenBook(effect.opening.request())
            is BookEndEffect.Missing -> ui.message = when {
                effect.sibling == Sibling.NoFolder -> R.string.book_end_no_folder
                effect.edge == BookEdge.End -> R.string.book_end_last_in_folder
                else -> R.string.book_end_first_in_folder
            }
            BookEndEffect.Close -> onClose()
        }
    }
}

/**
 * Shows the operation guide the first time a book is read, and remembers it once it is closed, by whatever closes it
 * (docs/spec.md section 4.8, 操作ガイド). A guide that was up when the app went away shows again.
 */
@Composable
private fun GuideEffects(viewModel: ReaderViewModel, ui: ReaderUi) {
    LaunchedEffect(viewModel) {
        if (!viewModel.guideSeen()) ui.guideShown = true
    }
    var wasShown by remember { mutableStateOf(false) }
    LaunchedEffect(ui.guideShown) {
        if (ui.guideShown) {
            wasShown = true
        } else if (wasShown) {
            wasShown = false
            viewModel.markGuideSeen()
        }
    }
}

/** 範囲を選んで表紙にする from the menu (docs/spec.md section 4.8): the page of the view, or for a pair the one the user picks. */
private fun chooseCoverRange(state: ReaderState, viewModel: ReaderViewModel, ui: ReaderUi, open: (BookId, Int) -> Unit) {
    when (val view = state.current) {
        is PageView.Two -> ui.sheet = ReaderSheet.CoverPick(view, range = true)
        else -> openCoverRange(view.pages.min(), viewModel, ui, open)
    }
}

private fun openCoverRange(page: Int, viewModel: ReaderViewModel, ui: ReaderUi, open: (BookId, Int) -> Unit) {
    val target = viewModel.coverRangeTarget(page)
    if (target == null) ui.message = R.string.cover_range_unavailable else open(target.book, target.entry)
}

/** Makes the page of [ReaderUi.covering] the book's cover, and says whether it did (docs/spec.md section 4.8, 表紙にする). */
@Composable
private fun CoverEffect(viewModel: ReaderViewModel, ui: ReaderUi) {
    val page = ui.covering ?: return
    LaunchedEffect(page) {
        val chosen = viewModel.setCover(page)
        ui.covering = null
        ui.message = if (chosen) R.string.cover_set else R.string.cover_set_failed
    }
}

/** Writes the page of [ReaderUi.sharing] and hands it to the system's share sheet (docs/spec.md section 4.8). */
@Composable
private fun SharePageEffect(viewModel: ReaderViewModel, ui: ReaderUi) {
    val context = LocalContext.current
    val page = ui.sharing ?: return
    LaunchedEffect(page) {
        val shared = viewModel.sharePage(page)
        ui.sharing = null
        if (shared == null) {
            ui.message = R.string.share_failed
            return@LaunchedEffect
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType(shared.mimeType)
            .putExtra(Intent.EXTRA_STREAM, shared.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, shared.uri)
        try {
            context.startActivity(Intent.createChooser(send, null))
        } catch (_: ActivityNotFoundException) {
            ui.message = R.string.share_failed
        }
    }
}

/** Sends 自動再生 its steps when they are due. */
@Composable
private fun AutoPlayClock(viewModel: ReaderViewModel, autoPlay: AutoPlay?) {
    LaunchedEffect(autoPlay) {
        val due = autoPlay?.dueAtMs ?: return@LaunchedEffect
        delay((due - SystemClock.uptimeMillis()).coerceAtLeast(0))
        viewModel.dispatch(ReaderEvent.AutoPlayDue(maxOf(SystemClock.uptimeMillis(), due)))
    }
}
