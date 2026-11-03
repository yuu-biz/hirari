package io.github.yuubiz.hirari.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val LIST_SETTLE_MS = 1_000L
private const val COVERS_SETTLE_MS = 5_000L
private const val COVERS_STORED_MS = 10_000L

/**
 * P7 and P8 of docs/spec.md section 5.2 on the real file list, in places the test stages on the shared storage: 1000
 * books in one folder, and 20 books of one standard page each.
 */
@OptIn(ExperimentalMetricApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val driver = Driver(instrumentation)
    private val device = driver.device
    private val staging = Staging(instrumentation, device)

    /**
     * P7: the query of a folder of 1000 books. The list is built from one query, so the first item and all items are
     * the one `hirari.listFolder` section; the screen draws after it.
     */
    @Test
    fun p7ListThousandItems() {
        staging.bigFolder()
        rule.measureFromPlace(
            driver,
            LIST_PLACE,
            listOf(TraceSectionMetric("hirari.listFolder", TraceSectionMetric.Mode.First), TraceSectionMetric("hirari.listFolder", TraceSectionMetric.Mode.Max)),
            CompilationMode.Partial(),
        ) {
            driver.openPlace(LIST_PLACE, LIST_FIRST)
            Thread.sleep(LIST_SETTLE_MS)
        }
    }

    /** P8, generation: the covers of the visible rows when the store is empty. */
    @Test
    fun p8CoversGenerated() {
        val first = staging.thumbnailBooks()
        rule.measureFromPlace(driver, THUMBNAIL_PLACE, listOf(ThumbnailFillMetric()), CompilationMode.Partial()) {
            driver.openPlace(THUMBNAIL_PLACE, first)
            Thread.sleep(COVERS_SETTLE_MS)
        }
    }

    /**
     * P8, store: the covers of the visible rows from the disk store, with nothing decoded in memory. The app starts
     * again on the folder it last showed, so the start is inside the measured block.
     */
    @Test
    fun p8CoversFromStore() {
        val first = staging.thumbnailBooks()
        rule.measureFromPlace(
            driver,
            THUMBNAIL_PLACE,
            listOf(ThumbnailFillMetric()),
            CompilationMode.Partial(),
            prepare = {
                driver.openPlace(THUMBNAIL_PLACE, first)
                Thread.sleep(COVERS_STORED_MS)
                killProcess()
                pressHome()
            },
        ) {
            startActivityAndWait()
            check(device.wait(androidx.test.uiautomator.Until.hasObject(By.pkg(PACKAGE).text(first)), TIMEOUT_MS)) {
                "the app did not come back to the folder"
            }
            Thread.sleep(COVERS_SETTLE_MS)
        }
    }
}
