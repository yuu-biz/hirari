package io.github.yuubiz.hirari.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val assets = LocalContext.current.assets
    val report by produceState<LicenseReport?>(null) {
        value = withContext(Dispatchers.IO) {
            LicenseReport.parse(assets.open(LicenseReport.ASSET).bufferedReader().use { it.readText() })
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.licenses_back)) } },
            )
        },
    ) { padding ->
        val loaded = report ?: return@Scaffold
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(loaded.libraries, key = { it.id }) { library ->
                LicenseItem(library, loaded.textsOf(library))
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun LicenseItem(library: LicensedLibrary, texts: List<String>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }.padding(16.dp)) {
        Text("${library.name} ${library.version}", style = MaterialTheme.typography.titleMedium)
        Text(library.licenses.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
        if (expanded) {
            texts.forEach { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
