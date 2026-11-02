package io.github.yuubiz.hirari.feature.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.yuubiz.hirari.core.image.BookFailure
import kotlinx.coroutines.delay

/** The default `readerBackground` (docs/spec.md section 4.10). */
internal val ReaderBackground = Color(0xFF616161)

/** Hands the activity's key events to the reader while it is on screen (docs/spec.md section 4.8, キー操作). */
class KeyRelay {
    internal var handler: ((KeyEvent) -> Boolean)? = null

    /** Whether the reader took [event]. A key it does not take, such as a volume key that is off, goes to the system. */
    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) == true
}

/** The image reader. [onClose] leaves it; the position is already saved by then. */
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, keys: KeyRelay, onClose: () -> Unit) {
    val density = LocalDensity.current.density
    val ui = remember { ReaderUi() }
    val close by rememberUpdatedState(onClose)
    HideSystemBars()
    ScreenOrientationLock(viewModel.orientation)

    fun dismiss() {
        when (dismissTarget(guideShown = false, panelOpen = ui.commandBar.panelVisible, opening = viewModel.phase == ReaderPhase.Opening)) {
            DismissTarget.Panel -> ui.commandBar = CommandBarState.Closed
            DismissTarget.Guide, DismissTarget.Opening, DismissTarget.Reader -> close()
        }
    }

    DisposableEffect(keys, viewModel) {
        keys.handler = handler@{ event ->
            val pressed = event.action == KeyEvent.ACTION_DOWN
            val config = viewModel.state?.config ?: ReaderConfig()
            when (val result = config.keys.onKey(event.keyCode, pressed, event.isShiftPressed)) {
                KeyResult.NotHandled -> false
                KeyResult.Consumed -> true
                is KeyResult.Act -> {
                    when (val action = result.action) {
                        ReaderAction.Dismiss -> dismiss()
                        ReaderAction.OpenMenu -> ui.message = R.string.reader_not_yet
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
                ReaderSurface(viewModel, state, ui, onClose)
                if (viewModel.allPagesFailed) BookError(stringResource(R.string.reader_all_pages_failed), onClose)
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
    var message: Int? by mutableStateOf(null)
}

private val BookFailure.message: Int
    get() = when (this) {
        BookFailure.NotFound -> R.string.reader_failed_not_found
        BookFailure.NoPermission -> R.string.reader_failed_no_permission
        BookFailure.NoRoomForCopy -> R.string.reader_failed_no_room
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
private fun ScreenOrientationLock(orientation: Int) {
    val activity = LocalView.current.context.findActivity()
    DisposableEffect(activity, orientation) {
        activity?.requestedOrientation = orientation
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
