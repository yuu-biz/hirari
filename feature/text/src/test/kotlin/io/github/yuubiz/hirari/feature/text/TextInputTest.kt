package io.github.yuubiz.hirari.feature.text

import io.github.yuubiz.hirari.core.model.TapLayout
import io.github.yuubiz.hirari.core.model.VolumeKeys
import io.github.yuubiz.hirari.core.model.Size
import io.github.yuubiz.hirari.core.model.ReaderRegions
import io.github.yuubiz.hirari.core.data.GlobalSettings
import io.github.yuubiz.hirari.core.model.KeyCodes
import io.github.yuubiz.hirari.core.model.KeyResult
import io.github.yuubiz.hirari.core.model.PageBar
import io.github.yuubiz.hirari.core.model.PageBarEvent
import io.github.yuubiz.hirari.core.model.PageBarState
import io.github.yuubiz.hirari.core.model.Point
import io.github.yuubiz.hirari.core.model.ReaderAction
import io.github.yuubiz.hirari.core.model.ReadingDirection
import io.github.yuubiz.hirari.core.model.Turn
import io.github.yuubiz.hirari.core.model.TurnAnimation
import io.github.yuubiz.hirari.core.model.TurnEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextInputTest {
    private val defaults = GlobalSettings()

    private fun views(code: Int, settings: GlobalSettings = defaults) =
        (settings.textKeys().onKey(code, pressed = true) as? KeyResult.Act)?.action?.textTurn()?.views

    @Test
    fun `opening input routes taps and keys with defaults and loaded settings`() {
        val regions = ReaderRegions(Size(400f, 600f), 40f)
        val turns = ArrayList<Turn>()
        fun tap(settings: GlobalSettings?, point: Point) = textTap(settings, point, regions) { turns += it }
        fun key(settings: GlobalSettings?, code: Int, pressed: Boolean = true, shift: Boolean = false) =
            textKey(settings, code, pressed, shift) { it.textTurn()?.let(turns::add) }
        tap(null, Point(20f, 300f))
        key(null, KeyCodes.DPAD_LEFT)
        key(null, KeyCodes.DPAD_LEFT, pressed = false)
        tap(defaults.copy(mirrorControls = true), Point(20f, 300f))
        key(defaults.copy(mirrorControls = true), KeyCodes.DPAD_LEFT)
        tap(null, Point(20f, 20f))
        tap(null, Point(20f, 580f))
        key(defaults.copy(volumeKeys = VolumeKeys.Off), KeyCodes.VOLUME_DOWN)
        key(null, KeyCodes.DPAD_UP)
        assertEquals(listOf(Turn.Forward, Turn.Forward, Turn.Back, Turn.Back), turns)
    }

    @Test
    fun `opening taps respect vertical zones and the inert middle`() {
        val regions = ReaderRegions(Size(400f, 600f), 40f)
        val settings = defaults.copy(tapLayout = TapLayout.TopAndBottomBands, tapBandPercent = 20)
        val turns = ArrayList<Turn>()
        for (y in listOf(50f, 300f, 550f)) textTap(settings, Point(200f, y), regions) { turns += it }
        assertEquals(listOf(Turn.Back, Turn.Forward), turns)
    }

    @Test
    fun `the left key and the volume keys turn a text bound on the right`() {
        assertEquals(1, views(KeyCodes.DPAD_LEFT))
        assertEquals(-1, views(KeyCodes.DPAD_RIGHT))
        assertEquals(-1, views(KeyCodes.DPAD_LEFT, defaults.copy(mirrorControls = true)))
        assertEquals(1, views(KeyCodes.VOLUME_DOWN))
        assertEquals(-1, views(KeyCodes.VOLUME_UP))
    }

    @Test
    fun `shifting the pairing does nothing in a text`() {
        assertEquals(null, views(KeyCodes.DPAD_UP))
        assertEquals(null, ReaderAction.Shift(forward = true).textTurn())
        assertEquals(Turn.Forward, ReaderAction.StepStops(Turn.Forward).textTurn())
    }

    private val partial = PageRun(intArrayOf(100, 200, 300), end = 400, fromStart = false, complete = false, sourceLength = 1000)

    @Test
    fun `an end page picked at the edge of the bar goes to the end of the text before it is all laid out`() {
        assertEquals(PageBarJump.Last, pageBarJump(2, atEdge = true, partial))
        assertEquals(PageBarJump.First, pageBarJump(0, atEdge = true, partial))
        assertEquals(PageBarJump.Page(300), pageBarJump(2, atEdge = false, partial))
        val whole = PageRun(intArrayOf(0, 100, 200), end = 300, fromStart = true, complete = true, sourceLength = 300)
        assertEquals(PageBarJump.Page(200), pageBarJump(2, atEdge = true, whole))
        assertEquals(PageBarJump.Page(0), pageBarJump(0, atEdge = true, whole))
    }

    @Test
    fun `a page of the run pressed on is found again in the run laid out since`() {
        val grown = PageRun(intArrayOf(0, 50, 100, 200, 300), end = 400, fromStart = true, complete = false, sourceLength = 1000)
        assertEquals(4, pageInLaterRun(partial, 2, grown))
        assertEquals(2, pageInLaterRun(partial, 0, grown))
        assertEquals(4, pageInLaterRun(partial, 9, grown))
    }

    private val turning = TextMotion.Turning(from = 30, TurnAnimation.turn(TurnEffect.Cover, movement = 1, nowMs = 0, durationMs = 100))

    @Test
    fun `a key action ends the effect that is running before it moves the view`() {
        val seen = ArrayList<String>()
        for (action in listOf(ReaderAction.First, ReaderAction.Last, ReaderAction.TurnView(Turn.Forward), ReaderAction.StepStops(Turn.Back), ReaderAction.OpenMenu, ReaderAction.Dismiss, ReaderAction.Shift(forward = true))) {
            val touch = TextTouch().apply { motion = turning }
            fun at(name: String): () -> Unit = { seen += "$name:${touch.motion}" }
            touch.perform(action, TextActions(turn = { seen += "turn:${touch.motion}" }, first = at("first"), last = at("last"), toggleMenu = at("menu"), dismiss = at("dismiss")))
            assertNull(touch.motion, "$action")
        }
        assertEquals(listOf("first:null", "last:null", "turn:null", "turn:null", "menu:null", "dismiss:null"), seen)
    }

    private val bar = PageBar(left = 0f, right = 400f, top = 560f, height = 40f, pageCount = 3, direction = ReadingDirection.RightToLeft, touchSlop = 8f)

    /** A press at [from] on [page], a slide to [to], and the release there, as the bar's reducer sees them. */
    private fun release(page: Int, from: Float, to: Float, run: PageRun, y: Float = 580f): PageBarJump? {
        val down = bar.reduce(PageBarState.Idle, PageBarEvent.Down(Point(from, 580f), 0, page)).state
        val slid = bar.reduce(down, PageBarEvent.Move(Point(to, y))).state
        val up = PageBarEvent.Up(Point(to, y))
        return bar.releaseJump(slid, up, bar.reduce(slid, up).effect, run)
    }

    @Test
    fun `sliding to the edge of the bar picks the end of the text even from the end page of what is laid out`() {
        assertEquals(PageBarJump.First, release(page = 0, from = 200f, to = 395f, partial))
        assertEquals(PageBarJump.Last, release(page = 2, from = 200f, to = 5f, partial))
        assertEquals(PageBarJump.Last, release(page = 1, from = 200f, to = 5f, partial))
    }

    @Test
    fun `a slide that stays on the page, is cancelled, or ends away from the edge goes nowhere`() {
        assertNull(release(page = 0, from = 200f, to = 205f, partial))
        assertNull(release(page = 0, from = 200f, to = 395f, partial, y = 400f))
        assertNull(release(page = 0, from = 200f, to = 395f, PageRun(intArrayOf(0, 100, 200), end = 300, fromStart = true, complete = true, sourceLength = 300)))
        assertNull(release(page = 2, from = 200f, to = 5f, PageRun(intArrayOf(0, 100, 200), end = 300, fromStart = true, complete = true, sourceLength = 300)))
    }
}
