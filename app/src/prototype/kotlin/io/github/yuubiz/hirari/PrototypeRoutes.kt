package io.github.yuubiz.hirari

import android.content.Intent
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.yuubiz.hirari.core.model.BookAddress
import io.github.yuubiz.hirari.core.model.OpenBookRequest
import io.github.yuubiz.hirari.core.model.UriString
import io.github.yuubiz.hirari.feature.reader.prototype.PrototypeScreen
import kotlinx.serialization.Serializable

@Serializable
internal data class PrototypeRoute(val variant: String)

/**
 * The Q1 prototypes, in the debug and benchmark builds only. The release build has its own no-op copy of
 * this file. `adb shell am start -n io.github.yuubiz.hirari/.MainActivity --es prototype view-hardware`
 * opens one directly.
 */
internal fun prototypeStartRoute(intent: Intent): Any? =
    intent.getStringExtra("prototype")?.let(::PrototypeRoute)

/**
 * Opens the document whose URI is in the `book` extra, so a device test or `adb shell am start ... --es book
 * file:///...` reaches the reader without the picker. A string extra, because a `file:` URI in the intent data
 * would be refused on its way out of the caller's process.
 */
internal fun debugBookRoute(intent: Intent): Any? =
    intent.getStringExtra("book")?.let { OpenBookRequest(BookAddress.Document(UriString(it))).toReaderRoute() }

internal fun NavGraphBuilder.prototypeRoutes() {
    composable<PrototypeRoute> { PrototypeScreen(it.toRoute<PrototypeRoute>().variant) }
}
