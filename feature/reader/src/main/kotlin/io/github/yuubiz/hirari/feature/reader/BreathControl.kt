package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.ui.rememberBreathPermission
import kotlinx.coroutines.launch

/**
 * 息での操作 on the reader (docs/spec.md section 4.11): the microphone runs only while the reader is resumed, the
 * command turns it on or off, a long sound scrolls frame by frame, and the corner shows what it hears.
 */
@Composable
internal fun BreathControl(viewModel: ReaderViewModel, ui: ReaderUi, modifier: Modifier = Modifier) {
    LifecycleResumeEffect(viewModel) {
        viewModel.onForeground(true)
        onPauseOrDispose { viewModel.onForeground(false) }
    }
    val panelUp = ui.sheet != null || ui.guideShown || ui.commandBar.panelVisible
    SideEffect { viewModel.breathHeld = panelUp }

    val scope = rememberCoroutineScope()
    fun save(on: Boolean) {
        scope.launch { if (!viewModel.saveBreath(on)) ui.message = R.string.breath_save_failed }
    }
    val permission = rememberBreathPermission { save(true) }
    LaunchedEffect(ui.breathToggled) {
        if (!ui.breathToggled) return@LaunchedEffect
        ui.breathToggled = false
        when (breathToggle(viewModel.breath.input)) {
            BreathToggle.TurnOff -> save(false)
            BreathToggle.TurnOn -> save(true)
            BreathToggle.AskPermission -> permission.launch()
        }
    }

    val view = viewModel.breath.view ?: return
    val scrolling = (view as? BreathView.Listening)?.detector?.scrolling
    LaunchedEffect(scrolling) {
        val turn = scrolling ?: return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            viewModel.breathScrolled(turn, (now - last) / 1_000_000f)
            last = now
        }
    }
    BreathBadge(view, modifier)
}

/** The mic, what the judgment is doing, and how loud the input is: small, at the top right. */
@Composable
private fun BreathBadge(view: BreathView, modifier: Modifier) {
    val status = stringResource(view.status)
    val description = stringResource(R.string.breath_badge, status)
    Row(
        modifier
            .padding(8.dp)
            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MicMark(if (view is BreathView.Listening) Color.White else Color(0xFFFF8A80))
        Column {
            Text(status, color = Color.White, fontSize = 12.sp)
            if (view is BreathView.Listening) LevelBar(view.detector)
        }
    }
}

@Composable
private fun LevelBar(detector: BreathDetector) {
    val full = detector.thresholds?.sound ?: Short.MAX_VALUE.toInt()
    val fraction = (detector.level.toFloat() / full).coerceIn(0f, 1f)
    val color = if (detector.phase is BreathPhase.Blowing || detector.phase is BreathPhase.Scrolling) Color(0xFF80CBC4) else Color.White
    Box(Modifier.padding(top = 2.dp).width(48.dp).height(4.dp).background(Color(0x55FFFFFF), RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(color, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun MicMark(color: Color) {
    Canvas(Modifier.size(14.dp, 18.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.5.dp.toPx()
        drawRoundRect(color, Offset(w * 0.3f, 0f), Size(w * 0.4f, h * 0.6f), CornerRadius(w * 0.2f))
        drawArc(color, 0f, 180f, useCenter = false, topLeft = Offset(w * 0.1f, h * 0.2f), size = Size(w * 0.8f, h * 0.55f), style = Stroke(stroke))
        drawLine(color, Offset(w / 2, h * 0.75f), Offset(w / 2, h * 0.95f), stroke)
        drawLine(color, Offset(w * 0.3f, h * 0.95f), Offset(w * 0.7f, h * 0.95f), stroke)
    }
}

private val BreathView.status: Int
    get() = when (this) {
        BreathView.NoPermission -> R.string.breath_no_permission
        BreathView.Unavailable -> R.string.breath_unavailable
        is BreathView.Listening -> when (val phase = detector.phase) {
            is BreathPhase.Calibrating -> R.string.breath_calibrating
            BreathPhase.Quiet -> R.string.breath_quiet
            is BreathPhase.Blowing -> R.string.breath_sound
            is BreathPhase.Settling -> R.string.breath_settling
            is BreathPhase.Scrolling -> if (phase.turn == Turn.Forward) R.string.breath_scrolling_forward else R.string.breath_scrolling_back
        }
    }
