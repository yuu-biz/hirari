package io.github.yuubiz.hirari

import android.content.Intent
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.core.net.toUri
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import io.github.yuubiz.hirari.feature.reader.prototype.PrototypeScreen

/**
 * The Q1 prototypes, in the debug and benchmark builds only. The release build has its own no-op copy of
 * this file. `adb shell am start -n io.github.yuubiz.hirari/.MainActivity --es prototype view-hardware`
 * opens one directly.
 */
internal fun prototypeStartRoute(intent: Intent): String? =
    intent.getStringExtra("prototype")?.let { "prototype/$it" }

/**
 * Opens the document whose URI is in the `book` extra, so a device test or `adb shell am start ... --es book
 * file:///...` reaches the reader without the picker. A string extra, because a `file:` URI in the intent data
 * would be refused on its way out of the caller's process.
 */
internal fun debugBookRoute(intent: Intent): String? =
    intent.getStringExtra("book")?.let { readerRoute(folder = false, it.toUri()) }

internal fun NavGraphBuilder.prototypeRoutes() {
    composable("prototype/{variant}") { PrototypeScreen(it.arguments?.getString("variant")) }
}

@Composable
internal fun PrototypeMenuItem(onOpen: (String) -> Unit) {
    DropdownMenuItem(text = { Text("Q1 prototype") }, onClick = { onOpen("prototype/compose-hardware") })
}
