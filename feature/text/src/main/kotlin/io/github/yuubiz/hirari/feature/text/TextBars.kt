package io.github.yuubiz.hirari.feature.text

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.ui.BarIcons
import io.github.yuubiz.hirari.core.ui.ReaderStrips
import io.github.yuubiz.hirari.core.ui.sp

@Composable
internal fun TextBars(
    state: TextReaderUiState.Reading,
    regions: ReaderRegions,
    pressed: Int?,
    onControl: (TextControl) -> Unit,
    onItem: (TextPanelItem) -> Unit,
) {
    val row = textCommandRow(regions) ?: return
    val number = state.number
    ReaderStrips(
        state.settings.readerBackground, state.title, number?.let { pageNumberText(it) }.orEmpty(),
        if (number == null) 0f else number.current.toFloat() / number.total.coerceAtLeast(1),
        TEXT_DIRECTION, state.settings.overlayTextSize.sp, regions, row,
        label = { item ->
            stringResource(when (item) {
                TextPanelItem.Close -> R.string.text_close
                TextPanelItem.More -> R.string.text_command_more
                is TextPanelItem.Command -> item.command.label
            })
        },
        icon = { it.icon },
        openCommands = stringResource(R.string.a11y_open_commands),
        openPages = stringResource(R.string.a11y_open_pages),
        onCommands = { onControl(TextControl.OpenCommands) },
        onPages = { onControl(TextControl.OpenPages) }, onItem = onItem,
        pressed = pressed,
    )
}

internal val TextPanelItem.icon: ImageVector
    get() = when (this) {
        TextPanelItem.Close -> BarIcons.Close
        TextPanelItem.More -> BarIcons.More
        is TextPanelItem.Command -> when (command) {
            TextCommand.Contents -> BarIcons.Contents
            TextCommand.Search -> BarIcons.Search
            TextCommand.AddBookmark -> BarIcons.AddBookmark
            TextCommand.Bookmarks -> BarIcons.Bookmarks
            TextCommand.Size -> BarIcons.TextSize
            TextCommand.Layout -> BarIcons.Layout
            TextCommand.Orientation -> BarIcons.Orientation
            TextCommand.Settings -> BarIcons.Settings
        }
    }
