package io.github.yuubiz.hirari.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.yuubiz.hirari.core.ui.GlyphButton
import io.github.yuubiz.hirari.core.ui.announced
import io.github.yuubiz.hirari.core.ui.decorative
import io.github.yuubiz.hirari.core.ui.minTouchTarget
import io.github.yuubiz.hirari.core.source.smb.DiscoveredServer
import io.github.yuubiz.hirari.core.source.smb.DiscoveredShare
import io.github.yuubiz.hirari.core.source.smb.ServerSearch
import io.github.yuubiz.hirari.core.source.smb.ShareDiscoveryFailure
import io.github.yuubiz.hirari.core.source.smb.ShareDiscoveryResult

/**
 * Runs an action once the app may open connections in the local network, asking for `ACCESS_LOCAL_NETWORK` first
 * where the system has it (docs/spec.md section 7). The action runs whatever the answer is; without the grant, it
 * reports that the permission is missing.
 */
@Composable
fun rememberLocalNetworkGate(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pending?.invoke()
        pending = null
    }
    return { action ->
        val missing = Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK &&
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (missing) {
            pending = action
            launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            action()
        }
    }
}

/** The form that adds or edits an SMB server (docs/spec.md section 4.5). [onClose] leaves it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmbServerScreen(model: SmbServerViewModel, onClose: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    // Kept out of saved state, so the password never reaches a bundle (docs/spec.md section 7).
    val password = remember { TextFieldState() }
    val gate = rememberLocalNetworkGate()
    DisposableEffect(model) {
        onDispose {
            model.cancelDiscovery()
            model.closeServers()
        }
    }
    // A save the form rejects keeps the typed password for the next try; only a saved one is cleared.
    LaunchedEffect(state) {
        if (state == SmbFormUi.Saved) {
            password.clearText()
            onClose()
        }
    }

    fun typed(): CharArray = password.text.let { text -> CharArray(text.length) { text[it] } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if ((state as? SmbFormUi.Editing)?.editing == true) R.string.smb_edit_title else R.string.smb_add_title)) },
                navigationIcon = { GlyphButton("←", stringResource(R.string.nav_back), onClose) },
            )
        },
    ) { padding ->
        when (val form = state) {
            SmbFormUi.Loading, SmbFormUi.Saved -> Unit
            SmbFormUi.Missing -> EmptyNote(R.string.smb_missing)
            is SmbFormUi.Editing -> SmbForm(
                form,
                password,
                Modifier.padding(padding),
                onEdit = model::edit,
                onRemovePassword = model::setRemovePassword,
                onTest = { gate { model.test(typed()) } },
                onSave = { gate { model.save(typed()) } },
                onDiscover = { gate { model.discover(typed()) } },
                onFindServers = { gate { model.searchServers() } },
            )
        }
    }
    (state as? SmbFormUi.Editing)?.let { form ->
        ShareDiscoveryDialog(form, model::cancelDiscovery, model::chooseShare)
        form.servers?.let { servers ->
            ServerSearchDialog(
                servers,
                onClose = model::closeServers,
                onAgain = { gate { model.searchServers() } },
                onChoose = { model.chooseServer(it, typed()) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmbForm(
    form: SmbFormUi.Editing,
    password: TextFieldState,
    modifier: Modifier,
    onEdit: (SmbFields) -> Unit,
    onRemovePassword: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
    onDiscover: () -> Unit,
    onFindServers: () -> Unit,
) {
    val fields = form.fields
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Field(R.string.smb_display_name, fields.displayName, hint = R.string.smb_display_name_hint) { onEdit(fields.copy(displayName = it)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Field(R.string.smb_host, fields.host, error = form.errors[SmbField.Host], type = KeyboardType.Uri) { onEdit(fields.copy(host = it)) }
            }
            OutlinedButton(onClick = onFindServers) { Text(stringResource(R.string.smb_find_servers)) }
        }
        Field(R.string.smb_port, fields.port, error = form.errors[SmbField.Port], type = KeyboardType.Number) { onEdit(fields.copy(port = it)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Field(R.string.smb_share, fields.share, error = form.errors[SmbField.Share]) { onEdit(fields.copy(share = it)) }
            }
            OutlinedButton(onClick = onDiscover, enabled = form.canDiscover) { Text(stringResource(R.string.smb_discover)) }
        }
        Field(R.string.smb_folder, fields.folder, hint = R.string.smb_folder_hint) { onEdit(fields.copy(folder = it)) }
        Field(R.string.smb_domain, fields.domain) { onEdit(fields.copy(domain = it)) }
        Field(R.string.smb_user, fields.user, error = form.errors[SmbField.User], hint = R.string.smb_user_hint) { onEdit(fields.copy(user = it)) }
        OutlinedSecureTextField(
            state = password,
            modifier = Modifier.fillMaxWidth().maskedForAccessibility(password),
            label = { Text(stringResource(R.string.smb_password)) },
            supportingText = if (form.storedPassword) ({ Text(stringResource(R.string.smb_password_kept)) }) else null,
        )
        if (form.storedPassword) {
            Row(
                Modifier.fillMaxWidth().toggleable(form.removePassword, role = Role.Checkbox, onValueChange = onRemovePassword).minTouchTarget(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = form.removePassword, onCheckedChange = null)
                Text(stringResource(R.string.smb_password_remove))
            }
        }
        TestLine(form.test)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onTest, enabled = form.test != ConnectionTest.Running && !form.saving) { Text(stringResource(R.string.smb_test)) }
            Button(onClick = onSave, enabled = !form.saving) { Text(stringResource(R.string.smb_save)) }
        }
    }
}

@Composable
private fun ShareDiscoveryDialog(form: SmbFormUi.Editing, onClose: () -> Unit, onChoose: (DiscoveredShare) -> Unit) {
    if (!form.discovering && form.discovery == null) return
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.smb_discover)) },
        text = {
            when (val result = form.discovery) {
                null -> Column(Modifier.announced(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.decorative())
                    Text(stringResource(R.string.smb_discovering))
                }
                is ShareDiscoveryResult.Failed -> Text(stringResource(result.reason.discoveryMessage()), Modifier.announced())
                is ShareDiscoveryResult.Listed -> LazyColumn {
                    items(result.shares, key = { it.name }) { share ->
                        TextButton(onClick = { onChoose(share) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(share.name)
                                share.remark?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The list of サーバーを探す (docs/spec.md section 4.5); [onAgain] is offered once the search settles. */
@Composable
private fun ServerSearchDialog(servers: ServerSearch, onClose: () -> Unit, onAgain: () -> Unit, onChoose: (DiscoveredServer) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.smb_find_servers)) },
        text = {
            when (servers) {
                ServerSearch.NoNetwork -> Text(stringResource(R.string.smb_servers_no_network), Modifier.announced())
                ServerSearch.NoLocalNetwork -> Text(stringResource(R.string.smb_discovery_permission), Modifier.announced())
                is ServerSearch.Searching -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.announced(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.padding(4.dp).decorative())
                        Text(stringResource(R.string.smb_finding_servers))
                    }
                    ServerRows(servers.found, onChoose)
                }
                is ServerSearch.Settled -> if (servers.found.isEmpty()) {
                    Column(Modifier.announced(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.smb_servers_none))
                        Text(stringResource(R.string.smb_servers_windows), style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    ServerRows(servers.found, onChoose)
                }
            }
        },
        confirmButton = {
            if (servers is ServerSearch.Settled) TextButton(onClick = onAgain) { Text(stringResource(R.string.smb_find_servers_again)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ServerRows(servers: List<DiscoveredServer>, onChoose: (DiscoveredServer) -> Unit) {
    LazyColumn {
        items(servers, key = { it.address.hostAddress.orEmpty() }) { server ->
            TextButton(onClick = { onChoose(server) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text(server.name)
                    Text(server.shownAddress(), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** The address of a row, with its port only when it is not 445. */
internal fun DiscoveredServer.shownAddress(): String = address.hostAddress.orEmpty().let { if (port == SMB_PORT) it else "$it:$port" }

@StringRes
internal fun ShareDiscoveryFailure.discoveryMessage(): Int = when (this) {
    ShareDiscoveryFailure.Unreachable -> R.string.smb_discovery_unreachable
    ShareDiscoveryFailure.AuthenticationFailed -> R.string.smb_discovery_auth
    ShareDiscoveryFailure.NoLocalNetwork -> R.string.smb_discovery_permission
    ShareDiscoveryFailure.Unsupported -> R.string.smb_discovery_unsupported
    ShareDiscoveryFailure.Empty -> R.string.smb_discovery_empty
    ShareDiscoveryFailure.PasswordUnreadable -> R.string.smb_failed_password_unreadable
}

/**
 * Replaces the text the field tells the accessibility services with one bullet per character. The field is a password
 * to them already, yet its node still carries what was typed, which a dump of the screen shows (docs/spec.md section 4.5).
 */
private fun Modifier.maskedForAccessibility(password: TextFieldState): Modifier = semantics {
    editableText = AnnotatedString("•".repeat(password.text.length))
}

@Composable
private fun Field(
    @StringRes label: Int,
    value: String,
    error: SmbFieldError? = null,
    @StringRes hint: Int? = null,
    type: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    val message = when (error) {
        SmbFieldError.Required -> stringResource(R.string.smb_error_required)
        SmbFieldError.Invalid -> stringResource(R.string.smb_error_invalid)
        null -> null
    }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().semantics { message?.let { error(it) } },
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        supportingText = when {
            message != null -> ({ Text(message) })
            hint != null -> ({ Text(stringResource(hint)) })
            else -> null
        },
    )
}

@Composable
private fun TestLine(test: ConnectionTest) {
    when (test) {
        ConnectionTest.Idle -> Unit
        ConnectionTest.Running -> Row(
            Modifier.announced(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.padding(4.dp).decorative())
            Text(stringResource(R.string.smb_testing))
        }
        ConnectionTest.Passed -> Text(stringResource(R.string.smb_test_passed), Modifier.announced(), color = MaterialTheme.colorScheme.primary)
        is ConnectionTest.Failed -> Text(stringResource(test.reason.message()), Modifier.announced(), color = MaterialTheme.colorScheme.error)
    }
}

@StringRes
fun SmbTestFailure.message(): Int = when (this) {
    SmbTestFailure.NoLocalNetwork -> R.string.smb_failed_local_network
    SmbTestFailure.PasswordUnreadable -> R.string.smb_failed_password_unreadable
    SmbTestFailure.NameNotResolved -> R.string.smb_failed_name
    SmbTestFailure.Unreachable -> R.string.smb_failed_unreachable
    SmbTestFailure.AuthenticationFailed -> R.string.smb_failed_auth
    SmbTestFailure.ShareNotFound -> R.string.smb_failed_share
    SmbTestFailure.FolderNotFound -> R.string.smb_failed_folder
    SmbTestFailure.ProtocolError -> R.string.smb_failed_protocol
}
