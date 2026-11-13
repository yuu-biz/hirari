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
import io.github.yuubiz.hirari.core.ui.decorative
import io.github.yuubiz.hirari.core.ui.minTouchTarget
import io.github.yuubiz.hirari.core.ui.rememberBreathPermission

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
        is SlotsSetting -> SlotsRow(setting, settings, onOpen)
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
        is SlotsSetting -> SlotsDialog(setting, settings, onChange, onClose)
        is ToggleSetting, is SliderSetting<*> -> onClose()
    }
}

@Composable
private fun ToggleRow(setting: ToggleSetting, settings: GlobalSettings, onChange: ((GlobalSettings) -> GlobalSettings) -> Unit) {
    val checked = setting.read(settings)
    val microphone = if (setting.asksMicrophone) rememberBreathPermission { onChange { setting.write(it, true) } } else null
    ListItem(
        headlineContent = { Text(setting.label) },
        supportingContent = setting.note?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        modifier = Modifier.toggleable(value = checked, role = Role.Switch) { value ->
            if (value && microphone != null) microphone.launch() else onChange { setting.write(it, value) }
        },
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
        // The slider below says both the name and the value, so the line above is not read a second time.
        Row(Modifier.fillMaxWidth().decorative(), horizontalArrangement = Arrangement.SpaceBetween) {
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
            .decorative()
            .background(Color(0xFF000000.toInt() or color.value), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

@Composable
private fun SlotsRow(setting: SlotsSetting, settings: GlobalSettings, onOpen: (String) -> Unit) {
    SummaryRow(setting, setting.read(settings).joinToString("、") { setting.labelOf(it) }, onOpen)
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
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).minTouchTarget().padding(vertical = 4.dp),
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

/** The slots, each a button; a slot opens the list of what it can hold, and picking one goes back to the slots. */
@Composable
internal fun SlotsDialog(setting: SlotsSetting, settings: GlobalSettings, onChange: ((GlobalSettings) -> GlobalSettings) -> Unit, onClose: () -> Unit) {
    val slots = setting.read(settings)
    var picking by rememberSaveable { mutableStateOf<Int?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(picking?.let { stringResource(R.string.settings_slot_pick, it + 1) } ?: setting.label) },
        text = {
            val index = picking
            LazyColumn {
                if (index == null) {
                    items(slots.indices.toList(), key = { "slot-$it" }) { i ->
                        DialogRow(stringResource(R.string.settings_slot, i + 1), setting.labelOf(slots[i])) { picking = i }
                    }
                } else {
                    val options = listOf<ReaderCommand?>(null) + setting.choices.map { it.value }
                    items(options, key = { "pick-${it?.name}" }) { command ->
                        DialogRow(setting.labelOf(command), if (command == slots[index]) stringResource(R.string.settings_slot_current) else null) {
                            onChange { setting.write(it, setting.read(it).withSlot(index, command)) }
                            picking = null
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (picking == null) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.settings_close)) }
            } else {
                TextButton(onClick = { picking = null }) { Text(stringResource(R.string.settings_slot_back)) }
            }
        },
    )
}

@Composable
private fun DialogRow(label: String, value: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).minTouchTarget().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        if (value != null) Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
