package io.github.yuubiz.hirari.feature.library

import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * The filter of a folder's file list. It takes the focus and shows the keyboard when it appears, so one tap is enough
 * to type. The field owns its text, starting from [initial], and reports each change; the list filters as the text
 * changes, and its state is never written back, which would end the input method's composition and move the cursor.
 */
@Composable
internal fun FilterField(
    initial: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val state = rememberTextFieldState(initial)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect(onValueChange)
    }
    OutlinedTextField(
        state = state,
        modifier = modifier.focusRequester(focus),
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        lineLimits = TextFieldLineLimits.SingleLine,
    )
}
