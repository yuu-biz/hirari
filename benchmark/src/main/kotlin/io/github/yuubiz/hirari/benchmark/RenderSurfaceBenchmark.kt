package io.github.yuubiz.hirari.benchmark

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "io.github.yuubiz.hirari"
private const val TAP_INTERVAL_MS = 300L
private const val PINCHES = 5

/** P1 needs 200 turns per trial (docs/spec.md section 5.2); P2 uses the default 100. */
private val turns: Int
    get() = InstrumentationRegistry.getArguments().getString("turns")?.toInt() ?: 100

/**
 * Q1 and Q2 of docs/spec.md section 10, measured as docs/q1-render-surface.md describes. Each test opens one
 * prototype variant of the debug-and-benchmark-only route and drives forward taps or [PINCHES] pinch-out
 * and pinch-in pairs.
 */
@RunWith(AndroidJUnit4::class)
class RenderSurfaceBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test fun composeHardwareTurns() = turns("compose-hardware")

    @Test fun composeArgb8888Turns() = turns("compose-argb8888")

    @Test fun composeArgb8888PreparedTurns() = turns("compose-argb8888prepared")

    @Test fun viewHardwareTurns() = turns("view-hardware")

    @Test fun viewArgb8888Turns() = turns("view-argb8888")

    @Test fun viewArgb8888PreparedTurns() = turns("view-argb8888prepared")

    @Test fun composeHardwarePinch() = pinch("compose-hardware")

    @Test fun viewHardwarePinch() = pinch("view-hardware")

    @OptIn(ExperimentalMetricApi::class)
    private fun turns(variant: String) = measure(
        variant,
        listOf(
            FrameTimingMetric(),
            TurnMetric(),
            TraceSectionMetric("hirari.prepare", TraceSectionMetric.Mode.Average),
            TraceSectionMetric("hirari.prepare", TraceSectionMetric.Mode.Max),
        ),
    ) {
        val x = device.displayWidth / 4
        val y = device.displayHeight / 2
        repeat(turns) {
            device.click(x, y)
            Thread.sleep(TAP_INTERVAL_MS)
        }
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun pinch(variant: String) = measure(variant, listOf(FrameTimingMetric(), TurnMetric())) {
        val root = device.findObject(By.pkg(PACKAGE).depth(0))
        repeat(PINCHES) {
            root.pinchOpen(0.6f, 1500)
            root.pinchClose(0.6f, 1500)
        }
    }

    private fun measure(variant: String, metrics: List<Metric>, block: MacrobenchmarkScope.() -> Unit) =
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = metrics,
            compilationMode = CompilationMode.Full(),
            iterations = 3,
            setupBlock = {
                killProcess()
                startActivityAndWait(
                    Intent().setClassName(PACKAGE, "$PACKAGE.MainActivity").putExtra("prototype", variant),
                )
                Thread.sleep(1000)
            },
            measureBlock = block,
        )
}
