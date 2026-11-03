package io.github.yuubiz.hirari.feature.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.yuubiz.hirari.core.image.BookFailure
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.CommandBarState
import io.github.yuubiz.hirari.core.model.KeyResult
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.PageBarState
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReaderOrientation
import kotlinx.coroutines.delay

/** The default `readerBackground` (docs/spec.md section 4.10). */
internal val ReaderBackground = Color(0xFF616161)

/** Hands the activity's key events to the reader while it is on screen (docs/spec.md section 4.8, キー操作). */
class KeyRelay {
    internal var handler: ((KeyEvent) -> Boolean)? = null

    /** Whether the reader took [event]. A key it does not take, such as a volume key that is off, goes to the system. */
    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}

/**
 * The image reader. [onClose] leaves it; the position is already saved by then. [onOpenBook] leaves it for
 * another book, the neighbor of 本の端での動作.
 */
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, keys: KeyRelay, onClose: () -> Unit, onOpenBook: (OpenBookRequest) -> Unit) {
    val density = LocalDensity.current.density
    val ui = remember { ReaderUi() }
    val stoppingKey = remember { StoppingKey() }
    val close by rememberUpdatedState(onClose)
    HideSystemBars()
    ScreenOrientationLock(viewModel.orientation)
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }

    fun dismiss() {
        val panelOpen = ui.commandBar.panelVisible || ui.sheet != null
        val autoPlaying = viewModel.state?.autoPlay != null
        when (dismissTarget(autoPlaying, guideShown = false, panelOpen = panelOpen, opening = viewModel.phase == ReaderPhase.Opening)) {
            DismissTarget.AutoPlay -> viewModel.dispatch(ReaderEvent.AutoPlayStopped)
            DismissTarget.Panel -> ui.closePanels()
            DismissTarget.Guide, DismissTarget.Opening, DismissTarget.Reader -> close()
        }
    }

    DisposableEffect(keys, viewModel) {
        keys.handler = handler@{ event ->
            val pressed = event.action == KeyEvent.ACTION_DOWN
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
        onDispose { keys.handler = null }
    }
    BackHandler { dismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .background(ReaderBackground)
            .onSizeChanged { viewModel.onViewport(Viewport(it.width.toFloat(), it.height.toFloat(), density)) },
    ) {
        when (val phase = viewModel.phase) {
            ReaderPhase.Opening -> DelayedProgress(Modifier.align(Alignment.Center))
            is ReaderPhase.Failed -> BookError(stringResource(phase.reason.message), onClose)
            ReaderPhase.NoPages -> BookError(stringResource(R.string.reader_no_pages), onClose)
            ReaderPhase.Reading -> viewModel.state?.let { state ->
                AutoPlayClock(viewModel, state.autoPlay)
                ReaderSurface(viewModel, state, ui, onClose)
                when (val sheet = ui.sheet) {
                    is ReaderSheet.Pages -> PagePanelSheet(
                        viewModel,
                        state,
                        sheet,
                        onClose = { ui.sheet = null },
                        onNeighbor = { ui.sheet = ReaderSheet.BookEnd(it, fromPanel = true) },
                    )
                    is ReaderSheet.BookEnd -> BookEndSheet(viewModel, sheet, onClose = { ui.sheet = null }, onCloseReader = onClose, onOpenBook = onOpenBook)
                    ReaderSheet.Menu -> MenuSheet(viewModel, state, onCloseMenu = { ui.sheet = null }, onCloseReader = onClose, onEffect = ui::follow)
                    ReaderSheet.Adjust -> AdjustSheet(viewModel, state, onClose = { ui.sheet = null })
                    is ReaderSheet.SharePick -> SharePickSheet(sheet.view, state.config.direction, onPick = { ui.follow(CommandEffect.Share(it)) }, onClose = { ui.sheet = null })
                    null -> Unit
                }
                if (viewModel.allPagesFailed) BookError(stringResource(R.string.reader_all_pages_failed), onClose)
                BookEndEffects(viewModel, ui, onClose, onOpenBook)
            }
        }
        SharePageEffect(viewModel, ui)
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
                    .background(Color(0xCC000000), MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private const val MESSAGE_MS = 1500L

/** The screen state that is not the book's: the bars and the panels. */
internal class ReaderUi {
    var commandBar: CommandBarState by mutableStateOf(CommandBarState.Closed)
    var pageBar: PageBarState by mutableStateOf(PageBarState.Idle)
    var sheet: ReaderSheet? by mutableStateOf(null)
    var message: Int? by mutableStateOf(null)

    /** The page ページを共有 is writing for the share sheet. */
    var sharing: Int? by mutableStateOf(null)

    fun closePanels() {
        commandBar = CommandBarState.Closed
        sheet = null
    }

    /** Does what a command left to the screen: opens its sheet, or says it is not there yet. */
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
            CommandEffect.NotYet -> message = R.string.reader_not_yet
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
    if (shown) CircularProgressIndicator(modifier)
}

/** docs/spec.md section 4.8, 重ねて表示する情報. */
internal const val PROGRESS_DELAY_MS = 300L

@Composable
private fun BookError(text: String, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(ReaderBackground).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
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
            is BookEndEffect.Open -> onOpenBook(effect.request)
            is BookEndEffect.Missing -> ui.message = when {
                effect.sibling == Sibling.NoFolder -> R.string.book_end_no_folder
                effect.edge == BookEdge.End -> R.string.book_end_last_in_folder
                else -> R.string.book_end_first_in_folder
            }
            BookEndEffect.Close -> onClose()
        }
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

/** Sends 自動再生 its steps when they are due, and keeps the screen on while it plays. */
@Composable
private fun AutoPlayClock(viewModel: ReaderViewModel, autoPlay: AutoPlay?) {
    val view = LocalView.current
    val playing = autoPlay != null
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }
    LaunchedEffect(autoPlay) {
        val due = autoPlay?.dueAtMs ?: return@LaunchedEffect
        delay((due - SystemClock.uptimeMillis()).coerceAtLeast(0))
        viewModel.dispatch(ReaderEvent.AutoPlayDue(maxOf(SystemClock.uptimeMillis(), due)))
    }
}

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
private fun ScreenOrientationLock(orientation: ReaderOrientation) {
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

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
