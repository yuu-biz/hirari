package io.github.yuubiz.hirari.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource

/** Only the checkbox is saved; password input must not reach saved instance state. */
@Composable
fun BookPasswordDialog(wrong: Boolean, busy: Boolean, submit: (CharArray, Boolean) -> Unit, cancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var rememberPassword by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(busy) { if (!busy) password = "" }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text(stringResource(R.string.book_password_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, enabled = !busy, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    label = { Text(stringResource(R.string.book_password_title)) }, isError = wrong,
                )
                if (wrong) Text(stringResource(R.string.book_password_wrong))
                Row {
                    Checkbox(checked = rememberPassword, onCheckedChange = { rememberPassword = it }, enabled = !busy)
                    Text(stringResource(R.string.book_password_remember))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                val chars = password.toCharArray()
                password = ""
                submit(chars, rememberPassword)
            }) { Text(stringResource(R.string.book_password_open)) }
        },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.book_password_cancel)) } },
    )
}
