package io.github.yuubiz.hirari.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.stateDescription
import io.github.yuubiz.hirari.core.model.LayoutMode
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Rotation
import io.github.yuubiz.hirari.core.model.ScaleMode
import io.github.yuubiz.hirari.core.model.TrimStrength
import io.github.yuubiz.hirari.core.archive.EntryNames
import java.nio.charset.Charset
import kotlin.math.roundToInt
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
internal fun AddBookmarkSheet(page: Int, pageCount: Int, onAdd: (String) -> Unit, onClose: () -> Unit) {
    val default = defaultBookmarkLabel(bookmarkAt(page, pageCount))
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

/**
 * 本ごとの設定 (docs/spec.md section 4.8, メニュー). Each change shows at once and is kept as this book's override
 * (section 2.9); 画面の明るさ is the global setting, since the book has no override of it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BookSettingsSheet(
    viewModel: ReaderViewModel,
    state: ReaderState,
    sheet: ReaderSheet.BookSettings,
    onMessage: (Int) -> Unit,
    onEffect: (CommandEffect?) -> Unit,
    onClose: () -> Unit,
) {
    val config = state.config
    fun set(next: ReaderConfig) = viewModel.dispatch(ReaderEvent.ConfigChanged(next))
    BottomPanel(stringResource(R.string.command_book_settings), onClose) {
        OptionRow(R.string.command_direction, ReadingDirection.entries, config.direction, { it.label }) { set(config.copy(layout = config.layout.copy(direction = it))) }
        OptionRow(R.string.command_layout, LayoutMode.entries, config.layout.mode, { it.label }) { set(config.copy(layout = config.layout.copy(mode = it))) }
        SwitchRow(R.string.book_cover_alone, config.layout.coverAlone) { set(config.copy(layout = config.layout.copy(coverAlone = it))) }
        OptionRow(R.string.command_scale_mode, ScaleMode.entries, config.scaleMode, { it.label }) { set(config.copy(scaleMode = it)) }
        OptionRow(R.string.command_trim, TrimStrength.entries, config.trim, { it.label }) { set(config.copy(trim = it)) }
        OptionRow(R.string.command_rotate, Rotation.entries, config.rotation, { R.string.rotation_degrees }, { arrayOf(it.degrees) }) { set(config.copy(rotation = it)) }
        SwitchRow(R.string.command_reverse_order, viewModel.reversed) { reversed -> onEffect(viewModel.reorder { it.reversedTo(reversed) }) }
        if (viewModel.namesHaveCharset) {
            val charsets = listOf<Charset?>(null) + EntryNames.choices
            CharsetRow(charsets, viewModel.nameCharset) { charset -> onEffect(viewModel.reorder { it.copy(nameCharset = charset) }) }
        }
        SectionTitle(R.string.command_image_adjust)
        AdjustControls(config.adjust) { set(config.copy(adjust = it)) }
        BrightnessControls(config.brightness, viewModel::setBrightness)
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            val opened = sheet.opened
            SheetButton(R.string.book_settings_undo, enabled = config.withBookValues(opened) != config || config.brightness != opened.brightness) {
                set(config.withBookValues(opened))
                if (config.brightness != opened.brightness) viewModel.setBrightness(opened.brightness)
            }
            val global = viewModel.globalConfig
            SheetButton(R.string.adjust_global, enabled = global != null && config.withBookValues(global) != config) { global?.let { set(config.withBookValues(it)) } }
            SheetButton(R.string.book_settings_make_default) {
                viewModel.makeDefault { stored -> onMessage(if (stored) R.string.book_settings_default_done else R.string.book_settings_save_failed) }
            }
            SheetButton(R.string.panel_close, onClick = onClose)
        }
    }
}

/** 文字コード: 自動判定, or one of the charsets a user may choose (docs/spec.md section 3.4). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharsetRow(charsets: List<Charset?>, selected: Charset?, onSelect: (Charset?) -> Unit) {
    SectionTitle(R.string.charset)
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (charset in charsets) {
            Chip(charset?.let(::charsetName) ?: stringResource(R.string.charset_detect), on = charset == selected) { onSelect(charset) }
        }
    }
}

private fun charsetName(charset: Charset): String = if (charset == EntryNames.CP932) "Shift_JIS (CP932)" else charset.name()

@Composable
private fun SectionTitle(label: Int) {
    Text(stringResource(label), color = SheetText, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
}

/** One choice among [options], each a chip a screen reader reads as a radio button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> OptionRow(label: Int, options: List<T>, selected: T, name: (T) -> Int, args: (T) -> Array<Any> = { emptyArray() }, onSelect: (T) -> Unit) {
    SectionTitle(label)
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in options) {
            Chip(stringResource(name(option), *args(option)), on = option == selected) { onSelect(option) }
        }
    }
}

/** A choice read by a screen reader as a radio button, with a touch target of 48 dp. */
@Composable
private fun Chip(text: String, on: Boolean, onSelect: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.selectable(selected = on, role = Role.RadioButton, onClick = onSelect).sizeIn(minWidth = 48.dp, minHeight = 48.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = SheetText,
            modifier = Modifier
                .background(if (on) SelectedChip else Color.Transparent, shape)
                .border(1.dp, if (on) SheetText else SheetDisabled, shape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private val SelectedChip = Color(0x40FFFFFF)

/** 画面の明るさ: the device's own, or a fixed percent in steps of [BRIGHTNESS_STEPS]. */
@Composable
private fun BrightnessControls(brightness: Int?, set: (Int?) -> Unit) {
    SectionTitle(R.string.brightness)
    SwitchRow(R.string.brightness_device, brightness == null) { device -> set(if (device) null else DEFAULT_BRIGHTNESS) }
    if (brightness == null) return
    val label = stringResource(R.string.brightness)
    val shown = stringResource(R.string.brightness_percent, brightness)
    Text(shown, color = SheetDisabled, modifier = Modifier.padding(horizontal = 20.dp).decorative())
    Slider(
        value = brightness.toFloat(),
        onValueChange = { value ->
            val step = BRIGHTNESS_STEPS.step
            val snapped = ((value / step).roundToInt() * step).coerceIn(BRIGHTNESS_STEPS.first, BRIGHTNESS_STEPS.last)
            if (snapped != brightness) set(snapped)
        },
        valueRange = BRIGHTNESS_STEPS.first.toFloat()..BRIGHTNESS_STEPS.last.toFloat(),
        steps = (BRIGHTNESS_STEPS.last - BRIGHTNESS_STEPS.first) / BRIGHTNESS_STEPS.step - 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).semantics {
            contentDescription = label
            stateDescription = shown
        },
    )
}

private const val DEFAULT_BRIGHTNESS = 50

private val ReadingDirection.label: Int
    get() = when (this) {
        ReadingDirection.RightToLeft -> R.string.direction_right_to_left
        ReadingDirection.LeftToRight -> R.string.direction_left_to_right
    }

private val LayoutMode.label: Int
    get() = when (this) {
        LayoutMode.Single -> R.string.layout_single
        LayoutMode.Spread -> R.string.layout_spread
        LayoutMode.SplitWide -> R.string.layout_split_wide
        LayoutMode.Auto -> R.string.layout_auto
        LayoutMode.Vertical -> R.string.layout_vertical
    }

private val ScaleMode.label: Int
    get() = when (this) {
        ScaleMode.FitScreen -> R.string.scale_fit_screen
        ScaleMode.FitWidth -> R.string.scale_fit_width
        ScaleMode.FitHeight -> R.string.scale_fit_height
        ScaleMode.Original -> R.string.scale_original
        ScaleMode.Stretch -> R.string.scale_stretch
    }

private val TrimStrength.label: Int
    get() = when (this) {
        TrimStrength.Off -> R.string.trim_off
        TrimStrength.Weak -> R.string.trim_weak
        TrimStrength.Medium -> R.string.trim_medium
        TrimStrength.Strong -> R.string.trim_strong
        TrimStrength.Strongest -> R.string.trim_strongest
    }
