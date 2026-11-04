package io.github.yuubiz.hirari.benchmark

import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAP_INTERVAL_MS = 300L
private const val JUMP_INTERVAL_MS = 800L
private const val PAGE_BAR_CENTER_DP = 45
private const val STEP_PAGES = 100

/** The default tap zones (docs/spec.md section 4.8): the right 10% turns back and the rest turns forward. */
private const val BACK_BAND_PERCENT = 95

/** A tap on the page bar opens the page panel; the first one after a book is first read only closes the operation guide. */
private const val PANEL_ATTEMPTS = 3
private const val MEMORY_SAMPLE_EVERY = 10

/** A view of the default spreads has two stops at most; the taps past those turn. */
private const val TAPS_PER_TURN = 6
private const val TAG = "HirariBench"

/**
 * P1 to P4 and P10 of docs/spec.md section 5.2 on the real reader. Each test adds [READER_PLACE] as a place, opens the
 * book from its file list as a reader does, and drives it with taps. The book is the standard corpus when it was
 * pushed to the device (AGENTS.md) and a stand-in otherwise, which makes the numbers of a stand-in meaningless
 * beyond showing that the test runs.
 *
 * A turn is a change of the view on screen. With 停止点 on (docs/spec.md section 4.8, the default), a tap first steps
 * to the next stop of the view, so each turn taps until the view changes, read from the page's description for a
 * screen reader between the taps. The turns go forward to the last view and back to the first, and again, until
 * the count is reached; they never turn past either end of the book.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ReaderBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = Driver(instrumentation)
    private val device = driver.device
    private val book = Staging(instrumentation, device).readerBook()
    private val views = ViewSpanReader(driver)

    /** P1: the turn to a prepared view, from the input event to the frame that shows it. 200 turns per trial. */
    @Test
    fun p1TurnLatency() = measureTurns(intArgument("turns", 200))

    /** P2: the dropped frames over 100 consecutive turns. */
    @Test
    fun p2TurnJank() = measureTurns(intArgument("turns", 100))

    /**
     * P3: 50 jumps through the page panel, in cycles of 100 pages forward, 1 page forward, 100 pages back, 1 page
     * forward, so that each lands on a page not seen before and the decoded pages that the reader keeps do not
     * answer it. A jump to a view that was prepared is counted as a turn (`turnCount`), the others as `jumpCount`.
     */
    @Test
    fun p3JumpLatency() = rule.measureFromPlace(
        driver,
        READER_PLACE,
        listOf(FrameTimingMetric(), TurnMetric(), TurnMetric("hirari.jump", "jump"), prepareTime(TraceSectionMetric.Mode.Average), prepareTime(TraceSectionMetric.Mode.Max)),
        CompilationMode.Full(),
        prepare = {
            openBook()
            openPagePanel()
        },
    ) {
        val cycle = listOf(
            stepButton("a11y_step_forward", STEP_PAGES),
            stepButton("a11y_step_forward", 1),
            stepButton("a11y_step_back", STEP_PAGES),
            stepButton("a11y_step_forward", 1),
        )
        repeat(intArgument("jumps", 50)) {
            driver.click(cycle[it % cycle.size])
            Thread.sleep(JUMP_INTERVAL_MS)
        }
    }

    /** P4: from the tap on the book in the file list to the first page drawn, with the profile installed. */
    @Test
    fun p4FirstPage() = rule.measureFromPlace(
        driver,
        READER_PLACE,
        listOf(TraceSectionMetric("hirari.firstPage"), prepareTime(TraceSectionMetric.Mode.Max)),
        CompilationMode.Partial(),
        prepare = { driver.openPlace(READER_PLACE, book) },
    ) {
        driver.open(book)
        Thread.sleep(FIRST_PAGE_SETTLE_MS)
    }

    /** P10: the memory while reading the whole book, with `dumpsys meminfo` sampled every 10 pages into the log. */
    @Test
    fun p10Memory() = rule.measureFromPlace(
        driver,
        READER_PLACE,
        listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max), MemoryUsageMetric(MemoryUsageMetric.Mode.Last)),
        CompilationMode.Partial(),
        prepare = { openBook() },
    ) {
        var view = views.read()
        var peakKb = sampleMemory(view.first)
        var sampled = view.first / MEMORY_SAMPLE_EVERY
        while (!view.atEnd) {
            view = turn(forward = true, from = view)
            if (view.first / MEMORY_SAMPLE_EVERY != sampled) {
                sampled = view.first / MEMORY_SAMPLE_EVERY
                peakKb = maxOf(peakKb, sampleMemory(view.first))
            }
        }
        Log.i(TAG, "P10 peak java+native heap ${peakKb / 1024} MB")
    }

    private fun measureTurns(turns: Int) = rule.measureFromPlace(
        driver,
        READER_PLACE,
        listOf(
            FrameTimingMetric(),
            TurnMetric(),
            TurnMetric("hirari.jump", "lateTurn"),
            prepareTime(TraceSectionMetric.Mode.Average),
            prepareTime(TraceSectionMetric.Mode.Max),
        ),
        CompilationMode.Full(),
        prepare = { openBook() },
    ) {
        var view = views.read()
        var forward = true
        repeat(turns) {
            if (forward && view.atEnd || !forward && view.atStart) forward = !forward
            view = turn(forward, view)
        }
    }

    /** Taps until the view is no longer [from], and returns the view it turned to. */
    private fun turn(forward: Boolean, from: ViewSpan): ViewSpan {
        val x = if (forward) device.displayWidth / 4 else device.displayWidth * BACK_BAND_PERCENT / 100
        repeat(TAPS_PER_TURN) {
            tap(x, device.displayHeight / 2)
            val view = views.read()
            if (view != from) return view
        }
        driver.fail("$TAPS_PER_TURN taps ${if (forward) "forward" else "back"} did not turn from $from")
    }

    /** Every trial starts from cleared data, so the book is a first read and the operation guide takes the first tap. */
    private fun openBook() {
        driver.openFromFileList(READER_PLACE, book)
        Thread.sleep(SETTLE_MS)
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        Thread.sleep(SETTLE_MS)
    }

    private fun tap(x: Int, y: Int) {
        device.click(x, y)
        Thread.sleep(TAP_INTERVAL_MS)
    }

    private fun openPagePanel() {
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val x = device.displayWidth / 2
        val y = device.displayHeight - (PAGE_BAR_CENTER_DP * density).toInt()
        repeat(PANEL_ATTEMPTS) {
            device.click(x, y)
            Log.i(TAG, "page bar tap at $x,$y")
            if (device.wait(Until.hasObject(stepButton("a11y_step_forward")), SETTLE_MS)) return
        }
        error("the page panel did not open")
    }

    /** The step buttons of the page panel are found by what a screen reader says of them. */
    private fun stepButton(description: String, pages: Int = STEP_PAGES) = By.pkg(PACKAGE).desc(driver.appString(description, pages))

    /** Java and native heap of the app in KB, from the app summary of `dumpsys meminfo`. */
    private fun sampleMemory(page: Int): Long {
        val summary = device.executeShellCommand("dumpsys meminfo $PACKAGE")
        fun kb(label: String) = Regex("$label:\\s+(\\d+)").find(summary)?.groupValues?.get(1)?.toLong() ?: 0L
        val java = kb("Java Heap")
        val native = kb("Native Heap")
        Log.i(TAG, "P10 page=$page javaHeapKb=$java nativeHeapKb=$native")
        return java + native
    }

    private fun prepareTime(mode: TraceSectionMetric.Mode): Metric = TraceSectionMetric("hirari.prepare", mode)
}

private const val SETTLE_MS = 1_500L

/** The first page has to be drawn before the measured block ends, or the section is not counted at all. */
private const val FIRST_PAGE_SETTLE_MS = 5_000L
