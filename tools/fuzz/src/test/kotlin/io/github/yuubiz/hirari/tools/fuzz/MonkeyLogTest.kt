package io.github.yuubiz.hirari.tools.fuzz

import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun `other apps and an abort`() {
        val monkey = "// CRASH: com.other (pid 7)\n** Monkey aborted due to error.\n// Monkey aborted\n"
        assertEquals(MonkeyCounts(0, 0, 0, 0, true), MonkeyCounts.of(app, monkey, ""))
    }
}
