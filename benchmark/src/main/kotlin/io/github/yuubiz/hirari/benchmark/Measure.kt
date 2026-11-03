package io.github.yuubiz.hirari.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry

private const val DEFAULT_ITERATIONS = 3

/** An instrumentation argument: `-Pandroid.testInstrumentationRunnerArguments.<name>=<value>`. */
internal fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

internal fun intArgument(name: String, default: Int): Int = argument(name)?.toInt() ?: default

/**
 * [block] measured [DEFAULT_ITERATIONS] times (docs/spec.md section 5.2 asks for 3 trials), each from the home
 * screen of a fresh install with [place] added, so the reading position and the covers of an earlier trial are gone.
 * [prepare] runs after the place is added and before the measured block.
 */
internal fun MacrobenchmarkRule.measureFromPlace(
    driver: Driver,
    place: String,
    metrics: List<Metric>,
    compilationMode: CompilationMode,
    prepare: MacrobenchmarkScope.() -> Unit = {},
    block: MacrobenchmarkScope.() -> Unit,
) = measureRepeated(
    packageName = PACKAGE,
    metrics = metrics,
    compilationMode = compilationMode,
    iterations = intArgument("iterations", DEFAULT_ITERATIONS),
    setupBlock = {
        driver.startClean()
        startActivityAndWait()
        driver.addPlace(place)
        prepare()
    },
    measureBlock = block,
)
