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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import io.github.yuubiz.hirari.core.data.FontImport
import io.github.yuubiz.hirari.core.data.FontStore
import io.github.yuubiz.hirari.core.data.GlobalSettings
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
 * The panels over the reader. [commandBounds] collects where each command sits on screen, so a finger that pressed
 * the command bar and slid onto a command runs it on release (docs/spec.md section 4.8, コマンドバー).
 */
@Composable
internal fun TextPanels(
    panel: TextPanel?,
    state: TextReaderUiState.Reading,
    viewModel: TextReaderViewModel,
    commandBounds: MutableMap<TextCommand, Rect>,
    onPanel: (TextPanel?) -> Unit,
    onClose: () -> Unit,
    onCommand: (TextCommand) -> Unit,
) {
    when (panel) {
        null -> Unit
        TextPanel.Commands -> Commands(commandBounds, onDismiss = { onPanel(null) }, onClose = onClose, onCommand = onCommand)
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
private fun Commands(bounds: MutableMap<TextCommand, Rect>, onDismiss: () -> Unit, onClose: () -> Unit, onCommand: (TextCommand) -> Unit) {
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss)) {
        Column(Modifier.fillMaxWidth().background(PANEL_BACKGROUND).clickable(enabled = false) {}.padding(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PanelButton(R.string.text_close, onClose)
                PanelButton(R.string.text_command_settings) { onCommand(TextCommand.Settings) }
            }
            TextCommand.entries.filter { it != TextCommand.Settings }.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    row.forEach { command ->
                        Box(
                            Modifier.weight(1f).heightIn(min = 56.dp)
                                .onGloballyPositioned { bounds[command] = it.boundsInRoot() }
                                .clickable { onCommand(command) },
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

@Composable
private fun PanelButton(label: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(stringResource(label), color = Color.White) }
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
    choices.forEach { typeface ->
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
    store?.let { FontImportButton(it, viewModel) { refresh++ } }
}

@Composable
private fun FontImportButton(store: FontStore, viewModel: TextReaderViewModel, onImported: () -> Unit) {
    val context = LocalContext.current
    val notAFont = stringResource(R.string.text_font_not_a_font)
    val noRoom = stringResource(R.string.text_font_no_room)
    val unreadable = stringResource(R.string.text_font_unreadable)
    val pick = rememberFontImport(store) { result ->
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
    TextButton(onClick = pick, modifier = Modifier.padding(horizontal = 16.dp)) { Text(stringResource(R.string.text_typeface_import)) }
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
