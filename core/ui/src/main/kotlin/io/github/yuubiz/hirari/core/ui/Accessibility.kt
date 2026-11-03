package io.github.yuubiz.hirari.core.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp

/** The smallest touch target of docs/spec.md section 6.7. */
val MinTouchTarget = 48.dp

/** Grows a row that is not a Material component to [MinTouchTarget]; Material buttons and rows already do. */
fun Modifier.minTouchTarget(): Modifier = heightIn(min = MinTouchTarget)

/** Takes a decoration (a glyph, an outline copy of a text, a dot) out of what a screen reader reads. */
fun Modifier.decorative(): Modifier = clearAndSetSemantics {}

/** Has a screen reader read the node, and what is in it, again whenever it changes. */
fun Modifier.announced(): Modifier = semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }

/**
 * Takes every tap that lands on it and says nothing to a screen reader. A sheet's own area uses it so that a tap on the
 * sheet does not reach the scrim beneath. A clickable would do the same but would also merge the sheet's controls into
 * one node.
 */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) { detectTapGestures { } }

/**
 * The command bar and the page bar of a reader as nodes of their own, above and below the page. They draw nothing and
 * take no touch, so a finger still reaches the bars as before; a screen reader gets two buttons that open what the bars
 * open. [barHeightDp] is the height of each bar.
 */
@Composable
fun BarSemantics(
    barHeightDp: Float,
    commandBar: String,
    pageBar: String,
    openCommands: String,
    openPages: String,
    onCommands: () -> Unit,
    onPages: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(barHeightDp.dp).semantics {
                contentDescription = commandBar
                role = Role.Button
                traversalIndex = 0f
                onClick(openCommands) {
                    onCommands()
                    true
                }
            },
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth().height(barHeightDp.dp).semantics {
                contentDescription = pageBar
                role = Role.Button
                traversalIndex = 2f
                onClick(openPages) {
                    onPages()
                    true
                }
            },
        )
    }
}

/** A choice among several, as a screen reader says it: "selected" or not, read as a radio button. */
fun Modifier.chosen(selected: Boolean): Modifier = semantics {
    this.selected = selected
    role = Role.RadioButton
}

/**
 * A button that shows a [glyph] and is read as [label]: the arrow of 戻る, the dots of a menu. A glyph read aloud would
 * be "left arrow" or "vertical ellipsis".
 */
@Composable
fun GlyphButton(glyph: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick = onClick, modifier = modifier.semantics { contentDescription = label }, enabled = enabled) {
        Text(glyph, Modifier.decorative())
    }
}
