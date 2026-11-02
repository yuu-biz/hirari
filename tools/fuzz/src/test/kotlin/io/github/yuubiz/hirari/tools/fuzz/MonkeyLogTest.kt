package io.github.yuubiz.hirari.tools.fuzz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonkeyLogTest {
    private val app = "io.github.yuubiz.hirari"

    @Test
    fun `a quiet session has no failures`() {
        val counts = MonkeyCounts.of(app, ":Monkey: seed=1 count=100\nEvents injected: 100\n## Network stats: elapsed time=900ms\n", "")
        assertEquals(MonkeyCounts(100, 0, 0, 0, false), counts)
    }

    @Test
    fun `a Java crash is counted once from the output and the log together`() {
        val monkey = "// CRASH: $app (pid 4242)\n// Short Msg: java.lang.IllegalStateException\nEvents injected: 50\n"
        val log = "11-02 18:49:50.178  4242  4242 E AndroidRuntime: FATAL EXCEPTION: main\n" +
            "11-02 18:49:50.178  4242  4242 E AndroidRuntime: Process: $app, PID: 4242\n"
        assertEquals(MonkeyCounts(50, 1, 0, 0, false), MonkeyCounts.of(app, monkey, log))
    }

    @Test
    fun `a native crash and an ANR in the log are counted`() {
        val log = "F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 1 (main), pid 4242 (b.yuubiz.hirari)\n" +
            "E ActivityManager: ANR in $app (io.github.yuubiz.hirari/.MainActivity)\n" +
            "F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 1 (main), pid 99 (other.app)\n"
        assertEquals(MonkeyCounts(0, 0, 1, 1, false), MonkeyCounts.of(app, "", log))
    }

    private val quiet = ":Monkey: seed=1 count=100\nEvents injected: 100\n## Network stats: elapsed time=900ms\n"

    @Test
    fun `a session that injected what was asked and exited cleanly is normal`() {
        val counts = MonkeyCounts.of(app, quiet, "")
        assertNull(counts.abnormality(100, AdbResult(quiet, 0, timedOut = false)))
        assertFalse(counts.failed)
    }

    @Test
    fun `a hang that adb gave up on reads as no events and is abnormal`() {
        val output = ":Monkey: seed=1 count=100\n\n[timed out after 340 s]"
        val counts = MonkeyCounts.of(app, output, "")
        assertEquals(MonkeyCounts(0, 0, 0, 0, false), counts)
        assertEquals("monkey did not end in time and was stopped", counts.abnormality(100, AdbResult(output, -1, timedOut = true)))
    }

    @Test
    fun `a failing exit status is abnormal even when the events were all injected`() {
        assertEquals("monkey ended with status 1", MonkeyCounts.of(app, quiet, "").abnormality(100, AdbResult(quiet, 1, timedOut = false)))
    }

    @Test
    fun `fewer or more events than asked are abnormal`() {
        val counts = MonkeyCounts.of(app, quiet, "")
        assertEquals("monkey injected 100 of the 250 events asked for", counts.abnormality(250, AdbResult(quiet, 0, timedOut = false)))
        assertEquals("monkey injected 100 of the 50 events asked for", counts.abnormality(50, AdbResult(quiet, 0, timedOut = false)))
        val none = MonkeyCounts.of(app, "", "")
        assertEquals("monkey injected 0 of the 250 events asked for", none.abnormality(250, AdbResult("", 0, timedOut = false)))
    }

    @Test
    fun `an abnormal session fails the run and the counts keep it when added up`() {
        val abnormal = MonkeyCounts(0, 0, 0, 0, false, abnormal = 1)
        assertTrue(abnormal.failed)
        assertEquals(1, (MonkeyCounts.None + abnormal + MonkeyCounts.None).abnormal)
        assertFalse(MonkeyCounts.None.failed)
        assertTrue(MonkeyCounts(10, 1, 0, 0, false).failed)
        assertTrue(MonkeyCounts(10, 0, 0, 0, true).failed)
    }

    @Test
    fun `other apps and an abort`() {
        val monkey = "// CRASH: com.other (pid 7)\n** Monkey aborted due to error.\n// Monkey aborted\n"
        assertEquals(MonkeyCounts(0, 0, 0, 0, true), MonkeyCounts.of(app, monkey, ""))
    }
}
