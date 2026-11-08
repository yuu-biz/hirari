package io.github.yuubiz.hirari.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.LibraryGrouping
import io.github.yuubiz.hirari.core.model.LibrarySort
import kotlin.math.roundToInt

private val STRIP_PADDING = 12.dp

/** Which of the two group settings a chip of the strip changes (docs/spec.md section 4.3, 並べ替えの帯). */
enum class StripLevel { First, Second }

/** The group chips: [level] is the setting they change, [choices] the values offered, [chosen] the one in force. */
data class StripGroup(val level: StripLevel, val choices: List<LibraryGrouping>, val chosen: LibraryGrouping) {
    /** The settings after choosing [value]. */
    fun choose(value: LibraryGrouping): (GlobalSettings) -> GlobalSettings = when (level) {
        StripLevel.First -> { s -> s.copy(libraryGrouping = value) }
        StripLevel.Second -> { s -> s.copy(librarySubgrouping = value) }
    }
}

/** What the strip offers: the sort, and the group unless the screen is one that makes no groups. */
data class SortStrip(val sort: LibrarySort, val group: StripGroup?)

/**
 * The strip of a screen showing [pseudo] (`null` for a real folder). A real folder's groups come from 「グループ」. Inside a
 * group of the first level they come from 「その中のグループ」, which never repeats the first level's value. Inside a group
 * of the second level the screen makes no groups.
 */
internal fun sortStrip(look: ListLook, sort: LibrarySort, pseudo: PseudoFolder?): SortStrip = SortStrip(
    sort,
    when {
        pseudo == null -> StripGroup(StripLevel.First, LibraryGrouping.entries, look.grouping)
        pseudo.parent == null -> StripGroup(StripLevel.Second, LibraryGrouping.entries.filter { it != look.grouping }, look.effectiveSubgrouping)
        else -> null
    },
)

@StringRes
private fun LibraryGrouping.stripLabel(): Int = when (this) {
    LibraryGrouping.None -> R.string.strip_group_none
    LibraryGrouping.Author -> R.string.look_group_author
    LibraryGrouping.Series -> R.string.look_group_series
    LibraryGrouping.Original -> R.string.look_group_original
    LibraryGrouping.Event -> R.string.look_group_event
}

/**
 * 並べ替えの帯 (docs/spec.md section 4.3): the sort and the group as chips in one row under the top bar, which scrolls
 * sideways and starts with the chosen group in view. A tap saves the value at once; the list follows the settings.
 */
@Composable
internal fun SortStripRow(strip: SortStrip, onSort: (LibrarySort) -> Unit, onGroup: (LibraryGrouping) -> Unit, modifier: Modifier = Modifier) {
    val sortKind = stringResource(R.string.strip_sort_kind)
    val groupKind = stringResource(R.string.strip_group_kind)
    val selected = stringResource(R.string.strip_selected)
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    var chosenEnd by remember { mutableIntStateOf(0) }
    var placed by remember { mutableStateOf(false) }
    val edge = with(LocalDensity.current) { STRIP_PADDING.roundToPx() }
    LaunchedEffect(viewport, chosenEnd) {
        if (!placed && viewport > 0 && chosenEnd > 0) {
            scroll.scrollTo((chosenEnd + edge - viewport).coerceAtLeast(0))
            placed = true
        }
    }
    Row(
        modifier.fillMaxWidth().onSizeChanged { viewport = it.width }.horizontalScroll(scroll).heightIn(min = 40.dp).padding(horizontal = STRIP_PADDING),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (sort in LibrarySort.entries) {
            val label = stringResource(sort.label())
            StripChip(label, sortKind, sort == strip.sort, selected, Modifier) { onSort(sort) }
        }
        strip.group?.let { group ->
            VerticalDivider(Modifier.heightIn(max = 24.dp))
            for (value in group.choices) {
                val label = stringResource(value.stripLabel())
                val on = value == group.chosen
                StripChip(label, groupKind, on, selected, if (on) Modifier.onPlaced { chosenEnd = (it.positionInParent().x + it.size.width).roundToInt() } else Modifier) { onGroup(value) }
            }
        }
    }
}

/** A chip a screen reader reads as its kind, its value and whether it is chosen: 「並べ替え、名前、選択中」. */
@Composable
private fun StripChip(label: String, kind: String, chosen: Boolean, selectedWord: String, modifier: Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = chosen,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier.semantics {
            role = Role.RadioButton
            contentDescription = "$kind、$label"
            if (chosen) stateDescription = selectedWord
        },
    )
}
