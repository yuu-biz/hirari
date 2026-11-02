package io.github.yuubiz.hirari.tools.fuzz

import kotlin.test.Test
import kotlin.test.assertEquals

class OutcomeTest {
    @Test
    fun `a finished passing run`() {
        assertEquals(RunEnd.Passed, RunEnd.of("io.github.FuzzTest:.\n\nTime: 60.211\n\nOK (1 test)\n\n"))
    }

    @Test
    fun `a finished failing run`() {
        assertEquals(RunEnd.Failed, RunEnd.of("Time: 3.1\n\nFAILURES!!!\nTests run: 1,  Failures: 1\n"))
    }

    @Test
    fun `a process that died has no result`() {
        val output = "INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nINSTRUMENTATION_CODE: 0\n"
        assertEquals(RunEnd.Died, RunEnd.of(output))
    }

    @Test
    fun `a native crash is named by its signal and top frames`() {
        val log = """
            11-02 18:49:50.178  3850  3863 F libc    : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0 in tid 3863 (archive-session), pid 3850 (ge.image.test)
            11-02 18:49:50.250  3880  3880 F DEBUG   :       #00 pc 0000000000045a10  /data/app/~~x/io.github.yuubiz.hirari.core.image.test-y/base.apk!libhirari_archive.so (offset 0x1000) (archive_read_data_block+48) (BuildId: abc)
            11-02 18:49:50.250  3880  3880 F DEBUG   :       #01 pc 0000000000012345  /data/app/~~x/lib/x86_64/libhirari_archive.so (Java_x_nativeReadData+200)
            11-02 18:49:50.250  3880  3880 F DEBUG   :       #02 pc 00000000001a2b3c  /apex/com.android.art/lib64/libart.so (art_quick_generic_jni_trampoline+144)
        """.trimIndent()
        val signature = CrashSignature.of(log)
        assertEquals("SIGSEGV", signature.signal)
        assertEquals(listOf("archive_read_data_block", "Java_x_nativeReadData", "art_quick_generic_jni_trampoline"), signature.frames)
    }

    @Test
    fun `a log without a crash is unknown`() {
        assertEquals(CrashSignature.Unknown, CrashSignature.of("nothing here"))
    }

    @Test
    fun `the time log is summed over its lines`() {
        val log = "2026-11-03T00:00:00Z seed=1 seconds=600.5 executions=10\n2026-11-04T00:00:00Z seed=2 seconds=1799.5 executions=20\n"
        assertEquals(2400.0, FuzzDriver.accumulatedSeconds(log))
    }
}
