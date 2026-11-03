package io.github.yuubiz.hirari.feature.library

import androidx.annotation.StringRes
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.hirari.core.model.LibrarySort
import io.github.yuubiz.hirari.core.model.LibraryView
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.ReadState
import io.github.yuubiz.hirari.core.model.StartAt
import io.github.yuubiz.hirari.core.ui.GlyphButton
import io.github.yuubiz.hirari.core.ui.LocalLibraryColors
import io.github.yuubiz.hirari.core.ui.chosen
import io.github.yuubiz.hirari.core.ui.decorative
import kotlinx.coroutines.launch

/** What the file list asks of the app. [openAncestor] goes up to the folder at that path of the same place. */
class FolderActions(
    val openBook: (OpenBookRequest) -> Unit,
    val openText: (TextAddress) -> Unit,
    val openArchive: (ArchiveKey) -> Unit,
    val openFolder: (FolderKey) -> Unit,
    /** Asks the launcher to pin [PlaceShortcut]; false when it cannot. */
    val pinShortcut: suspend (PlaceShortcut) -> Boolean,
    val openAncestor: (String) -> Unit,
    val close: () -> Unit,
)

/** [reveal] is the path of a row to scroll to when the list first shows (docs/spec.md section 4.3, 戻ったとき). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(model: FolderViewModel, actions: FolderActions, reveal: String? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    var filtering by rememberSaveable { mutableStateOf(false) }
    var sheetFor by remember { mutableStateOf<FileRow?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val gate = rememberLocalNetworkGate()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(resources.getString(notice.message()))
        model.dismissNotice()
    }

    fun open(row: FileRow, start: StartAt = StartAt.Saved) {
        when (val target = model.open(row, start)) {
            is FileTarget.Folder -> {
                filtering = false
                actions.openFolder(target.key)
            }
            is FileTarget.Book -> actions.openBook(target.request)
            is FileTarget.Text -> actions.openText(target.address)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Breadcrumbs(state.placeName, state.path, actions.openAncestor) },
                navigationIcon = { GlyphButton("←", stringResource(R.string.nav_back), actions.close) },
                actions = {
                    TextButton(onClick = {
                        filtering = !filtering
                        if (!filtering) model.setFilter("")
                    }) { Text(stringResource(R.string.folder_filter)) }
                    (state.content as? FolderContent.Shown)?.let { ListMenu(it, model) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val shown = state.content as? FolderContent.Shown
            if (filtering && shown != null) {
                OutlinedTextField(
                    value = shown.filter,
                    onValueChange = model::setFilter,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    label = { Text(stringResource(R.string.folder_filter)) },
                    placeholder = { Text(stringResource(R.string.folder_filter_hint)) },
                    singleLine = true,
                )
            }
            when (val content = state.content) {
                FolderContent.Loading -> Unit
                FolderContent.SlowLoading -> SlowLoading {
                    model.cancel()
                    actions.close()
                }
                is FolderContent.Failed -> ListFailed(content.reason, onRetry = { if (content.reason == ListFailure.NoLocalNetwork) gate(model::load) else model.load() })
                is FolderContent.Shown -> if (content.rows.isEmpty()) {
                    EmptyNote(if (content.total == 0) R.string.folder_empty else R.string.folder_no_match)
                } else {
                    FileList(content, reveal, onOpen = ::open, onLongPress = { sheetFor = it })
                }
            }
        }
    }
    sheetFor?.let { row ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            RowSheet(row, hasShortcut = model.shortcut(row) != null, folderCoverChosen = (state.content as? FolderContent.Shown)?.folderCoverPath == row.path, canChooseCover = state.path.isNotEmpty()) { action ->
                sheetFor = null
                when (action) {
                    SheetAction.Open -> open(row)
                    SheetAction.Contents -> actions.openArchive(ArchiveKey(model.folder.location, row.path, row.item.entry.documentId))
                    SheetAction.FromStart -> open(row, StartAt.Page(0))
                    SheetAction.MarkRead -> model.mark(row, ReadState.Read)
                    SheetAction.MarkUnread -> model.mark(row, ReadState.Unread)
                    SheetAction.Pin -> model.pin(row)
                    SheetAction.Remake -> model.remake(row)
                    SheetAction.FolderCover -> model.chooseFolderCover(row, choose = (state.content as? FolderContent.Shown)?.folderCoverPath != row.path)
                    SheetAction.Shortcut -> model.shortcut(row)?.let { shortcut ->
                        scope.launch { if (!actions.pinShortcut(shortcut)) model.show(Notice.ShortcutUnsupported) }
                    }
                }
            }
        }
    }
}

/** The path with each folder a step up (docs/spec.md section 4.3, 上部バー). */
@Composable
private fun Breadcrumbs(placeName: String, path: String, onPick: (String) -> Unit) {
    val names = path.split('/').filter { it.isNotEmpty() }
    Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        val steps = listOf(placeName to "") + names.mapIndexed { i, name -> name to names.take(i + 1).joinToString("/") }
        steps.forEachIndexed { i, (name, at) ->
            if (i > 0) Text("/", Modifier.decorative(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = i == steps.lastIndex
            TextButton(onClick = { onPick(at) }, enabled = !last) {
                Text(name, maxLines = 1, fontWeight = if (last) FontWeight.Bold else null)
            }
        }
    }
}

@Composable
private fun ListMenu(shown: FolderContent.Shown, model: FolderViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        GlyphButton("⋮", stringResource(R.string.list_menu), { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeader(R.string.folder_sort)
            LibrarySort.entries.forEach { sort ->
                Choice(sort.label(), sort == shown.sort) {
                    open = false
                    model.setSort(sort)
                }
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folder_folders_first)) },
                leadingIcon = { Checkbox(checked = shown.foldersFirst, onCheckedChange = null, modifier = Modifier.decorative()) },
                modifier = Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(shown.foldersFirst)
                },
                onClick = { model.setFoldersFirst(!shown.foldersFirst) },
            )
            HorizontalDivider()
            MenuHeader(R.string.folder_view)
            LibraryView.entries.forEach { view ->
                Choice(view.label(), view == shown.view) {
                    open = false
                    model.setView(view)
                }
            }
        }
    }
}

@Composable
private fun MenuHeader(@StringRes text: Int) {
    Text(
        stringResource(text),
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun Choice(@StringRes label: Int, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label), fontWeight = if (selected) FontWeight.Bold else null) },
        leadingIcon = { Text(if (selected) "✓" else " ", Modifier.decorative()) },
        modifier = Modifier.chosen(selected),
        onClick = onClick,
    )
}

@Composable
private fun SlowLoading(onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        val loading = stringResource(R.string.loading)
        CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
        OutlinedButton(onClick = onCancel, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.cancel)) }
    }
}

@Composable
private fun ListFailed(reason: ListFailure, onRetry: () -> Unit) {
    val message = when (reason) {
        ListFailure.NoPermission -> R.string.folder_failed_permission
        ListFailure.NotFound -> R.string.folder_failed_missing
        ListFailure.ReadError -> R.string.folder_failed_read
        ListFailure.Unreachable -> R.string.folder_failed_unreachable
        ListFailure.AuthenticationFailed -> R.string.folder_failed_auth
        ListFailure.PasswordUnreadable -> R.string.folder_failed_password_unreadable
        ListFailure.NoLocalNetwork -> R.string.folder_failed_local_network
    }
    val retry = if (reason == ListFailure.NoLocalNetwork) R.string.folder_allow_local_network else R.string.folder_retry
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(message), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(retry)) }
    }
}

internal enum class SheetAction { Open, Contents, FromStart, MarkRead, MarkUnread, Pin, Shortcut, Remake, FolderCover }

/** The long-press sheet; an action that does not fit the row is left out (docs/spec.md section 4.3). */
@Composable
internal fun RowSheet(row: FileRow, hasShortcut: Boolean, folderCoverChosen: Boolean, canChooseCover: Boolean, onAction: (SheetAction) -> Unit) {
    val actions = buildList {
        add(SheetAction.Open to R.string.sheet_open)
        if (row.item is FileItem.Archive && row.item.kind != ArchiveKind.Pdf) add(SheetAction.Contents to R.string.sheet_contents)
        if (row.item is FileItem.Archive && row.mark != null && row.mark != ReadMark.Unread) add(SheetAction.FromStart to R.string.sheet_from_start)
        if (row.item is FileItem.Archive || row.item is FileItem.Text) {
            if (row.mark != ReadMark.Read) add(SheetAction.MarkRead to R.string.sheet_mark_read)
            if (row.mark != ReadMark.Unread) add(SheetAction.MarkUnread to R.string.sheet_mark_unread)
        }
        if (row.item is FileItem.Folder) add(SheetAction.Pin to R.string.sheet_pin)
        if (row.item is FileItem.Image && canChooseCover) {
            add(SheetAction.FolderCover to if (folderCoverChosen) R.string.sheet_folder_cover_off else R.string.sheet_folder_cover)
        }
        if (row.cover != null) add(SheetAction.Remake to R.string.sheet_remake)
        if (hasShortcut) add(SheetAction.Shortcut to R.string.sheet_shortcut)
    }
    SheetActions(row.item.entry.name) {
        actions.forEach { (action, label) ->
            ListItem(
                headlineContent = { Text(stringResource(label)) },
                modifier = Modifier.clickable(role = Role.Button) { onAction(action) },
            )
        }
    }
}

/**
 * What a long press opens: a title, then the actions. The column scrolls, so the actions stay reachable at a large
 * font and in a short window (docs/spec.md section 6.7).
 */
@Composable
internal fun SheetActions(title: String, actions: @Composable () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(
            title,
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        actions()
    }
}

/**
 * The pressed background of docs/spec.md section 4.3; focus and Enter come with the click (方向キー). A screen reader
 * lists the long press as an action named メニューを開く.
 */
@Composable
internal fun Modifier.rowPress(onClick: () -> Unit, onLongClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colors = LocalLibraryColors.current
    return this
        .then(if (pressed) Modifier.background(colors.itemPressed) else Modifier)
        .combinedClickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
            onLongClickLabel = stringResource(R.string.row_menu),
            onClick = onClick,
            onLongClick = onLongClick,
        )
}
