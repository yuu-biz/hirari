package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.ui.GlyphButton
import io.github.yuubiz.hirari.core.ui.LocalLibraryColors
import io.github.yuubiz.hirari.core.ui.chosen
import io.github.yuubiz.hirari.core.ui.decorative

/** What the archive list asks of the app. */
class ArchiveActions(
    val openBook: (OpenBookRequest) -> Unit,
    val openText: (TextAddress) -> Unit,
    val chooseRange: (CoverRangeKey) -> Unit,
    val close: () -> Unit,
)

private sealed interface Held {
    data class Page(val page: ArchivePage) : Held

    data class Text(val row: TextRow) : Held
}

/** 書庫内一覧 (docs/spec.md section 4.7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(model: ArchiveViewModel, actions: ArchiveActions) {
    val state by model.state.collectAsStateWithLifecycle()
    var list by rememberSaveable { mutableStateOf(false) }
    var held by remember { mutableStateOf<Held?>(null) }

    fun open(target: ArchiveTarget?) {
        when (target) {
            is ArchiveTarget.Page -> actions.openBook(target.request)
            is ArchiveTarget.Text -> actions.openText(target.address)
            null -> Unit
        }
    }

    val shown = state as? ArchiveUi.Shown
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(shown?.title ?: model.book.path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { GlyphButton("←", stringResource(R.string.nav_back), actions.close) },
                actions = {
                    val target = stringResource(if (list) R.string.view_grid else R.string.view_list)
                    val viewAs = stringResource(R.string.archive_view_as, target)
                    TextButton(onClick = { list = !list }, modifier = Modifier.semantics { contentDescription = viewAs }) {
                        Text(target, Modifier.decorative())
                    }
                    shown?.let { CharsetMenu(it, model::chooseCharset) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val content = state) {
                ArchiveUi.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ArchiveUi.Failed -> EmptyNote(content.reason.message())
                is ArchiveUi.Shown -> if (content.texts.isEmpty() && content.pages.isEmpty()) {
                    EmptyNote(R.string.archive_empty)
                } else {
                    Entries(
                        content,
                        list,
                        onPage = { open(model.open(it)) },
                        onText = { open(model.open(it)) },
                        onHold = { held = it },
                    )
                }
            }
        }
    }
    held?.let { item ->
        ModalBottomSheet(onDismissRequest = { held = null }) {
            fun action(label: Int, run: () -> Unit) = label to run
            val (name, choices) = when (item) {
                is Held.Page -> item.page.name to listOfNotNull(
                    action(R.string.archive_read_up_to) { model.readUpTo(item.page) },
                    if (shown?.indexed == true) action(R.string.archive_set_cover) { model.setCover(item.page) } else null,
                    if (shown?.indexed == true) action(R.string.archive_set_cover_range) { model.rangeKey(item.page)?.let(actions.chooseRange) } else null,
                    if (shown?.inFolder == true) {
                        val chosen = shown.folderCoverPage == item.page.entryIndex
                        action(if (chosen) R.string.archive_folder_cover_off else R.string.archive_folder_cover) { model.chooseFolderCover(item.page, choose = !chosen) }
                    } else {
                        null
                    },
                )
                is Held.Text -> item.row.text.name to listOf(
                    if (item.row.mark == ReadMark.Read) {
                        action(R.string.sheet_mark_unread) { model.mark(item.row.text, ReadState.Unread) }
                    } else {
                        action(R.string.sheet_mark_read) { model.mark(item.row.text, ReadState.Read) }
                    },
                )
            }
            SheetActions(name) {
                choices.forEach { (label, run) ->
                    ListItem(
                        headlineContent = { Text(stringResource(label)) },
                        modifier = Modifier.clickable(role = Role.Button) {
                            held = null
                            run()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Entries(content: ArchiveUi.Shown, list: Boolean, onPage: (ArchivePage) -> Unit, onText: (ArchiveText) -> Unit, onHold: (Held) -> Unit) {
    val colors = LocalLibraryColors.current
    val grid = rememberLazyGridState()
    val pageCovers = content.covers
    val focus = remember { CoverFocus() }
    if (pageCovers != null && !list) {
        val refs = remember(content.pages, pageCovers) { content.pages.map { pageCovers.ref(it.page) } }
        val before = content.texts.size + if (content.texts.isNotEmpty() && content.pages.isNotEmpty()) 1 else 0
        PrefetchCovers(refs, pageCovers.layout.covers, focus, { grid.isScrollInProgress }) {
            val shown = grid.layoutInfo.visibleItemsInfo
            if (shown.isEmpty()) IntRange.EMPTY else maxOf(shown.first().index - before, 0)..(shown.last().index - before)
        }
    }
    LazyVerticalGrid(GridCells.Adaptive(if (list) 10_000.dp else 104.dp), Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(8.dp)) {
        items(content.texts, key = { "text-${it.text.entryIndex}" }, span = { GridItemSpan(maxLineSpan) }) { row ->
            val color = when (row.mark) {
                ReadMark.Read -> colors.stateRead
                is ReadMark.Reading -> colors.stateReading
                ReadMark.Unread -> colors.stateUnread
            }
            ListItem(
                headlineContent = { Text(row.text.name, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(listOfNotNull(stringResource(row.mark.label()), row.text.size?.let(::sizeText)).joinToString("  "), color = colors.itemDetail)
                },
                leadingContent = { CoverSlot(CoverBadge.Label("TXT"), Modifier.padding(4.dp).aspectRatio(0.7f).fillMaxWidth(0.12f)) },
                modifier = Modifier.rowPress(onClick = { onText(row.text) }, onLongClick = { onHold(Held.Text(row)) }),
            )
        }
        if (content.texts.isNotEmpty() && content.pages.isNotEmpty()) {
            item(key = "divider", span = { GridItemSpan(maxLineSpan) }) { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        }
        itemsIndexed(content.pages, key = { _, it -> "page-${it.page.entryIndex}" }) { index, row ->
            val page = row.page
            val number = (index + 1).toString()
            if (list) {
                ListItem(
                    headlineContent = { Text(page.name, color = if (row.read) colors.stateRead else colors.stateUnread, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(listOfNotNull(number, page.size?.let(::sizeText), if (page.unreadable) stringResource(R.string.archive_unreadable) else null).joinToString("  "), color = colors.itemDetail)
                    },
                    modifier = Modifier.rowPress(onClick = { onPage(page) }, onLongClick = { onHold(Held.Page(page)) }),
                )
            } else {
                val spokenPage = stringResource(R.string.archive_page, index + 1)
                val spokenState = spokenList(
                    stringResource(if (row.read) R.string.mark_read else R.string.mark_unread),
                    if (page.unreadable) stringResource(R.string.archive_unreadable) else null,
                )
                Column(
                    Modifier
                        .padding(4.dp)
                        .rowPress(onClick = { onPage(page) }, onLongClick = { onHold(Held.Page(page)) })
                        .semantics {
                            contentDescription = spokenPage
                            spokenState?.let { stateDescription = it }
                        },
                ) {
                    val label = if (page.unreadable) CoverBadge.Label("!") else null
                    val pageCovers = content.covers
                    if (pageCovers == null) {
                        CoverSlot(label, Modifier.fillMaxWidth().aspectRatio(COVER_RATIO), faded = row.read)
                    } else {
                        CoverBox(pageCovers.ref(page), pageCovers.layout.covers, label, Modifier.fillMaxWidth().aspectRatio(COVER_RATIO).decorative(), faded = row.read, focus = focus)
                    }
                    Text(
                        number,
                        Modifier.fillMaxWidth().decorative(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (row.read) colors.stateRead else colors.stateUnread,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CharsetMenu(shown: ArchiveUi.Shown, onChoose: (java.nio.charset.Charset?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text(stringResource(R.string.archive_charset)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.archive_charset_auto), fontWeight = if (shown.chosen == null) FontWeight.Bold else null) },
                modifier = Modifier.chosen(shown.chosen == null),
                onClick = {
                    open = false
                    onChoose(null)
                },
            )
            NAME_CHARSETS.forEach { (charset, label) ->
                DropdownMenuItem(
                    text = { Text(label, fontWeight = if (shown.chosen == charset) FontWeight.Bold else null) },
                    leadingIcon = { Text(if (shown.charset == charset) "✓" else " ", Modifier.decorative()) },
                    modifier = Modifier.chosen(shown.charset == charset),
                    onClick = {
                        open = false
                        onChoose(charset)
                    },
                )
            }
        }
    }
}

private fun ReadMark.label(): Int = when (this) {
    ReadMark.Read -> R.string.mark_read
    is ReadMark.Reading -> R.string.mark_reading
    ReadMark.Unread -> R.string.mark_unread
}

private fun ArchiveFailure.message(): Int = when (this) {
    ArchiveFailure.NotFound -> R.string.archive_failed_missing
    ArchiveFailure.NoPermission -> R.string.folder_failed_permission
    ArchiveFailure.Encrypted -> R.string.archive_failed_encrypted
    ArchiveFailure.Unreachable -> R.string.folder_failed_unreachable
    ArchiveFailure.Unreadable -> R.string.archive_failed_read
}
