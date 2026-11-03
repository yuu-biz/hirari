package io.github.yuubiz.hirari.feature.reader

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A recording the test scripts: the samples it gives, then how it ends. */
private class FakeRecording(
    private val started: Boolean = true,
    private val samples: List<Short> = emptyList(),
    private val silencedAfterReads: Int = Int.MAX_VALUE,
) : Recording {
    var released = false
    var starts = 0
    private var cursor = 0
    private var reads = 0

    override fun start(): Boolean {
        starts++
        return started
    }

    override fun read(into: ShortArray, offset: Int, size: Int): Int {
        reads++
        if (cursor >= samples.size) return -1
        val count = minOf(size, samples.size - cursor)
        repeat(count) { into[offset + it] = samples[cursor + it] }
        cursor += count
        return count
    }

    override val silenced: Boolean get() = reads > silencedAfterReads

    override fun release() {
        released = true
    }
}

private fun window(value: Short) = List(BREATH_WINDOW_SAMPLES) { value }

@OptIn(ExperimentalCoroutinesApi::class)
class MicrophoneTest {
    private val context = UnconfinedTestDispatcher()

    @Test
    fun itGivesTheLargestAmplitudeOfEachWindowAndLetsGoWhenTheReadingEnds() = runTest(context) {
        val recording = FakeRecording(samples = window(300) + window(-9000) + window(5))
        assertEquals(listOf(300, 9000, 5), recordingWindows(context) { recording }.toList())
        assertTrue(recording.released)
    }

    @Test
    fun aRecordingTheSystemWillNotOpenGivesNoWindows() = runTest(context) {
        assertEquals(emptyList(), recordingWindows(context) { null }.toList())
    }

    @Test
    fun aRecordingThatDoesNotStartGivesNoWindowsAndIsReleased() = runTest(context) {
        val recording = FakeRecording(started = false, samples = window(300))
        assertEquals(emptyList(), recordingWindows(context) { recording }.toList())
        assertTrue(recording.released)
    }

    @Test
    fun aRecordingTheSystemSilencedStopsGivingWindowsAndIsReleased() = runTest(context) {
        val recording = FakeRecording(samples = window(300) + window(0) + window(0) + window(0), silencedAfterReads = 1)
        val heard = recordingWindows(context) { recording }.toList()
        assertTrue(heard.size < 2, "the window read after the system silenced it is not passed on: $heard")
        assertTrue(recording.released)
    }

    @Test
    fun aQuietRoomIsNotSilenced() = runTest(context) {
        val recording = FakeRecording(samples = window(0) + window(0) + window(0))
        assertEquals(listOf(0, 0, 0), recordingWindows(context) { recording }.toList())
    }

    @Test
    fun aRecordingThatEndsTheCollectionIsReleasedAtOnce() = runTest(context) {
        val recording = FakeRecording(samples = window(300) + window(300) + window(300))
        assertEquals(listOf(300), recordingWindows(context) { recording }.take(1).toList())
        assertTrue(recording.released)
    }

    @Test
    fun aCollectionStoppedBeforeTheStartDoesNotStartTheRecording() = runTest(context) {
        val recording = FakeRecording(samples = window(300))
        lateinit var collecting: Job
        collecting = launch(context, start = CoroutineStart.LAZY) {
            recordingWindows(context) {
                collecting.cancel()
                recording
            }.collect {}
        }
        collecting.start()
        collecting.join()
        assertEquals(0, recording.starts)
        assertTrue(recording.released)
    }
}
