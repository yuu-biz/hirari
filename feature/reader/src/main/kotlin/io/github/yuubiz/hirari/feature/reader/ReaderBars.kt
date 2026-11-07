package io.github.yuubiz.hirari.feature.reader

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.ui.decorative
import io.github.yuubiz.hirari.core.ui.readableOn
import io.github.yuubiz.hirari.core.ui.readerBackgroundColor

/** Visible strips take no pointer input of their own; the screen input preserves the bars' gestures. */
@Composable
internal fun ReaderBars(readerBackground: ReaderBackground, title: String, state: ReaderState, onControl: (ReaderControl) -> Unit, onItem: (PanelItem) -> Unit) {
    val background = readerBackgroundColor(readerBackground)
    val ink = readableOn(background)
    val regions = state.area.regions
    val height = regions.barHeight.dp
    val pageNumber = pageNumberText(state.current, state.pagination.shapes.size)
    val textSize = state.config.overlays.textSize.sp.sp
    val status = rememberStatusText()
    val openCommands = stringResource(R.string.a11y_open_commands)
    val openPages = stringResource(R.string.a11y_open_pages)
    Row(
        Modifier.fillMaxWidth().height(height).background(background)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                traversalIndex = 0f
                onClick(openCommands) { onControl(ReaderControl.OpenCommands); true }
            }.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, Modifier.weight(1f).padding(end = 8.dp),
            color = ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
        )
        Text(
            "$pageNumber  $status", Modifier.widthIn(max = (regions.screen.width * 0.65f).dp),
            color = ink, fontSize = textSize,
            maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = textSize),
        )
    }
    state.commandRow()?.let { row ->
        Box(Modifier.offset(row.bounds.left.dp, row.bounds.top.dp).size(row.bounds.width.dp, row.bounds.height.dp).background(background)) {
            for ((index, item) in row.cells.withIndex()) {
                CommandCell(
                    item, ink,
                    Modifier.offset((index * row.cellWidth).dp).size(row.cellWidth.dp, height)
                        .semantics { traversalIndex = 2f + index / 100f },
                    onItem,
                )
            }
        }
    }
    Box(
        Modifier.offset(y = regions.pageBar.top.dp).fillMaxWidth().height(height).background(background)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                traversalIndex = 3f
                onClick(openPages) { onControl(ReaderControl.OpenPages); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter).decorative()) {
            val fraction = (state.current.pages.max() + 1f) / state.pagination.shapes.size
            val width = size.width * fraction
            val left = if (state.config.direction == ReadingDirection.RightToLeft) size.width - width else 0f
            drawRect(ink, Offset(left, 0f), androidx.compose.ui.geometry.Size(width, size.height))
        }
        Text(pageNumber, color = ink, fontSize = textSize)
    }
}
