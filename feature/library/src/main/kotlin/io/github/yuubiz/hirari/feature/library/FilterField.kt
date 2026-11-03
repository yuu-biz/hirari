package io.github.yuubiz.hirari.feature.library

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** The filter of a folder's file list. It takes the focus and shows the keyboard when it appears, so one tap is enough to type. */
@Composable
internal fun FilterField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.focusRequester(focus),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
    )
}
