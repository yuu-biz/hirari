package io.github.yuubiz.hirari.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P6 in docs/spec.md section 5.2: cold start to the home screen. [coldStart] is the target's measurement, with the
 * baseline profile installed. [coldStartWithoutProfile] is the same start with nothing compiled, so the two show
 * what the profile buys.
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun coldStart() = coldStart(CompilationMode.Partial())

    @Test
    fun coldStartWithoutProfile() = coldStart(CompilationMode.None())

    private fun coldStart(compilationMode: CompilationMode) = rule.measureRepeated(
        packageName = "io.github.yuubiz.hirari",
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        iterations = 10,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
    }
}
