package io.github.yuubiz.hirari.core.ui

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import io.github.yuubiz.hirari.core.model.AppTheme

/** The settings `appTheme` and `dynamicColor` (docs/spec.md section 4.10), which the theme follows live. */
@Immutable
data class ThemeChoice(val appTheme: AppTheme = AppTheme.System, val dynamicColor: Boolean = true) {
    fun isDark(systemDark: Boolean): Boolean = when (appTheme) {
        AppTheme.System -> systemDark
        AppTheme.Light -> false
        AppTheme.Dark -> true
    }

    /** Wallpaper colors exist from Android 12; below it the setting has no row and the baseline scheme is used. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S, parameter = 0)
    fun usesWallpaperColors(sdk: Int): Boolean = dynamicColor && sdk >= Build.VERSION_CODES.S
}

/** [content] learns whether the theme resolved to dark, for what lives outside the composition, as the system bars. */
@Composable
fun HirariTheme(choice: ThemeChoice, content: @Composable (dark: Boolean) -> Unit) {
    val dark = choice.isDark(isSystemInDarkTheme())
    val colorScheme = when {
        choice.usesWallpaperColors(Build.VERSION.SDK_INT) -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    CompositionLocalProvider(LocalLibraryColors provides if (dark) LibraryColors.Dark else LibraryColors.Light) {
        MaterialTheme(colorScheme = colorScheme) { content(dark) }
    }
}
