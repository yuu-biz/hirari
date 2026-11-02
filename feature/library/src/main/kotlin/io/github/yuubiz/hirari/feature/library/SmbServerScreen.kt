package io.github.yuubiz.hirari.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    LaunchedEffect(state) { if (state == SmbFormUi.Saved) onClose() }

    fun typed(): CharArray = password.text.let { text -> CharArray(text.length) { text[it] } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if ((state as? SmbFormUi.Editing)?.editing == true) R.string.smb_edit_title else R.string.smb_add_title)) },
                navigationIcon = { TextButton(onClick = onClose) { Text("←") } },
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
                onSave = {
                    gate {
                        model.save(typed())
                        password.clearText()
                    }
                },
            )
        }
    }
}

@Composable
private fun SmbForm(
    form: SmbFormUi.Editing,
    password: TextFieldState,
    modifier: Modifier,
    onEdit: (SmbFields) -> Unit,
    onRemovePassword: (Boolean) -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    val fields = form.fields
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Field(R.string.smb_display_name, fields.displayName, hint = R.string.smb_display_name_hint) { onEdit(fields.copy(displayName = it)) }
        Field(R.string.smb_host, fields.host, error = form.errors[SmbField.Host], type = KeyboardType.Uri) { onEdit(fields.copy(host = it)) }
        Field(R.string.smb_port, fields.port, error = form.errors[SmbField.Port], type = KeyboardType.Number) { onEdit(fields.copy(port = it)) }
        Field(R.string.smb_share, fields.share, error = form.errors[SmbField.Share]) { onEdit(fields.copy(share = it)) }
        Field(R.string.smb_folder, fields.folder, hint = R.string.smb_folder_hint) { onEdit(fields.copy(folder = it)) }
        Field(R.string.smb_domain, fields.domain) { onEdit(fields.copy(domain = it)) }
        Field(R.string.smb_user, fields.user, error = form.errors[SmbField.User], hint = R.string.smb_user_hint) { onEdit(fields.copy(user = it)) }
        OutlinedSecureTextField(
            state = password,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.smb_password)) },
            supportingText = if (form.storedPassword) ({ Text(stringResource(R.string.smb_password_kept)) }) else null,
        )
        if (form.storedPassword) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = form.removePassword, onCheckedChange = onRemovePassword)
                Text(stringResource(R.string.smb_password_remove))
            }
        }
        TestLine(form.test)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onTest, enabled = form.test != ConnectionTest.Running && !form.saving) { Text(stringResource(R.string.smb_test)) }
            Button(onClick = onSave, enabled = !form.saving) { Text(stringResource(R.string.smb_save)) }
        }
    }
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
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error != null,
        keyboardOptions = KeyboardOptions(keyboardType = type),
        supportingText = when {
            error == SmbFieldError.Required -> ({ Text(stringResource(R.string.smb_error_required)) })
            error == SmbFieldError.Invalid -> ({ Text(stringResource(R.string.smb_error_invalid)) })
            hint != null -> ({ Text(stringResource(hint)) })
            else -> null
        },
    )
}

@Composable
private fun TestLine(test: ConnectionTest) {
    when (test) {
        ConnectionTest.Idle -> Unit
        ConnectionTest.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.padding(4.dp))
            Text(stringResource(R.string.smb_testing))
        }
        ConnectionTest.Passed -> Text(stringResource(R.string.smb_test_passed), color = MaterialTheme.colorScheme.primary)
        is ConnectionTest.Failed -> Text(stringResource(test.reason.message()), color = MaterialTheme.colorScheme.error)
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
