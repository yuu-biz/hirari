package io.github.yuubiz.hirari.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.coroutines.cancellation.CancellationException

/** このアプリについて (docs/spec.md section 4.11). [load] reads the bundled parts off the main thread. */
@Composable
internal fun AboutDialog(load: suspend () -> AboutInfo, onOpenLicenses: () -> Unit, onDismiss: () -> Unit) {
    val info by produceState<AboutInfo?>(null, load) {
        value = try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AboutInfo(version = null, builtOn = null, changes = null)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title)) },
        text = {
            val loaded = info
            if (loaded == null) {
                CircularProgressIndicator()
            } else {
                AboutContent(loaded, onOpenLicenses)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_close)) } },
    )
}

@Composable
private fun AboutContent(info: AboutInfo, onOpenLicenses: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        FactRow(stringResource(R.string.about_version), info.version ?: stringResource(R.string.about_unknown))
        FactRow(stringResource(R.string.about_build_date), info.builtOn?.let(AboutInfo::dateText) ?: stringResource(R.string.about_unknown))
        TextButton(onClick = onOpenLicenses) { Text(stringResource(R.string.settings_licenses)) }
        Text(stringResource(R.string.about_changes), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        Text(info.changes ?: stringResource(R.string.about_changes_missing), Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label)
        Text(value)
    }
}
