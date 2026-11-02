package io.github.yuubiz.hirari.feature.reader.prototype

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** The two drawing surfaces compared in docs/spec.md section 8.4. */
internal enum class RenderSurface(val key: String) {
    COMPOSE("compose"),
    VIEW("view"),
}

/** One prototype configuration, written `<surface>-<pixel mode>`, for example `view-hardware`. */
internal data class PrototypeVariant(val surface: RenderSurface, val mode: PixelMode) {
    val key: String get() = "${surface.key}-${mode.key}"

    val next: PrototypeVariant
        get() {
            val all = RenderSurface.entries.flatMap { s -> PixelMode.entries.map { PrototypeVariant(s, it) } }
            return all[(all.indexOf(this) + 1) % all.size]
        }

    companion object {
        fun parse(key: String?): PrototypeVariant {
            val parts = key.orEmpty().split('-', limit = 2)
            return PrototypeVariant(
                RenderSurface.entries.firstOrNull { it.key == parts[0] } ?: RenderSurface.COMPOSE,
                PixelMode.entries.firstOrNull { it.key == parts.getOrNull(1) } ?: PixelMode.HARDWARE,
            )
        }
    }
}

/**
 * Throwaway screen for Q1 and Q2. [variantKey] picks the first variant, for example `view-hardware`; the
 * label at the top switches to the next one.
 */
@Composable
fun PrototypeScreen(variantKey: String?) {
    var variant by remember { mutableStateOf(PrototypeVariant.parse(variantKey)) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        key(variant) {
            when (variant.surface) {
                RenderSurface.COMPOSE -> ComposeSurface(variant.mode)
                RenderSurface.VIEW -> ViewSurface(variant.mode)
            }
        }
        Text(
            text = variant.key,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .safeDrawingPadding()
                .background(Color(0x99000000))
                .clickable { variant = variant.next }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
