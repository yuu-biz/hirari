package io.github.yuubiz.hirari

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder

@Suppress("UNUSED_PARAMETER")
internal fun prototypeStartRoute(intent: Intent): Any? = null

@Suppress("UNUSED_PARAMETER")
internal fun debugBookRoute(intent: Intent): Any? = null

internal fun NavGraphBuilder.prototypeRoutes() = Unit

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun PrototypeMenuItem(onOpen: (Any) -> Unit) = Unit
