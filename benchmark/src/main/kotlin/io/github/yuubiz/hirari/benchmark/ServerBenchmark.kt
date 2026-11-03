package io.github.yuubiz.hirari.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SERVER_NAME = "hirari-perf-nas"
private const val SETTLE_MS = 10_000L

/**
 * P4 of docs/spec.md section 5.2 on the reference NAS (section 5.1): the tap on the standard corpus in the file list
 * of an SMB place to its first page. The test skips unless the instrumentation arguments name a server:
 * `smbHost`, `smbShare`, `smbBook` (the file in the folder), and optionally `smbPort` (445), `smbFolder`,
 * `smbUser` and `smbPassword` (a guest when empty).
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class ServerBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val driver = Driver(InstrumentationRegistry.getInstrumentation())

    @Test
    fun p4FirstPageOnServer() {
        val host = argument("smbHost")
        val share = argument("smbShare")
        val book = argument("smbBook")
        assumeTrue("no server: pass smbHost, smbShare and smbBook", host != null && share != null && book != null)
        val server = ServerSettings(host!!, argument("smbPort") ?: "445", share!!, argument("smbFolder").orEmpty(), argument("smbUser").orEmpty(), argument("smbPassword").orEmpty())
        rule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(TraceSectionMetric("hirari.firstPage")),
            compilationMode = CompilationMode.Partial(),
            iterations = intArgument("iterations", 3),
            setupBlock = {
                driver.startClean()
                startActivityAndWait()
                driver.addServer(SERVER_NAME, server)
                driver.openPlace(SERVER_NAME, book!!, onNetwork = true)
            },
        ) {
            driver.open(book!!)
            Thread.sleep(SETTLE_MS)
        }
    }
}
