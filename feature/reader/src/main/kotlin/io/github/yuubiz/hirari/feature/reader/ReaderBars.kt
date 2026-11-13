package io.github.yuubiz.hirari.feature.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.yuubiz.hirari.core.model.ReaderBackground
import io.github.yuubiz.hirari.core.ui.ReaderStrips
import io.github.yuubiz.hirari.core.ui.sp

@Composable
internal fun ReaderBars(
    readerBackground: ReaderBackground,
    title: String,
    state: ReaderState,
    pressed: Int?,
    onControl: (ReaderControl) -> Unit,
    onItem: (PanelItem) -> Unit,
) {
    val row = state.commandRow() ?: return
    ReaderStrips(
        readerBackground, title, pageNumberText(state.current, state.pagination.shapes.size),
        (state.current.pages.max() + 1f) / state.pagination.shapes.size,
        state.config.direction, state.config.overlays.textSize.sp, state.area.regions, row.layout,
        label = { it.label() },
        icon = { it.icon },
        openCommands = stringResource(R.string.a11y_open_commands),
        openPages = stringResource(R.string.a11y_open_pages),
        onCommands = { onControl(ReaderControl.OpenCommands) },
        onPages = { onControl(ReaderControl.OpenPages) }, onItem = onItem,
        enabled = { it.enabled(state.shiftsPairs) },
        slot = row::isSlot,
        pressed = pressed,
    )
}
