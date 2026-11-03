package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.defaultBookmarkLabel
import io.github.yuubiz.hirari.core.ui.blockTouches
import io.github.yuubiz.hirari.core.ui.decorative
import io.github.yuubiz.hirari.core.ui.minTouchTarget

/** A sheet from the bottom as wide as the menu (docs/spec.md section 4.8, メニュー), headed [title]. */
@Composable
internal fun BottomPanel(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Scrim(title, onClose) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(menuWidthDp(maxWidth.value, maxHeight.value).dp)
                    .background(SheetBackground, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .blockTouches()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                Text(title, color = SheetText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp).semantics { heading() })
                content()
            }
        }
    }
}

/** しおりを追加 (docs/spec.md section 4.8): the name starts as the default one, and a blank name is the default too. */
@Composable
internal fun AddBookmarkSheet(page: Int, onAdd: (String) -> Unit, onClose: () -> Unit) {
    val default = defaultBookmarkLabel(bookmarkAt(page, page + 1))
    var typed by rememberSaveable(page) { mutableStateOf(default) }
    BottomPanel(stringResource(R.string.command_add_bookmark), onClose) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            singleLine = true,
            label = { Text(stringResource(R.string.bookmark_name)) },
            placeholder = { Text(default) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd(typed) }),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = SheetText),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            SheetButton(R.string.bookmark_cancel, onClick = onClose)
            SheetButton(R.string.bookmark_add) { onAdd(typed) }
        }
    }
}

/** しおり一覧 (docs/spec.md section 4.8): a tap goes to the bookmark, 削除 removes it. */
@Composable
internal fun BookmarksSheet(viewModel: ReaderViewModel, onAdd: () -> Unit, onClose: () -> Unit) {
    val flow = remember(viewModel) { viewModel.bookmarks() }
    val marks by flow.collectAsState(null)
    BottomPanel(stringResource(R.string.command_bookmarks), onClose) {
        val shown = marks
        if (shown != null && shown.isEmpty()) {
            Text(stringResource(R.string.bookmarks_empty), color = SheetDisabled, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
        for (mark in shown.orEmpty()) {
            val line = if (mark.showsPage) stringResource(R.string.bookmark_line, mark.label, defaultBookmarkLabel(mark.position)) else mark.label
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    line,
                    color = SheetText,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(role = Role.Button) {
                            viewModel.goTo(mark)
                            onClose()
                        }
                        .minTouchTarget()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
                val delete = stringResource(R.string.a11y_bookmark_delete, mark.label)
                TextButton(onClick = { viewModel.deleteBookmark(mark) }, modifier = Modifier.semantics { contentDescription = delete }) {
                    Text(stringResource(R.string.bookmark_delete), Modifier.decorative(), color = SheetText)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            SheetButton(R.string.command_add_bookmark, onClick = onAdd)
            SheetButton(R.string.panel_close, onClick = onClose)
        }
    }
}
