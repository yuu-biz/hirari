package io.github.yuubiz.hirari.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SETTLE_MS = 6_000L

/** P9 of docs/spec.md section 5.2: a text of about 1 MB from the tap in the file list to its first page. */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class TextBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = Driver(instrumentation)
    private val book = Staging(instrumentation, driver.device).textBook()

    @Test
    fun p9TextFirstPage() = rule.measureFromPlace(
        driver,
        TEXT_PLACE,
        listOf(TraceSectionMetric("hirari.textFirstPage")),
        CompilationMode.Partial(),
        prepare = { driver.openPlace(TEXT_PLACE, book) },
    ) {
        driver.open(book)
        Thread.sleep(SETTLE_MS)
    }
}
