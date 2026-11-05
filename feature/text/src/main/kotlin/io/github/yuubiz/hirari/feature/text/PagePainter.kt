package io.github.yuubiz.hirari.feature.text

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.withRotation
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import io.github.yuubiz.hirari.core.model.Rgb
import io.github.yuubiz.hirari.core.text.EmphasisRun
import io.github.yuubiz.hirari.core.text.Glyph
import io.github.yuubiz.hirari.core.text.GlyphContent
import io.github.yuubiz.hirari.core.text.LineStyle
import io.github.yuubiz.hirari.core.text.MarkSide
import io.github.yuubiz.hirari.core.text.Orientation
import io.github.yuubiz.hirari.core.text.PageContent
import io.github.yuubiz.hirari.core.text.SideLineRun
import io.github.yuubiz.hirari.core.text.TextLine
import io.github.yuubiz.hirari.core.text.TextPage
import kotlin.math.PI
import kotlin.math.sin

/** What a page shows besides its text: the title band, the page number, and the search highlight. */
class PageDecor(val title: String, val number: String, val highlight: IntRange?)

/**
 * Draws laid-out pages onto a canvas (docs/spec.md section 4.9). The vertical forms of punctuation, brackets and
 * the long vowel mark come from the font's `vert` feature. Not thread-safe; the drawing thread owns one.
 */
class PagePainter(typeface: Typeface, colors: PageColors) {
    private val ink = argb(colors.ink)
    private val paper = argb(colors.paper)
    private val highlightColor = argb(colors.highlight)
    private val regular = textPaint(typeface)
    private val bold = textPaint(Typeface.create(typeface, Typeface.BOLD))
    private val info = textPaint(typeface).apply { fontFeatureSettings = null; alpha = 0xB0 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ink
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val metrics = Paint.FontMetrics()
    private val rect = RectF()
    private val path = Path()

    private fun textPaint(face: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = face
        color = ink
        fontFeatureSettings = "'vert' 1"
    }

    /**
     * Draws [page] into the page box of [frame] whose top left is at ([left], [top]), or the paper and [decor] alone
     * while the page is null. [picture] gives the decoded 挿絵 of a path, or null while it loads.
     */
    fun draw(canvas: Canvas, page: TextPage?, frame: PageFrame, left: Float, top: Float, decor: PageDecor, picture: (String) -> Bitmap?) {
        fill.color = paper
        canvas.drawRect(left, top, left + frame.pageWidth, top + frame.pageHeight, fill)
        fill.color = ink
        drawInfo(canvas, decor.title, left + frame.pageWidth / 2, top + frame.margin, frame, alignTop = true)
        drawInfo(canvas, decor.number, left + frame.pageWidth / 2, top + frame.pageHeight - frame.margin, frame, alignTop = false)
        val originX = left + frame.margin
        val originY = top + frame.margin + frame.band
        canvas.withSave {
            clipRect(originX, originY, originX + frame.params.width, originY + frame.params.height)
            when (val content = page?.content) {
                null -> Unit
                is PageContent.Picture -> drawPicture(canvas, picture(content.path), originX, originY, frame.params.width, frame.params.height)
                is PageContent.Lines -> canvas.withTranslation(originX, originY) {
                    decor.highlight?.let { range -> content.lines.forEach { drawHighlight(this, it, range) } }
                    content.lines.forEach { drawLine(this, it, frame.params.fontSize, picture) }
                }
            }
        }
    }

    private fun drawInfo(canvas: Canvas, text: String, centerX: Float, edge: Float, frame: PageFrame, alignTop: Boolean) {
        if (text.isEmpty() || frame.band == 0f) return
        info.textSize = frame.infoSize
        info.getFontMetrics(metrics)
        val width = info.measureText(text)
        val available = frame.pageWidth - 2 * frame.margin
        val shown = if (width <= available) text else text.take(info.breakText(text, true, available, null))
        val baseline = if (alignTop) edge + (frame.band - (metrics.descent - metrics.ascent)) / 2 - metrics.ascent else edge - (frame.band - (metrics.descent - metrics.ascent)) / 2 - metrics.descent
        canvas.drawText(shown, centerX - info.measureText(shown) / 2, baseline, info)
    }

    private fun drawHighlight(canvas: Canvas, line: TextLine, range: IntRange) {
        if (line.end <= range.first || line.start > range.last) return
        fill.color = highlightColor
        for (glyph in line.glyphs) {
            if (glyph.end <= range.first || glyph.start > range.last) continue
            canvas.drawRect(line.x - line.bodyWidth / 2, glyph.y, line.x + line.bodyWidth / 2, glyph.y + glyph.advance, fill)
        }
        fill.color = ink
    }

    private fun drawLine(canvas: Canvas, line: TextLine, fontSize: Float, picture: (String) -> Bitmap?) {
        for (glyph in line.glyphs) drawGlyph(canvas, glyph, line.x, picture)
        val rubyX = line.x + line.bodyWidth / 2 + line.rightBand / 2
        for (ruby in line.rubies) {
            val count = ruby.text.codePointCount(0, ruby.text.length)
            val pitch = ruby.length / count
            var i = 0
            var index = 0
            while (i < ruby.text.length) {
                val width = Character.charCount(ruby.text.codePointAt(i))
                drawUpright(canvas, ruby.text.substring(i, i + width), rubyX, ruby.y + pitch * index + (pitch - ruby.size) / 2, ruby.size, regular)
                i += width
                index++
            }
        }
        for (run in line.emphasis) drawEmphasis(canvas, run, line)
        stroke.strokeWidth = fontSize * 0.05f
        for (run in line.sideLines) drawSideLine(canvas, run, line, fontSize)
    }

    private fun drawGlyph(canvas: Canvas, glyph: Glyph, x: Float, picture: (String) -> Bitmap?) {
        val paint = if (glyph.bold) bold else regular
        when (val content = glyph.content) {
            is GlyphContent.Chars -> when (content.orientation) {
                Orientation.Upright -> drawUpright(canvas, content.text, x, glyph.y, glyph.size, paint)
                Orientation.TateChuYoko -> drawTateChuYoko(canvas, content.text, x, glyph.y, glyph.size, paint)
                Orientation.Sideways -> drawSideways(canvas, content.text, x, glyph.y, glyph.size, paint)
            }
            is GlyphContent.Dash -> {
                stroke.strokeWidth = glyph.size * 0.06f
                canvas.drawLine(x, glyph.y, x, glyph.y + glyph.advance, stroke)
            }
            is GlyphContent.Leader -> {
                val dots = content.ems * 3
                val pitch = glyph.advance / dots
                repeat(dots) { canvas.drawCircle(x, glyph.y + pitch * (it + 0.5f), glyph.size * 0.06f, fill) }
            }
            is GlyphContent.Image -> drawPicture(canvas, picture(content.path), x - glyph.size / 2, glyph.y, glyph.size, glyph.size)
        }
    }

    /** One upright cluster centered in the em square whose top is [top]. */
    private fun drawUpright(canvas: Canvas, text: String, x: Float, top: Float, size: Float, paint: Paint) {
        paint.textSize = size
        paint.textScaleX = 1f
        paint.getFontMetrics(metrics)
        canvas.drawText(text, x - paint.measureText(text) / 2, top + size / 2 - (metrics.ascent + metrics.descent) / 2, paint)
    }

    /** Horizontal text squeezed to one em when it is wider. */
    private fun drawTateChuYoko(canvas: Canvas, text: String, x: Float, top: Float, size: Float, paint: Paint) {
        paint.textSize = size
        paint.fontFeatureSettings = null
        val width = paint.measureText(text)
        paint.textScaleX = if (width > size) size / width else 1f
        paint.getFontMetrics(metrics)
        canvas.drawText(text, x - minOf(width, size) / 2, top + size / 2 - (metrics.ascent + metrics.descent) / 2, paint)
        paint.textScaleX = 1f
        paint.fontFeatureSettings = "'vert' 1"
    }

    /** Text turned a quarter clockwise, starting at [top], centered on the column. */
    private fun drawSideways(canvas: Canvas, text: String, x: Float, top: Float, size: Float, paint: Paint) {
        paint.textSize = size
        paint.fontFeatureSettings = null
        paint.getFontMetrics(metrics)
        canvas.withRotation(90f, x, top) { drawText(text, x, top - (metrics.ascent + metrics.descent) / 2, paint) }
        paint.fontFeatureSettings = "'vert' 1"
    }

    private fun drawEmphasis(canvas: Canvas, run: EmphasisRun, line: TextLine) {
        val x = when (run.side) {
            MarkSide.Right -> line.x + line.bodyWidth / 2 + line.rightBand / 2
            MarkSide.Left -> line.x - line.bodyWidth / 2 - line.leftBand / 2
        }
        for (center in run.centers) drawUpright(canvas, run.mark.glyph, x, center - run.size / 2, run.size, regular)
    }

    private fun drawSideLine(canvas: Canvas, run: SideLineRun, line: TextLine, fontSize: Float) {
        val offset = fontSize * 0.12f
        val x = when (run.side) {
            MarkSide.Right -> line.x + line.bodyWidth / 2 + offset
            MarkSide.Left -> line.x - line.bodyWidth / 2 - offset
        }
        val w = stroke.strokeWidth
        when (run.style) {
            LineStyle.Solid -> canvas.drawLine(x, run.y0, x, run.y1, stroke)
            LineStyle.Double -> {
                canvas.drawLine(x - w * 1.5f, run.y0, x - w * 1.5f, run.y1, stroke)
                canvas.drawLine(x + w * 1.5f, run.y0, x + w * 1.5f, run.y1, stroke)
            }
            LineStyle.Dashed, LineStyle.Chain -> {
                stroke.pathEffect = if (run.style == LineStyle.Dashed) DashPathEffect(floatArrayOf(w * 6, w * 4), 0f) else DashPathEffect(floatArrayOf(w * 6, w * 3, w, w * 3), 0f)
                path.reset()
                path.moveTo(x, run.y0)
                path.lineTo(x, run.y1)
                canvas.drawPath(path, stroke)
                stroke.pathEffect = null
            }
            LineStyle.Wavy -> {
                path.reset()
                path.moveTo(x, run.y0)
                val wave = fontSize / 4
                var y = run.y0
                while (y < run.y1) {
                    y = minOf(run.y1, y + wave / 8)
                    path.lineTo(x + w * 1.5f * sin(2 * PI * (y - run.y0) / wave).toFloat(), y)
                }
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun drawPicture(canvas: Canvas, bitmap: Bitmap?, left: Float, top: Float, width: Float, height: Float) {
        if (bitmap != null) {
            drawFitted(canvas, bitmap, left, top, width, height)
        } else {
            stroke.strokeWidth = 1f
            canvas.drawRect(left + 1, top + 1, left + width - 1, top + height - 1, stroke)
            canvas.drawLine(left, top, left + width, top + height, stroke)
            canvas.drawLine(left + width, top, left, top + height, stroke)
        }
    }

    private fun drawFitted(canvas: Canvas, bitmap: Bitmap, left: Float, top: Float, width: Float, height: Float) {
        val scale = minOf(width / bitmap.width, height / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        rect.set(left + (width - w) / 2, top + (height - h) / 2, left + (width + w) / 2, top + (height + h) / 2)
        canvas.drawBitmap(bitmap, null, rect, null)
    }
}

internal fun argb(color: Rgb): Int = 0xFF000000.toInt() or color.value
