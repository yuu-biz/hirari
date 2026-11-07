package io.github.yuubiz.hirari

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.toArgb
import io.github.yuubiz.hirari.core.ui.ThemeChoice
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The theme the settings `appTheme`, `colorPalette` and `pureBlack` choose, from the system's until the settings are read. */
@Composable
internal fun themeChoice(app: HirariApp): State<ThemeChoice> = produceState(ThemeChoice()) {
    app.services.await().storage.settings.settings
        .map { ThemeChoice(it.appTheme, it.colorPalette, it.pureBlack) }
        .distinctUntilChanged()
        .collect { value = it }
}

/**
 * What the theme cannot reach from the composition (docs/spec.md section 4.10): the window's background, which
 * shows before and between screens, and the icons of the status and navigation bars. [dark] is the theme's result, not
 * the system's night mode.
 */
@Composable
internal fun ComponentActivity.FollowTheme(dark: Boolean) {
    val background = MaterialTheme.colorScheme.background.toArgb()
    SideEffect { window.setBackgroundDrawable(ColorDrawable(background)) }
    DisposableEffect(dark) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(NAVIGATION_LIGHT_SCRIM, NAVIGATION_DARK_SCRIM) { dark },
        )
        onDispose {}
    }
}

/** The scrims `enableEdgeToEdge()` uses when it is given no style. */
private val NAVIGATION_LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NAVIGATION_DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
