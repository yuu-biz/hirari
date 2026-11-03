package io.github.yuubiz.hirari.feature.text

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.yuubiz.hirari.core.data.FontImport
import io.github.yuubiz.hirari.core.data.FontStore
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.BookEdge
import io.github.yuubiz.hirari.core.model.CommandTargets
import io.github.yuubiz.hirari.core.model.PanelTarget
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.TextTypeface

/** The text commands of docs/spec.md section 4.9, in the order of the spec. */
internal enum class TextCommand(val label: Int) {
    Contents(R.string.text_command_contents),
    Search(R.string.text_command_search),
    AddBookmark(R.string.text_command_add_bookmark),
    Bookmarks(R.string.text_command_bookmarks),
    Size(R.string.text_command_size),
    Layout(R.string.text_command_layout),
    Orientation(R.string.text_command_orientation),
    Settings(R.string.text_command_settings),
}

/** What a finger can land on in the command panel: 閉じる, or a command. */
internal sealed interface TextPanelItem : PanelTarget {
    data object Close : TextPanelItem

    data class Command(val command: TextCommand) : TextPanelItem
}

/**
 * Where the command panel and its items are on screen, as the panel lays them out in pixels of the root; the reader
 * fills the root, so they match its touches once turned into dp by [density]. Empty while the panel is closed.
 */
internal class TextPanelBounds(private val density: Float) : CommandTargets {
    var panel: Rect? = null
    val items = HashMap<TextPanelItem, Rect>()

    fun clear() {
        panel = null
        items.clear()
    }

    private fun Point.px() = Offset(x * density, y * density)

    override fun contains(point: Point): Boolean = panel?.contains(point.px()) == true

    override fun itemAt(point: Point): TextPanelItem? =
        if (contains(point)) items.entries.firstOrNull { point.px() in it.value }?.key else null
}

/** Runs [command] of the command panel; the panel is closed by then. */
@Composable
internal fun rememberCommandRunner(
    viewModel: TextReaderViewModel,
    onPanel: (TextPanel?) -> Unit,
    onOpenSettings: () -> Unit,
    onOrientation: () -> Unit,
): (TextCommand) -> Unit {
    val context = LocalContext.current
    val added = stringResource(R.string.text_bookmark_added)
    val settings by rememberUpdatedState(onOpenSettings)
    val orientation by rememberUpdatedState(onOrientation)
    return remember(viewModel) {
        { command ->
            onPanel(null)
            when (command) {
                TextCommand.Contents -> onPanel(TextPanel.Contents)
                TextCommand.Search -> onPanel(TextPanel.Search)
                TextCommand.AddBookmark -> {
                    viewModel.addBookmark()
                    Toast.makeText(context, added, Toast.LENGTH_SHORT).show()
                }
                TextCommand.Bookmarks -> onPanel(TextPanel.Bookmarks)
                TextCommand.Size -> onPanel(TextPanel.Size)
                TextCommand.Layout -> viewModel.updateSettings(GlobalSettings::nextLayout)
                TextCommand.Orientation -> orientation()
                TextCommand.Settings -> settings()
            }
        }
    }
}

/**
 * The panels over the reader. [commandBounds] collects where the command panel and its items sit on screen, so a
 * finger that pressed the command bar and slid onto an item runs it on release, with [highlight] under the finger
 * (docs/spec.md section 4.8, コマンドバー).
 */
@Composable
internal fun TextPanels(
    panel: TextPanel?,
    state: TextReaderUiState.Reading,
    viewModel: TextReaderViewModel,
    commandBounds: TextPanelBounds,
    highlight: TextPanelItem?,
    onPanel: (TextPanel?) -> Unit,
    onClose: () -> Unit,
    onCommand: (TextCommand) -> Unit,
) {
    when (panel) {
        null -> Unit
        TextPanel.Commands -> Commands(
            commandBounds, highlight, state.settings.barHeightDp, onDismiss = { onPanel(null) }, onClose = onClose, onCommand = onCommand,
        )
        TextPanel.Contents -> Sheet(onDismiss = { onPanel(null) }) {
            if (state.contents.isEmpty()) Message(R.string.text_contents_empty)
            LazyColumn {
                items(state.contents) { heading ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            viewModel.goTo(heading)
                            onPanel(null)
                        }.padding(horizontal = 24.dp, vertical = 12.dp),
                    ) {
                        Text(heading.text, modifier = Modifier.padding(start = (heading.level.ordinal * 16).dp))
                    }
                }
            }
        }
        TextPanel.Search -> Sheet(onDismiss = { onPanel(null) }) { SearchPanel(state.search, viewModel) { onPanel(null) } }
        TextPanel.Bookmarks -> Sheet(onDismiss = { onPanel(null) }) {
            if (state.bookmarks.isEmpty()) Message(R.string.text_bookmarks_empty)
            LazyColumn {
                items(state.bookmarks, key = { it.id }) { mark ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            mark.label,
                            Modifier.weight(1f).clickable {
                                viewModel.goTo(mark)
                                onPanel(null)
                            }.padding(vertical = 12.dp),
                        )
                        TextButton(onClick = { viewModel.deleteBookmark(mark) }) { Text(stringResource(R.string.text_bookmark_delete)) }
                    }
                }
            }
        }
        TextPanel.Size -> Sheet(onDismiss = { onPanel(null) }) { SizePanel(state.settings, viewModel) }
        TextPanel.PageJump -> Sheet(onDismiss = { onPanel(null) }) { PageJumpPanel(state, viewModel) }
    }
}

/** The command panel under the command bar: 閉じる and 設定 on top, then the commands, four to a row. */
@Composable
private fun Commands(
    bounds: TextPanelBounds,
    highlight: TextPanelItem?,
    barHeightDp: Int,
    onDismiss: () -> Unit,
    onClose: () -> Unit,
    onCommand: (TextCommand) -> Unit,
) {
    DisposableEffect(bounds) { onDispose { bounds.clear() } }
    fun Modifier.item(item: TextPanelItem) =
        onGloballyPositioned { bounds.items[item] = it.boundsInRoot() }.background(if (item == highlight) HIGHLIGHT else Color.Transparent)
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
        Column(
            Modifier.padding(top = barHeightDp.dp).fillMaxWidth().onGloballyPositioned { bounds.panel = it.boundsInRoot() }
                .background(PANEL_BACKGROUND).clickable(enabled = false) {}.padding(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PanelButton(R.string.text_close, Modifier.item(TextPanelItem.Close), onClose)
                PanelButton(R.string.text_command_settings, Modifier.item(TextPanelItem.Command(TextCommand.Settings))) {
                    onCommand(TextCommand.Settings)
                }
            }
            TextCommand.entries.filter { it != TextCommand.Settings }.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { command ->
                        Box(
                            Modifier.weight(1f).heightIn(min = 56.dp).item(TextPanelItem.Command(command)).clickable { onCommand(command) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(command.label), color = Color.White)
                        }
                    }
                    repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** The item under the finger as it slides over the panel. */
private val HIGHLIGHT = Color(0x40FFFFFF)

@Composable
private fun PanelButton(label: Int, modifier: Modifier, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = modifier) { Text(stringResource(label), color = Color.White) }
}

/** A sheet from the bottom; a tap above it closes it. */
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x66000000)).clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Surface(Modifier.fillMaxWidth().heightIn(max = 480.dp).clickable(enabled = false) {}, tonalElevation = 2.dp) {
            Column(Modifier.padding(vertical = 8.dp)) { content() }
        }
    }
}

@Composable
private fun Message(text: Int) {
    Text(stringResource(text), Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
}

/**
 * The text beside this one toward [edge] (docs/spec.md section 4.8, 本の端での動作). Until the library has answered,
 * its items show disabled; a text with no folder has none.
 */
@Composable
internal fun TextBookEndSheet(
    edge: BookEdge,
    viewModel: TextReaderViewModel,
    onDismiss: () -> Unit,
    onCloseReader: () -> Unit,
    onOpen: (TextOpening) -> Unit,
) {
    var sibling by remember(edge) { mutableStateOf<TextSibling?>(null) }
    LaunchedEffect(edge) { sibling = viewModel.sibling(edge) }
    val atEnd = edge == BookEdge.End
    val starts = if (atEnd) listOf(TextStart.Saved, TextStart.First) else listOf(TextStart.Saved, TextStart.Last)
    Sheet(onDismiss) {
        when (val found = sibling) {
            TextSibling.Last -> Message(if (atEnd) R.string.text_book_end_last_in_folder else R.string.text_book_end_first_in_folder)
            TextSibling.NoFolder -> Unit
            is TextSibling.Found, null -> for (start in starts) {
                TextButton(onClick = { (found as? TextSibling.Found)?.let { onOpen(it.opening(start)) } }, enabled = found != null) {
                    Text(stringResource(if (atEnd) R.string.text_book_end_next else R.string.text_book_end_previous, stringResource(start.label)))
                }
            }
        }
        if (atEnd) {
            TextButton(onClick = {
                viewModel.first()
                onDismiss()
            }) { Text(stringResource(R.string.text_book_end_from_first)) }
        }
        TextButton(onClick = onCloseReader) { Text(stringResource(R.string.text_close)) }
        TextButton(onClick = {
            viewModel.restorePosition()
            onCloseReader()
        }) { Text(stringResource(R.string.text_book_end_restore)) }
    }
}

private val TextStart.label: Int
    get() = when (this) {
        TextStart.Saved -> R.string.text_neighbor_saved
        TextStart.First -> R.string.text_neighbor_first
        TextStart.Last -> R.string.text_neighbor_last
    }

@Composable
private fun SearchPanel(search: SearchState, viewModel: TextReaderViewModel, onShown: () -> Unit) {
    var query by rememberSaveable { mutableStateOf((search as? SearchState.Done)?.result?.query.orEmpty()) }
    OutlinedTextField(
        value = query,
        onValueChange = {
            query = it
            viewModel.search(it)
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        placeholder = { Text(stringResource(R.string.text_search_hint)) },
        singleLine = true,
    )
    val result = (search as? SearchState.Done)?.result ?: return
    if (result.hits.isEmpty() && result.query.isNotBlank()) Message(R.string.text_search_none)
    LazyColumn {
        items(result.hits) { hit ->
            Text(
                buildAnnotatedString {
                    append(hit.context.substring(0, hit.matchStart))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(hit.context.substring(hit.matchStart, hit.matchEnd)) }
                    append(hit.context.substring(hit.matchEnd))
                },
                Modifier.fillMaxWidth().clickable {
                    viewModel.show(hit)
                    onShown()
                }.padding(horizontal = 24.dp, vertical = 10.dp),
                maxLines = 2,
            )
            HorizontalDivider()
        }
        if (result.truncated) item { Message(R.string.text_search_more) }
    }
}

/** 文字の大きさ, with the typeface beside it, since both change the layout. */
@Composable
private fun SizePanel(settings: GlobalSettings, viewModel: TextReaderViewModel) {
    var size by remember(settings.textSizeSp) { mutableFloatStateOf(settings.textSizeSp.toFloat()) }
    Text(stringResource(R.string.text_size_value, size.toInt()), Modifier.padding(horizontal = 24.dp))
    Slider(
        value = size,
        onValueChange = { size = it },
        onValueChangeFinished = { viewModel.updateSettings { it.copy(textSizeSp = size.toInt()) } },
        valueRange = 6f..62f,
        steps = 62 - 6 - 1,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Text(stringResource(R.string.text_typeface), Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
    var refresh by remember { mutableIntStateOf(0) }
    val imported by produceState(emptyList<String>(), refresh) { value = viewModel.importedFonts() }
    val store by produceState<FontStore?>(null) { value = viewModel.fonts() }
    val choices = listOf(TextTypeface.Mincho, TextTypeface.Gothic) + imported.map { TextTypeface.Imported(it) }
    // Out of the list, so the picker's result still lands while its button is scrolled away.
    val pick = store?.let { rememberFontPick(it, viewModel) { refresh++ } }
    // The list takes what the sheet leaves under the slider and scrolls, so many imported fonts keep the import in reach.
    LazyColumn {
        items(choices) { typeface ->
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.updateSettings { it.copy(textTypeface = typeface) } }.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = settings.textTypeface == typeface, onClick = null)
                Text(
                    when (typeface) {
                        TextTypeface.Mincho -> stringResource(R.string.text_typeface_mincho)
                        TextTypeface.Gothic -> stringResource(R.string.text_typeface_gothic)
                        is TextTypeface.Imported -> typeface.fileName
                    },
                    Modifier.padding(12.dp),
                )
            }
        }
        pick?.let {
            item {
                TextButton(onClick = it, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.text_typeface_import)) }
            }
        }
    }
}

/** Starts the import of a font into [store], which then selects the font it imported. */
@Composable
private fun rememberFontPick(store: FontStore, viewModel: TextReaderViewModel, onImported: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val notAFont = stringResource(R.string.text_font_not_a_font)
    val noRoom = stringResource(R.string.text_font_no_room)
    val unreadable = stringResource(R.string.text_font_unreadable)
    return rememberFontImport(store) { result ->
        when (result) {
            is FontImport.Imported -> {
                viewModel.updateSettings { it.copy(textTypeface = TextTypeface.Imported(result.fileName)) }
                onImported()
            }
            FontImport.NotAFont -> Toast.makeText(context, notAFont, Toast.LENGTH_SHORT).show()
            FontImport.NoRoom -> Toast.makeText(context, noRoom, Toast.LENGTH_SHORT).show()
            FontImport.Unreadable -> Toast.makeText(context, unreadable, Toast.LENGTH_SHORT).show()
        }
    }
}

/** The page panel of a text: a slider through the book, right to left, and the two ends. */
@Composable
private fun PageJumpPanel(state: TextReaderUiState.Reading, viewModel: TextReaderViewModel) {
    val run = state.run ?: return
    val number = run.number(state.current)
    var fraction by remember(number) { mutableFloatStateOf(if (number.total > 1) (number.current - 1f) / (number.total - 1) else 0f) }
    Text(pageNumberText(number), Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleMedium)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        Slider(
            value = fraction,
            onValueChange = { fraction = it },
            onValueChangeFinished = { viewModel.goToFraction(fraction) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = viewModel::last) { Text(stringResource(R.string.text_last)) }
        TextButton(onClick = viewModel::first) { Text(stringResource(R.string.text_first)) }
    }
}
