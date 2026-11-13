package io.github.yuubiz.hirari.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.model.CommandCells
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.ReadingDirection

/** Below this bar height a button drops its icon and keeps its name (docs/spec.md section 4.8, バーを出す表示). */
private const val ICON_MIN_BAR_DP = 52f

/** The amber the assignable slots of the command row are tinted with, apart from the fixed buttons. */
internal val SlotTint = Color(0xFFF2C46A)

/** How much of the text color, or of [SlotTint], the parts of the bars carry. */
internal object BarAlpha {
    const val DIVIDER = 0.14f
    const val BUTTON = 0.12f
    const val PRESSED = 0.24f
    const val SLOT = 0.22f
    const val SLOT_PRESSED = 0.34f
    const val DISABLED = 0.38f
    const val GROOVE = 0.10f
    const val FILL = 0.26f
}

/** Visible strips leave pointer routing to the reader, preserving the bars' gestures. */
@Composable
fun <T> ReaderStrips(
    readerBackground: ReaderBackground,
    title: String,
    pageNumber: String,
    progress: Float,
    direction: ReadingDirection,
    textSizeSp: Int,
    regions: ReaderRegions,
    row: CommandCells<T>,
    label: @Composable (T) -> String,
    icon: (T) -> ImageVector?,
    openCommands: String,
    openPages: String,
    onCommands: () -> Unit,
    onPages: () -> Unit,
    onItem: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
    slot: (T) -> Boolean = { false },
    pressed: Int? = null,
) {
    val background = readerBackgroundColor(readerBackground)
    val surface = barSurface(background)
    val ink = readableOn(background)
    val divider = ink.copy(alpha = BarAlpha.DIVIDER)
    val height = regions.barHeight.dp
    val textSize = textSizeSp.sp
    val status = rememberStatusText()
    Row(
        Modifier.fillMaxWidth().height(height).background(surface)
            .edgeLine(divider, top = false)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                traversalIndex = 0f
                onClick(openCommands) { onCommands(); true }
            }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, Modifier.weight(1f).padding(end = 8.dp), color = ink,
            fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
        Text("$pageNumber  $status", Modifier.widthIn(max = (regions.screen.width * 0.65f).dp),
            color = ink, fontSize = textSize, maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = textSize))
    }
    Box(
        Modifier.offset(row.bounds.left.dp, row.bounds.top.dp).size(row.bounds.width.dp, row.bounds.height.dp)
            .background(surface).edgeLine(divider, top = true),
    ) {
        for ((index, item) in row.cells.withIndex()) {
            val down = index == pressed
            val container = if (slot(item)) SlotTint.copy(alpha = if (down) BarAlpha.SLOT_PRESSED else BarAlpha.SLOT)
            else ink.copy(alpha = if (down) BarAlpha.PRESSED else BarAlpha.BUTTON)
            ReaderCommandCell(label(item), icon(item).takeIf { regions.barHeight >= ICON_MIN_BAR_DP }, ink,
                Modifier.offset((index * row.cellWidth).dp).size(row.cellWidth.dp, height)
                    .semantics { traversalIndex = 2f + index / 100f },
                container = container,
                enabled = enabled(item),
            ) { onItem(item) }
        }
    }
    Box(
        Modifier.offset(y = regions.pageBar.top.dp).fillMaxWidth().height(height).background(surface)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                traversalIndex = 3f
                onClick(openPages) { onPages(); true }
            }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        PageGroove(progress, direction, ink, Modifier.fillMaxWidth().height((regions.barHeight - 22f).coerceAtLeast(18f).dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("«", Modifier.decorative(), color = ink, fontSize = 17.sp)
            Text(pageNumber, Modifier.weight(1f), color = ink, fontSize = textSize, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center, maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = textSize))
            Text("»", Modifier.decorative(), color = ink, fontSize = 17.sp)
        }
    }
}

/** The groove of the page bar, filled from where the binding direction starts up to [progress]. */
@Composable
private fun PageGroove(progress: Float, direction: ReadingDirection, ink: Color, modifier: Modifier) {
    Canvas(modifier.decorative()) {
        val radius = CornerRadius(size.height / 2f)
        val groove = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }
        drawPath(groove, ink.copy(alpha = BarAlpha.GROOVE))
        val width = size.width * progress.coerceIn(0f, 1f)
        val left = if (direction == ReadingDirection.RightToLeft) size.width - width else 0f
        clipPath(groove) { drawRect(ink.copy(alpha = BarAlpha.FILL), Offset(left, 0f), Size(width, size.height)) }
    }
}

private fun Modifier.edgeLine(color: Color, top: Boolean) = drawWithContent {
    drawContent()
    val stroke = 1.dp.toPx()
    drawRect(color, Offset(0f, if (top) 0f else size.height - stroke), Size(size.width, stroke))
}

/**
 * A semantic button with [icon] over [label]; pointer gestures are handled by the containing reader. [container] is
 * the rounded surface behind it, or null for none, as in the command panel. Without a label it is an empty slot,
 * which a screen reader skips.
 */
@Composable
fun ReaderCommandCell(
    label: String,
    icon: ImageVector?,
    color: Color,
    modifier: Modifier,
    container: Color? = null,
    enabled: Boolean = true,
    onExecute: () -> Unit,
) {
    val content = if (enabled) color else color.copy(alpha = BarAlpha.DISABLED)
    val semantics = if (label.isEmpty()) Modifier.clearAndSetSemantics {} else Modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        onClick { onExecute(); true }
    }
    Box(modifier.then(semantics).padding(3.dp)) {
        Column(
            Modifier.fillMaxSize()
                .then(if (container == null) Modifier else Modifier.clip(RoundedCornerShape(12.dp)).background(container)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (icon != null) Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = content)
            Text(label, Modifier.padding(horizontal = 2.dp), color = content,
                textAlign = TextAlign.Center, fontSize = 10.5.sp, lineHeight = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 10.5.sp))
        }
    }
}
