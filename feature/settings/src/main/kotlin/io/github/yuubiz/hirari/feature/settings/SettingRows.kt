package io.github.yuubiz.hirari.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.ReaderCommand
import io.github.yuubiz.hirari.core.model.Rgb
import io.github.yuubiz.hirari.core.model.TextTypeface

/**
 * Draws the row of [setting]. Rows that need a choice open their dialog through [onOpen]; the others edit in place.
 * [saveFailures] counts the saves that failed, so a slider can go back to the value that is stored.
 */
@Composable
internal fun SettingRow(
    setting: Setting,
    settings: GlobalSettings,
    saveFailures: Int,
    onChange: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onOpen: (String) -> Unit,
) {
    when (setting) {
        is ToggleSetting -> ToggleRow(setting, settings, onChange)
        is SliderSetting<*> -> SliderRow(setting, settings, saveFailures, onChange)
        is ChoiceSetting<*> -> ChoiceRow(setting, settings, onOpen)
        is ColorSetting<*> -> ColorRow(setting, settings, onOpen)
        is OrderedSetSetting -> CommandsRow(setting, settings, onOpen)
    }
}

/** The dialog of the choice, color or command list [setting]; nothing for a row that edits in place. */
@Composable
internal fun SettingDialog(
    setting: Setting,
    settings: GlobalSettings,
    importedFonts: List<String>,
    onImportFont: () -> Unit,
    onChange: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onClose: () -> Unit,
) {
    when (setting) {
        TypefaceSetting -> TypefaceDialog(importedFonts, settings, onImportFont, onChange, onClose)
        is ChoiceSetting<*> -> ChoiceDialog(setting, settings, onChange, onClose)
        is ColorSetting<*> -> ColorDialog(setting, settings, onChange, onClose)
        is OrderedSetSetting -> CommandsDialog(setting, settings, onChange, onClose)
        is ToggleSetting, is SliderSetting<*> -> onClose()
    }
}

@Composable
private fun ToggleRow(setting: ToggleSetting, settings: GlobalSettings, onChange: ((GlobalSettings) -> GlobalSettings) -> Unit) {
    val checked = setting.read(settings)
    ListItem(
        headlineContent = { Text(setting.label) },
        supportingContent = setting.note?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch) { value -> onChange { setting.write(it, value) } },
    )
}

@Composable
private fun <T> ChoiceRow(setting: ChoiceSetting<T>, settings: GlobalSettings, onOpen: (String) -> Unit) {
    SummaryRow(setting, setting.describe(setting.read(settings)), onOpen)
}

@Composable
private fun SummaryRow(setting: Setting, summary: String, onOpen: (String) -> Unit, leading: (@Composable () -> Unit)? = null) {
    ListItem(
        headlineContent = { Text(setting.label) },
        supportingContent = {
            Column {
                Text(summary)
                setting.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = leading,
        modifier = Modifier.clickable(role = Role.Button) { onOpen(setting.key) },
    )
}

@Composable
private fun <T> SliderRow(setting: SliderSetting<T>, settings: GlobalSettings, saveFailures: Int, onChange: ((GlobalSettings) -> GlobalSettings) -> Unit) {
    val values = setting.values(settings)
    val current = setting.read(settings)
    val stored = values.indexOf(current).coerceAtLeast(0)
    val draft = remember(stored, values.size, saveFailures) { SliderDraft(values.size) }
    val shown = draft.shown(stored)
    val text = setting.format(values[shown])
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(setting.label, style = MaterialTheme.typography.bodyLarge)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        setting.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Slider(
            value = shown.toFloat(),
            onValueChange = draft::drag,
            onValueChangeFinished = {
                val target = values[draft.release(stored)]
                if (target != current) onChange { setting.write(it, target) }
            },
            valueRange = 0f..values.lastIndex.toFloat().coerceAtLeast(1f),
            steps = (values.size - 2).coerceAtLeast(0),
            enabled = values.size > 1,
            modifier = Modifier.semantics {
                contentDescription = setting.label
                stateDescription = text
            },
        )
    }
}

@Composable
private fun <T> ColorRow(setting: ColorSetting<T>, settings: GlobalSettings, onOpen: (String) -> Unit) {
    val value = setting.read(settings)
    val swatch = setting.customOf(value)
    SummaryRow(setting, setting.describe(value), onOpen, leading = swatch?.let { { ColorSwatch(it) } })
}

@Composable
private fun ColorSwatch(color: Rgb) {
    Box(
        Modifier
            .size(24.dp)
            .background(Color(0xFF000000.toInt() or color.value), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

@Composable
private fun CommandsRow(setting: OrderedSetSetting, settings: GlobalSettings, onOpen: (String) -> Unit) {
    val items = setting.read(settings)
    SummaryRow(setting, items.joinToString("、") { setting.labelOf(it) }.ifEmpty { stringResource(R.string.settings_command_empty) }, onOpen)
}

/** 本文の書体: the system typefaces, the imported fonts, and a button to import another. */
@Composable
private fun TypefaceDialog(
    importedFonts: List<String>,
    settings: GlobalSettings,
    onImportFont: () -> Unit,
    onChange: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onClose: () -> Unit,
) {
    val setting = TypefaceSetting.plusOptions(importedFonts.map { Option(TextTypeface.Imported(it), it) })
    ChoiceDialog(setting, settings, onChange, onClose) {
        TextButton(
            onClick = {
                onClose()
                onImportFont()
            },
        ) { Text(stringResource(R.string.settings_import_font)) }
    }
}

@Composable
private fun <T> ChoiceDialog(
    setting: ChoiceSetting<T>,
    settings: GlobalSettings,
    onChange: ((GlobalSettings) -> GlobalSettings) -> Unit,
    onClose: () -> Unit,
    extraButton: (@Composable () -> Unit)? = null,
) {
    val current = setting.read(settings)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(setting.label) },
        text = {
            LazyColumn {
                itemsIndexed(setting.options, key = { index, _ -> index }) { _, option ->
                    RadioRow(option.label, option.value == current) {
                        onChange { setting.write(it, option.value) }
                        onClose()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.settings_close)) } },
        dismissButton = extraButton,
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 16.dp))
        Text(label)
    }
}

@Composable
private fun <T> ColorDialog(setting: ColorSetting<T>, settings: GlobalSettings, onChange: ((GlobalSettings) -> GlobalSettings) -> Unit, onClose: () -> Unit) {
    val current = setting.read(settings)
    var text by rememberSaveable { mutableStateOf(setting.customOf(current)?.let(::hexOf) ?: "#") }
    var typing by rememberSaveable { mutableStateOf(setting.customOf(current) != null) }
    val parsed = parseHex(text)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(setting.label) },
        text = {
            LazyColumn {
                items(setting.presets, key = { it.label }) { option ->
                    RadioRow(option.label, !typing && option.value == current) {
                        onChange { setting.write(it, option.value) }
                        onClose()
                    }
                }
                item(key = "custom") {
                    Column {
                        RadioRow(stringResource(R.string.settings_custom_color), typing) { typing = true }
                        if (typing) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                label = { Text(stringResource(R.string.settings_custom_color_label)) },
                                supportingText = { Text(stringResource(R.string.settings_custom_color_hint)) },
                                isError = parsed == null,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (typing) {
                TextButton(
                    enabled = parsed != null,
                    onClick = {
                        parsed?.let { color -> onChange { setting.write(it, setting.custom(color)) } }
                        onClose()
                    },
                ) { Text(stringResource(R.string.settings_apply)) }
            } else {
                TextButton(onClick = onClose) { Text(stringResource(R.string.settings_close)) }
            }
        },
        dismissButton = if (typing) {
            { TextButton(onClick = onClose) { Text(stringResource(R.string.settings_cancel)) } }
        } else {
            null
        },
    )
}

@Composable
private fun CommandsDialog(setting: OrderedSetSetting, settings: GlobalSettings, onChange: ((GlobalSettings) -> GlobalSettings) -> Unit, onClose: () -> Unit) {
    val selected = setting.read(settings)
    val available = setting.choices.map { it.value }.filter { it !in selected }
    fun edit(transform: (List<ReaderCommand>) -> List<ReaderCommand>) = onChange { setting.write(it, transform(setting.read(it))) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(setting.label) },
        text = {
            LazyColumn {
                item(key = "selected-title") { SectionTitle(stringResource(R.string.settings_command_selected) + " (${selected.size}/${setting.max})") }
                if (selected.isEmpty()) item(key = "selected-empty") { Text(stringResource(R.string.settings_command_empty)) }
                items(selected, key = { "selected-${it.name}" }) { command ->
                    val name = setting.labelOf(command)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f))
                        val up = stringResource(R.string.settings_command_up, name)
                        val down = stringResource(R.string.settings_command_down, name)
                        val remove = stringResource(R.string.settings_command_remove, name)
                        TextButton(
                            enabled = command != selected.first(),
                            onClick = { edit { it.movedCommand(command, -1) } },
                            modifier = Modifier.semantics { contentDescription = up },
                        ) { Text("↑") }
                        TextButton(
                            enabled = command != selected.last(),
                            onClick = { edit { it.movedCommand(command, 1) } },
                            modifier = Modifier.semantics { contentDescription = down },
                        ) { Text("↓") }
                        TextButton(
                            onClick = { edit { it.withoutCommand(command) } },
                            modifier = Modifier.semantics { contentDescription = remove },
                        ) { Text("×") }
                    }
                }
                item(key = "available-title") {
                    SectionTitle(
                        stringResource(R.string.settings_command_available) +
                            if (selected.size >= setting.max) " " + stringResource(R.string.settings_command_full, setting.max) else "",
                    )
                }
                items(available, key = { "available-${it.name}" }) { command ->
                    val add = stringResource(R.string.settings_command_add, setting.labelOf(command))
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = selected.size < setting.max, role = Role.Button) {
                            edit { it.withCommand(command, setting.max) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            setting.labelOf(command),
                            Modifier.weight(1f).padding(vertical = 12.dp),
                            color = if (selected.size < setting.max) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("＋", Modifier.semantics { contentDescription = add })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.settings_close)) } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}
