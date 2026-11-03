package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.image.PixelSize
import io.github.yuubiz.hirari.core.model.PageShape
import io.github.yuubiz.hirari.core.model.PageView
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.feature.reader.prefetch.PageSteps
import kotlin.math.abs

/** The page of the strip under [screenY], in screen pixels from the top of the screen. */
internal fun ReaderState.stripPageAt(screenY: Float): Int? {
    val scroll = scroll ?: return null
    val screen = stripScreen
    return strip.pageAt((scroll.top(strip, screen) + screenY) / screen.scale)
}

/** The loupe of 縦に連続, which magnifies the page under the finger at its scale on screen. */
internal fun ReaderState.stripLoupeView(loupe: Loupe, nowMs: Long): LoupeView? {
    val page = stripPageAt(loupe.finger.y) ?: return null
    val shape = pagination.shapes[page] as? PageShape.Known ?: return null
    val scale = (stripScreen.scale / shape.width).toFloat()
    return LoupeView(loupe.rectAt(nowMs, viewport.size, config.loupeFrame), loupe.finger, config.loupeZoom.magnification(scale))
}

/**
 * The page under the loupe read at the size of its own image, for the magnifications of the source image, or `null`
 * when its prepared page, as wide as the screen, already has a pixel for every pixel of the image.
 */
internal fun ReaderState.stripLoupeSource(): LoupeSource? {
    val finger = loupe?.finger ?: return null
    val page = stripPageAt(finger.y) ?: return null
    val shape = pagination.shapes[page] as? PageShape.Known ?: return null
    if (viewport.width / shape.width >= 1f) return null
    return LoupeSource(PageView.One(page), 0, PixelSize(shape.width, shape.height), PageSteps(PagePart.Whole, config.rotation, config.adjust.sharpen))
}

/**
 * The pan that moves the strip of 縦に連続 [distancePx] toward [turn], as a long sound of 息での操作 scrolls it, up to
 * the end of the strip. `null` outside 縦に連続 and when the screen is at that end already.
 */
fun ReaderState.stripPan(turn: Turn, distancePx: Float): ReaderEvent.Pan? {
    val scroll = scroll ?: return null
    val screen = stripScreen
    val from = scroll.top(strip, screen)
    val to = scroll.panned(0f, if (turn == Turn.Forward) -distancePx else distancePx, strip, screen).top(strip, screen)
    if (abs(to - from) < SAME_PX) return null
    return ReaderEvent.Pan(0f, (from - to).toFloat())
}

/** Positions closer than this, in pixels, count as the same. */
private const val SAME_PX = 0.5
