package io.github.yuubiz.hirari.feature.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.traversalIndex
import io.github.yuubiz.hirari.core.text.GlyphContent
import io.github.yuubiz.hirari.core.text.PageContent
import io.github.yuubiz.hirari.core.text.TextPage

/**
 * What a screen reader can do on the page without a gesture (docs/spec.md section 6.7): the turns, panels and menu
 * that the tap zones, the bars and the Menu key reach.
 */
internal enum class TextControl(@StringRes val label: Int) {
    NextPage(R.string.a11y_next_page),
    PreviousPage(R.string.a11y_previous_page),
    FirstPage(R.string.a11y_first_page),
    LastPage(R.string.a11y_last_page),
    OpenPages(R.string.a11y_open_pages),
    OpenCommands(R.string.a11y_open_commands),
}

/**
 * The text of a page in reading order: the columns from the right edge to the left, each from its top. The page is
 * drawn into a canvas, so this is the only way for a screen reader to get at what is written. Ruby and marks are left
 * out; a speech engine reads the kanji by itself.
 */
internal fun TextPage.plainText(): String = when (val shown = content) {
    is PageContent.Picture -> ""
    is PageContent.Lines -> shown.lines.sortedByDescending { it.x }.joinToString("") { line ->
        line.glyphs.joinToString("") { glyph ->
            when (val drawn = glyph.content) {
                is GlyphContent.Chars -> drawn.text
                is GlyphContent.Dash -> "―"
                is GlyphContent.Leader -> "…"
                is GlyphContent.Image -> ""
            }
        }
    }
}

/** What the screen reader says of the shown pages: their text, then nothing for a page still being laid out. */
internal fun TextReaderUiState.Reading.spokenPages(): String = shown.mapNotNull { page(it)?.plainText()?.ifEmpty { null } }.joinToString("\n")

/**
 * The page as a node a screen reader can focus: it reads the text of the shown pages, then the page number as its
 * state, and reads again whenever a turn changes them. A double tap is 次のページ; the other controls are its actions.
 */
@Composable
internal fun Modifier.textAccessibility(state: TextReaderUiState.Reading, onControl: (TextControl) -> Unit): Modifier {
    val text = state.spokenPages()
    val number = state.run?.number(state.current)?.let { pageNumberText(it) }
    val labels = TextControl.entries.associateWith { stringResource(it.label) }
    return semantics {
        contentDescription = text
        number?.let { stateDescription = it }
        liveRegion = LiveRegionMode.Polite
        traversalIndex = 1f
        onClick(labels.getValue(TextControl.NextPage)) {
            onControl(TextControl.NextPage)
            true
        }
        customActions = TextControl.entries.filter { it != TextControl.NextPage }.map { control ->
            CustomAccessibilityAction(labels.getValue(control)) {
                onControl(control)
                true
            }
        }
    }
}
