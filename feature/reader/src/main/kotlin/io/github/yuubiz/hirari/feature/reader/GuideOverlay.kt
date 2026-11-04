package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.Rect
import io.github.yuubiz.hirari.core.model.Side
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.VolumeKeys

private val GuideScrim = Color(0xB3000000)
private val ForwardTint = Color(0x664FC3F7)
private val BackTint = Color(0x66FFB74D)
private val NoActionTint = Color(0x33FFFFFF)
private val BarTint = Color(0x80000070)
private val Outline = BorderStroke(1.dp, Color(0x99FFFFFF))

/**
 * 操作ガイド (docs/spec.md section 4.8): a dimmed layer over the reader that draws the tap zones, the command bar and
 * the page bar with their names, and lists the gestures and keys. A tap anywhere closes it, and the reader under it
 * gets no touch.
 */
@Composable
internal fun GuideOverlay(config: ReaderConfig, onDismiss: () -> Unit) {
    val close = stringResource(R.string.panel_close)
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(GuideScrim)
            .clickable(onClickLabel = close, role = Role.Button, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) {
        val regions = ReaderRegions(Size(maxWidth.value, maxHeight.value), config.barHeightDp)
        val content = guideContent(regions, config)
        for (zone in content.layout.zones) {
            Zone(zone.rect, tintOf(zone.turn), stringResource(zone.turn.label))
        }
        Zone(content.layout.commandBar, BarTint, stringResource(R.string.guide_command_bar), stringResource(R.string.guide_command_bar_hint))
        Zone(content.layout.pageBar, BarTint, stringResource(R.string.guide_page_bar), stringResource(R.string.guide_page_bar_hint))
        Legend(content, Modifier.align(Alignment.Center).padding(horizontal = 16.dp).widthIn(max = 320.dp))
    }
}

private fun tintOf(turn: Turn?): Color = when (turn) {
    Turn.Forward -> ForwardTint
    Turn.Back -> BackTint
    null -> NoActionTint
}

private val Turn?.label: Int
    get() = when (this) {
        Turn.Forward -> R.string.guide_forward
        Turn.Back -> R.string.guide_back
        null -> R.string.guide_no_action
    }

@Composable
private fun Zone(rect: Rect, tint: Color, name: String, hint: String? = null) {
    Box(
        Modifier
            .offset(rect.left.dp, rect.top.dp)
            .size(rect.width.dp, rect.height.dp)
            .background(tint)
            .border(Outline),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.padding(top = 6.dp, start = 2.dp, end = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            if (hint != null) Text(hint, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
private fun Legend(content: GuideContent, modifier: Modifier) {
    Column(
        modifier
            .background(Color(0xE6202028), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(stringResource(R.string.guide_title), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Line(R.string.guide_gesture_swipe, swipeText(content.swipeForward))
        Line(R.string.guide_gesture_pinch, stringResource(R.string.guide_pinch))
        Line(R.string.guide_gesture_hold, stringResource(R.string.guide_hold))
        Line(R.string.guide_key_arrows, arrowKeysText(content.leftKeyTurn))
        Line(R.string.guide_key_pages, stringResource(R.string.guide_pages_keys))
        if (content.volumeKeys != VolumeKeys.Off) Line(R.string.guide_key_volume, stringResource(R.string.guide_volume))
        Line(R.string.guide_key_menu, stringResource(R.string.guide_menu_keys))
        Text(stringResource(R.string.guide_dismiss), color = Color(0xFFB0B0B8), fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun Line(name: Int, text: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(stringResource(name), color = Color(0xFFB0B0B8), fontSize = 11.sp)
        Text(text, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun swipeText(forward: Side?): String = when (forward) {
    null -> stringResource(R.string.guide_drag_scrolls)
    Side.Right -> stringResource(R.string.guide_swipe, stringResource(R.string.guide_right), stringResource(R.string.guide_left))
    Side.Left -> stringResource(R.string.guide_swipe, stringResource(R.string.guide_left), stringResource(R.string.guide_right))
}

@Composable
private fun arrowKeysText(left: Turn): String {
    val (forward, back) = if (left == Turn.Forward) R.string.guide_left to R.string.guide_right else R.string.guide_right to R.string.guide_left
    return stringResource(R.string.guide_arrows, stringResource(forward), stringResource(back))
}
