package io.github.yuubiz.hirari.core.text

import android.graphics.Paint
import android.graphics.Typeface

/** [GlyphMetrics] from the typeface the page is drawn with. Not thread-safe; one layout uses it at a time. */
class PaintGlyphMetrics(typeface: Typeface) : GlyphMetrics {
    private val regular = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = typeface }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.typeface = Typeface.create(typeface, Typeface.BOLD) }

    override fun width(text: String, size: Float, bold: Boolean): Float {
        val paint = if (bold) this.bold else regular
        paint.textSize = size
        return paint.measureText(text)
    }
}
