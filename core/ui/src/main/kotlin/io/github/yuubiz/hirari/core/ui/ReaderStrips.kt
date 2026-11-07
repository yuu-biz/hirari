package io.github.yuubiz.hirari.core.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.model.CommandCells
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.ReadingDirection

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
    openCommands: String,
    openPages: String,
    onCommands: () -> Unit,
    onPages: () -> Unit,
    onItem: (T) -> Unit,
) {
    val background = readerBackgroundColor(readerBackground)
    val ink = readableOn(background)
    val height = regions.barHeight.dp
    val textSize = textSizeSp.sp
    val status = rememberStatusText()
    Row(
        Modifier.fillMaxWidth().height(height).background(background)
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
    Box(Modifier.offset(row.bounds.left.dp, row.bounds.top.dp).size(row.bounds.width.dp, row.bounds.height.dp).background(background)) {
        for ((index, item) in row.cells.withIndex()) {
            ReaderCommandCell(label(item), ink,
                Modifier.offset((index * row.cellWidth).dp).size(row.cellWidth.dp, height)
                    .semantics { traversalIndex = 2f + index / 100f },
            ) { onItem(item) }
        }
    }
    Box(
        Modifier.offset(y = regions.pageBar.top.dp).fillMaxWidth().height(height).background(background)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                traversalIndex = 3f
                onClick(openPages) { onPages(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter).decorative()) {
            val width = size.width * progress.coerceIn(0f, 1f)
            val left = if (direction == ReadingDirection.RightToLeft) size.width - width else 0f
            drawRect(ink, Offset(left, 0f), androidx.compose.ui.geometry.Size(width, size.height))
        }
        Text(pageNumber, color = ink, fontSize = textSize)
    }
}

/** A semantic button; pointer gestures are handled by the containing reader. */
@Composable
fun ReaderCommandCell(label: String, color: Color, modifier: Modifier, onExecute: () -> Unit) {
    Box(modifier.semantics(mergeDescendants = true) {
        role = Role.Button
        onClick { onExecute(); true }
    }, contentAlignment = Alignment.Center) {
        Text(label, Modifier.padding(horizontal = 4.dp), color = color,
            textAlign = TextAlign.Center, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 13.sp))
    }
}
