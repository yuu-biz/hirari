package io.github.yuubiz.hirari.core.ui

import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeTonalSpot
import io.github.yuubiz.hirari.core.model.AppTheme
import io.github.yuubiz.hirari.core.model.ColorPalette

/** The settings `appTheme`, `colorPalette` and `pureBlack` (docs/spec.md section 4.10), which the theme follows live. */
@Immutable
data class ThemeChoice(
    val appTheme: AppTheme = AppTheme.System,
    val colorPalette: ColorPalette = ColorPalette.Wallpaper,
    val pureBlack: Boolean = false,
) {
    fun isDark(systemDark: Boolean): Boolean = when (appTheme) {
        AppTheme.System -> systemDark
        AppTheme.Light -> false
        AppTheme.Dark -> true
    }

    /** Wallpaper colors exist from Android 12; below it the choice is hidden and the baseline scheme is used. */
    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S, parameter = 0)
    fun usesWallpaperColors(sdk: Int): Boolean = colorPalette == ColorPalette.Wallpaper && sdk >= Build.VERSION_CODES.S
}

/** [content] learns whether the theme resolved to dark, for what lives outside the composition, as the system bars. */
@Composable
fun HirariTheme(choice: ThemeChoice, content: @Composable (dark: Boolean) -> Unit) {
    val dark = choice.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val colorScheme = remember(choice, dark, context) {
        val scheme = if (choice.usesWallpaperColors(Build.VERSION.SDK_INT)) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else paletteColorScheme(choice.colorPalette, dark)
        scheme.withPureBlack(dark && choice.pureBlack)
    }
    CompositionLocalProvider(LocalLibraryColors provides if (dark) LibraryColors.Dark else LibraryColors.Light) {
        MaterialTheme(colorScheme = colorScheme) { content(dark) }
    }
}

/** Wallpaper falls back to the unchanged Material defaults where dynamic colors are unavailable. */
internal fun paletteColorScheme(palette: ColorPalette, dark: Boolean): ColorScheme {
    val seed = when (palette) {
        ColorPalette.Wallpaper, ColorPalette.Standard -> return if (dark) darkColorScheme() else lightColorScheme()
        ColorPalette.Indigo -> 0x2E4A7D
        ColorPalette.Bamboo -> 0x4E8A4B
        ColorPalette.Sakura -> 0xC2607A
        ColorPalette.Orange -> 0xD9822B
        ColorPalette.Ink -> 0x5F6368
        is ColorPalette.Custom -> palette.color.value
    }
    val hct = Hct.fromInt(seed or 0xFF000000.toInt())
    val scheme = if (palette == ColorPalette.Ink) SchemeNeutral(hct, dark, 0.0) else SchemeTonalSpot(hct, dark, 0.0)
    return scheme.toColorScheme()
}

/** Only background roles turn black; foreground and inverse roles retain their contrast pairs. */
internal fun ColorScheme.withPureBlack(enabled: Boolean): ColorScheme = if (!enabled) this else copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    surfaceTint = Color.Black,
)

private fun DynamicScheme.toColorScheme(): ColorScheme = lightColorScheme(
    primary = Color(primary),
    onPrimary = Color(onPrimary),
    primaryContainer = Color(primaryContainer),
    onPrimaryContainer = Color(onPrimaryContainer),
    inversePrimary = Color(inversePrimary),
    secondary = Color(secondary),
    onSecondary = Color(onSecondary),
    secondaryContainer = Color(secondaryContainer),
    onSecondaryContainer = Color(onSecondaryContainer),
    tertiary = Color(tertiary),
    onTertiary = Color(onTertiary),
    tertiaryContainer = Color(tertiaryContainer),
    onTertiaryContainer = Color(onTertiaryContainer),
    background = Color(background),
    onBackground = Color(onBackground),
    surface = Color(surface),
    onSurface = Color(onSurface),
    surfaceVariant = Color(surfaceVariant),
    onSurfaceVariant = Color(onSurfaceVariant),
    surfaceTint = Color(surfaceTint),
    inverseSurface = Color(inverseSurface),
    inverseOnSurface = Color(inverseOnSurface),
    error = Color(error),
    onError = Color(onError),
    errorContainer = Color(errorContainer),
    onErrorContainer = Color(onErrorContainer),
    outline = Color(outline),
    outlineVariant = Color(outlineVariant),
    scrim = Color(scrim),
    surfaceDim = Color(surfaceDim),
    surfaceBright = Color(surfaceBright),
    surfaceContainerLowest = Color(surfaceContainerLowest),
    surfaceContainerLow = Color(surfaceContainerLow),
    surfaceContainer = Color(surfaceContainer),
    surfaceContainerHigh = Color(surfaceContainerHigh),
    surfaceContainerHighest = Color(surfaceContainerHighest),
    primaryFixed = Color(primaryFixed),
    primaryFixedDim = Color(primaryFixedDim),
    onPrimaryFixed = Color(onPrimaryFixed),
    onPrimaryFixedVariant = Color(onPrimaryFixedVariant),
    secondaryFixed = Color(secondaryFixed),
    secondaryFixedDim = Color(secondaryFixedDim),
    onSecondaryFixed = Color(onSecondaryFixed),
    onSecondaryFixedVariant = Color(onSecondaryFixedVariant),
    tertiaryFixed = Color(tertiaryFixed),
    tertiaryFixedDim = Color(tertiaryFixedDim),
    onTertiaryFixed = Color(onTertiaryFixed),
    onTertiaryFixedVariant = Color(onTertiaryFixedVariant),
)
