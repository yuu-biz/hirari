package io.github.yuubiz.hirari.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.yuubiz.hirari.core.model.KeepAwake
import kotlinx.coroutines.delay

/**
 * Whether 画面を消さない (docs/spec.md section 4.10) keeps a reader's screen on now. [touched] changes on every input,
 * which starts the ten minutes of [KeepAwake.TenMinutes] again.
 */
@Composable
fun keepsAwake(mode: KeepAwake, touched: Int): Boolean {
    var awake by remember { mutableStateOf(false) }
    LaunchedEffect(mode, touched) {
        awake = mode != KeepAwake.System
        if (mode == KeepAwake.TenMinutes) {
            delay(AWAKE_AFTER_INPUT_MS)
            awake = false
        }
    }
    return awake
}

/** How long [KeepAwake.TenMinutes] keeps the screen on after the last input. */
const val AWAKE_AFTER_INPUT_MS = 10 * 60 * 1000L
