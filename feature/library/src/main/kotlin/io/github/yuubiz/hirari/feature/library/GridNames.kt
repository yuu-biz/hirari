package io.github.yuubiz.hirari.feature.library

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import java.text.BreakIterator

/**
 * What a grid cell shows of an item's name (docs/spec.md section 4.3, グリッドの名前): [title] on the first two lines
 * and [note], the inside of the first bracket pair, on the third; [note] is empty when the name has no brackets.
 */
data class GridName(val title: String, val note: String)

/** The extension goes from a book or a text, whose kind label tells it; a folder and an image keep the whole name. */
fun FileItem.gridName(): GridName {
    val name = entry.name
    val base = when (this) {
        is FileItem.Archive, is FileItem.Text -> name.substringBeforeLast('.').ifEmpty { name }
        is FileItem.Folder, is FileItem.Image -> name
    }
    return splitBrackets(base)
}

private val BRACKETS = mapOf('[' to ']', '［' to '］')

/**
 * Takes the first `[…]` or `［…］` pair out of [name] into the note. The parts around it join with one space when
 * either side had one; a name that is only the brackets keeps their inside as its title.
 */
internal fun splitBrackets(name: String): GridName {
    for ((open, char) in name.withIndex()) {
        val closing = BRACKETS[char] ?: continue
        val close = name.indexOf(closing, open + 1)
        if (close < 0) continue
        val note = name.substring(open + 1, close).trim()
        val before = name.substring(0, open)
        val after = name.substring(close + 1)
        val gap = if (before.isNotBlank() && after.isNotBlank() && (before.last().isWhitespace() || after.first().isWhitespace())) " " else ""
        val title = before.trim() + gap + after.trim()
        return if (title.isEmpty()) GridName(note.ifEmpty { name.trim() }, "") else GridName(title, note)
    }
    return GridName(name.trim(), "")
}

private const val ELLIPSIS = "…"

/**
 * [text] as it fits, by [fits]: whole when it does, or with as many characters as fit kept from its start and its end
 * around an ellipsis, so the volume number at the end of a series title stays. Cuts fall between user-perceived
 * characters.
 */
internal fun middleElided(text: String, fits: (String) -> Boolean): String {
    if (fits(text)) return text
    val bounds = graphemeBounds(text)
    val count = bounds.size - 1
    fun kept(n: Int): String {
        val head = text.substring(0, bounds[(n + 1) / 2]).trimEnd()
        val tail = text.substring(bounds[count - n / 2]).trimStart()
        return head + ELLIPSIS + tail
    }
    var low = 0
    var high = count - 1
    var best = ELLIPSIS
    while (low <= high) {
        val mid = (low + high) / 2
        val candidate = kept(mid)
        if (fits(candidate)) {
            best = candidate
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

private fun graphemeBounds(text: String): IntArray {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val bounds = ArrayList<Int>(text.length + 1)
    var at = iterator.first()
    while (at != BreakIterator.DONE) {
        bounds += at
        at = iterator.next()
    }
    return bounds.toIntArray()
}

/** Fits a grid title into [GRID_TITLE_LINES] lines of [widthPx] at [style]; one per list, shared by its cells. */
internal class TitleFitter(private val measurer: TextMeasurer, val style: TextStyle, private val widthPx: Int) {
    fun fit(title: String): String = middleElided(title) { candidate ->
        !measurer.measure(candidate, style, maxLines = GRID_TITLE_LINES, constraints = Constraints(maxWidth = widthPx)).hasVisualOverflow
    }
}

internal const val GRID_TITLE_LINES = 2
