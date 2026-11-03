package io.github.yuubiz.hirari.core.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/** What turning on a feature behind a runtime permission does first. */
enum class PermissionStep {
    /** The app holds the permission: the feature turns on. */
    Granted,

    /** The user turned the request down before: say why the app asks, then ask. */
    Explain,

    /** Ask the system. A permission turned down for good comes back refused at once. */
    Ask,
}

fun permissionStep(granted: Boolean, showRationale: Boolean): PermissionStep = when {
    granted -> PermissionStep.Granted
    showRationale -> PermissionStep.Explain
    else -> PermissionStep.Ask
}

private enum class PermissionDialog { Explain, Refused }

/** Turns on a feature behind a runtime permission: [launch] asks for it when the app does not hold it. */
@Stable
class PermissionRequest internal constructor(private val start: () -> Unit) {
    fun launch() = start()
}

/**
 * Asks for [permission] when a feature turns on, and calls [onGranted] once the app holds it. A request the user
 * turned down before is explained by [rationale] first. A refusal says [refused] and offers the app's page of the
 * system settings, where a permission refused for good can still be given.
 */
@Composable
fun rememberPermissionRequest(permission: String, rationale: String, refused: String, onGranted: () -> Unit): PermissionRequest {
    val context = LocalContext.current
    val granted by rememberUpdatedState(onGranted)
    var dialog by rememberSaveable { mutableStateOf<PermissionDialog?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) granted() else dialog = PermissionDialog.Refused
    }
    when (dialog) {
        PermissionDialog.Explain -> AlertDialog(
            onDismissRequest = { dialog = null },
            text = { Text(rationale) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    launcher.launch(permission)
                }) { Text(stringResource(R.string.permission_continue)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.permission_cancel)) } },
        )
        PermissionDialog.Refused -> AlertDialog(
            onDismissRequest = { dialog = null },
            text = { Text(refused) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    context.openAppSettings()
                }) { Text(stringResource(R.string.permission_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.permission_close)) } },
        )
        null -> Unit
    }
    return remember(context, permission, launcher) {
        PermissionRequest {
            val held = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            val explain = context.findActivity()?.shouldShowRequestPermissionRationale(permission) == true
            when (permissionStep(held, explain)) {
                PermissionStep.Granted -> granted()
                PermissionStep.Explain -> dialog = PermissionDialog.Explain
                PermissionStep.Ask -> launcher.launch(permission)
            }
        }
    }
}

/** The microphone for 息での操作, which the reader's command and the settings screen both turn on (docs/spec.md section 4.11). */
@Composable
fun rememberBreathPermission(onGranted: () -> Unit): PermissionRequest = rememberPermissionRequest(
    Manifest.permission.RECORD_AUDIO,
    stringResource(R.string.breath_permission_rationale),
    stringResource(R.string.breath_permission_refused),
    onGranted,
)

private fun Context.openAppSettings() {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
