package io.github.yuubiz.hirari

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.yuubiz.hirari.core.ui.HirariTheme
import io.github.yuubiz.hirari.feature.library.HomeScreen
import io.github.yuubiz.hirari.feature.settings.LicenseScreen

private const val HOME = "home"
private const val LICENSES = "licenses"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            HirariTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = prototypeStartRoute(intent) ?: HOME) {
                    composable(HOME) {
                        HomeScreen(actions = { HomeMenu(onLicenses = { nav.navigate(LICENSES) }, onOpen = nav::navigate) })
                    }
                    composable(LICENSES) { LicenseScreen(onBack = { nav.popBackStack() }) }
                    prototypeRoutes()
                }
            }
        }
    }
}

/** Until the settings screen exists, the license screen is opened from here (docs/spec.md M0). */
@Composable
private fun HomeMenu(onLicenses: () -> Unit, onOpen: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.menu)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.menu_licenses)) },
            onClick = {
                open = false
                onLicenses()
            },
        )
        PrototypeMenuItem {
            open = false
            onOpen(it)
        }
    }
}
