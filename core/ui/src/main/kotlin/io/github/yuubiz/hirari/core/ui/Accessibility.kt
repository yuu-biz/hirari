package io.github.yuubiz.hirari.core.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The smallest touch target of docs/spec.md section 6.7. */
val MinTouchTarget = 48.dp

/** Grows a row that is not a Material component to [MinTouchTarget]; Material buttons and rows already do. */
fun Modifier.minTouchTarget(): Modifier = heightIn(min = MinTouchTarget)

/** Takes a decoration (a glyph, an outline copy of a text, a dot) out of what a screen reader reads. */
fun Modifier.decorative(): Modifier = clearAndSetSemantics {}

/** Has a screen reader read the node again whenever its text or description changes. */
fun Modifier.announced(): Modifier = semantics { liveRegion = LiveRegionMode.Polite }

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
