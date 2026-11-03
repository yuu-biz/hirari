package io.github.yuubiz.hirari.feature.settings

import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.data.Storage
import io.github.yuubiz.hirari.core.model.UriString
import java.time.LocalDate

/**
 * What the settings screen opens on: the list, or one dialog over it for the reader's コマンドバーの編集 or このアプリについて
 * (docs/spec.md section 4.8, メニュー). Closing that dialog leaves the screen, back to where it was opened from.
 */
enum class SettingsOpening { List, CommandBar, About }

/**
 * The settings screen (docs/spec.md sections 4.10 and 4.11), built on [storage] and hosted by `:app` as one route.
 * [onBack] leaves the screen. [onOpenLicenses] opens [LicenseScreen]. All reads and writes run off the main thread.
 */
@Composable
fun SettingsScreen(storage: Storage, onBack: () -> Unit, onOpenLicenses: () -> Unit, opening: SettingsOpening = SettingsOpening.List) {
    val context: Context = LocalContext.current
    val services = remember(storage) { settingsServices(storage, context) }
    SettingsScreen(services, onBack, onOpenLicenses, opening)
}

/** The same screen on any [services], for the tests and for apps that build their own. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(services: SettingsServices, onBack: () -> Unit, onOpenLicenses: () -> Unit, opening: SettingsOpening = SettingsOpening.List) {
    val viewModel: SettingsViewModel = viewModel(factory = remember(services) { viewModelFactory { initializer { SettingsViewModel(services) } } })
    val ui by viewModel.state.collectAsStateWithLifecycle()

    var openedSetting by rememberSaveable { mutableStateOf(if (opening == SettingsOpening.CommandBar) GlobalSettings::commandBarItems.name else null) }
    var exportDialog by rememberSaveable { mutableStateOf(false) }
    var includeReading by rememberSaveable { mutableStateOf(false) }
    var aboutDialog by rememberSaveable { mutableStateOf(opening == SettingsOpening.About) }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(JSON_TYPE)) { uri ->
        if (uri != null) viewModel.export(UriString(uri.toString()), includeReading)
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.prepareImport(UriString(uri.toString()))
    }
    val openFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFont(UriString(uri.toString()))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.settings_back)) } },
            )
        },
    ) { padding ->
        when (val load = ui.load) {
            SettingsLoad.Loading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            SettingsLoad.Failed -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.settings_load_failed), Modifier.padding(16.dp))
            }
            is SettingsLoad.Ready -> {
                val settings = load.settings
                val groups = remember { settingsFor(Build.VERSION.SDK_INT) }
                LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                    for ((group, rows) in groups) {
                        item(key = "group-${group.name}") { GroupHeader(group.title) }
                        items(rows, key = { it.key }) { setting ->
                            SettingRow(setting, settings, ui.saveFailures, viewModel::change) { openedSetting = it }
                        }
                    }
                    item(key = "group-data") { GroupHeader(stringResource(R.string.settings_group_data)) }
                    item(key = "export") {
                        ActionRow(stringResource(R.string.settings_export), stringResource(R.string.settings_export_summary)) { exportDialog = true }
                    }
                    item(key = "import") {
                        ActionRow(stringResource(R.string.settings_import), stringResource(R.string.settings_import_summary)) {
                            openDocument.launch(arrayOf(ANY_TYPE))
                        }
                    }
                    item(key = "thumbnails") {
                        ActionRow(stringResource(R.string.settings_clear_thumbnails), stringResource(R.string.settings_clear_thumbnails_summary), viewModel::clearThumbnails)
                    }
                    item(key = "extraction") {
                        ActionRow(stringResource(R.string.settings_clear_extraction), stringResource(R.string.settings_clear_extraction_summary), viewModel::clearExtraction)
                    }
                    item(key = "licenses") { ActionRow(stringResource(R.string.settings_licenses), null, onOpenLicenses) }
                    item(key = "about") { ActionRow(stringResource(R.string.settings_about), null) { aboutDialog = true } }
                }
                openedSetting?.let { key ->
                    SettingsTable.firstOrNull { it.key == key }?.let {
                        SettingDialog(it, settings, ui.fonts, { openFont.launch(FONT_MIME_TYPES) }, viewModel::change) {
                            openedSetting = null
                            if (opening == SettingsOpening.CommandBar) onBack()
                        }
                    }
                }
            }
        }
    }

    if (exportDialog) {
        ExportDialog(
            includeReading = includeReading,
            onIncludeReading = { includeReading = it },
            onChoosePlace = {
                exportDialog = false
                createDocument.launch(exportFileName(LocalDate.now()))
            },
            onDismiss = { exportDialog = false },
        )
    }
    if (aboutDialog) {
        AboutDialog(
            load = services.about,
            onOpenLicenses = {
                aboutDialog = false
                onOpenLicenses()
            },
            onDismiss = {
                aboutDialog = false
                if (opening == SettingsOpening.About) onBack()
            },
        )
    }
    when (val action = ui.action) {
        ActionState.Idle -> Unit
        is ActionState.Working -> WorkingDialog()
        is ActionState.ConfirmImport -> ConfirmImportDialog(action.changes, onConfirm = viewModel::confirmImport, onCancel = viewModel::cancelImport)
        is ActionState.Done -> ResultDialog(action.result, onDismiss = viewModel::dismissResult)
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        title,
        Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun ActionRow(title: String, summary: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

private const val JSON_TYPE = "application/json"
private const val ANY_TYPE = "*/*"

/** `hirari-settings-2026-11-03.json`. */
internal fun exportFileName(date: LocalDate): String = "hirari-settings-$date.json"
