package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BarLayout
import io.github.yuubiz.hirari.core.model.CommandCells
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.SystemGestureEdges

/** Screen dp for input and strips; the page frame uses the reserved area's pixel dimensions. */
internal fun textRegions(screen: Size, settings: GlobalSettings, edges: SystemGestureEdges = SystemGestureEdges.Fixed) =
    ReaderRegions(screen, settings.barHeightDp.toFloat(), edges,
        if (settings.barDisplay.shown(screen)) BarLayout.Shown else BarLayout.Hidden)

internal fun Viewport.textRegions(settings: GlobalSettings) =
    textRegions(Size(width / density, height / density), settings)

internal fun textCommandRow(regions: ReaderRegions): CommandCells<TextPanelItem>? = regions.commandRow?.let {
    CommandCells<TextPanelItem>(
        TextCommand.entries.filter { it != TextCommand.Settings }.map { TextPanelItem.Command(it) },
        TextPanelItem.Close, TextPanelItem.Command(TextCommand.Settings), TextPanelItem.More, it,
    )
}
