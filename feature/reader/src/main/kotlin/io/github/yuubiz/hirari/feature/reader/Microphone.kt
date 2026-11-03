package io.github.yuubiz.hirari.feature.reader

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/** The loudness the microphone hears: the maximum amplitude of each window of 100 ms, from 0 to 32767. */
fun interface Microphone {
    /**
     * The microphone is on while this is collected. Nothing it hears is kept past its window. It ends by itself when the
     * microphone will not start, fails, or is taken from the app, as when another app holds it.
     */
    fun windows(): Flow<Int>
}

/** What [recordingWindows] needs of a recording, so that the ways it fails can be tested without a device. */
internal interface Recording {
    /** `false` when the system did not let the recording begin. */
    fun start(): Boolean

    /** The count of samples read, or a negative error. Blocks until some are there. */
    fun read(into: ShortArray, offset: Int, size: Int): Int

    /** The system gives this app silence in place of the input, as when a call holds the microphone (Android 10 and later). */
    val silenced: Boolean

    fun release()
}

/**
 * The windows of one [Recording], read on [context] since a read blocks. [open] is `null` when the system will not open
 * one. The flow ends when the recording will not start, fails, or is silenced, and the recording is released however
 * the flow ends.
 */
internal fun recordingWindows(context: CoroutineContext, open: () -> Recording?): Flow<Int> = flow {
    currentCoroutineContext().ensureActive()
    val recording = open() ?: return@flow
    try {
        currentCoroutineContext().ensureActive()
        if (!recording.start() || recording.silenced) return@flow
        val window = ShortArray(BREATH_WINDOW_SAMPLES)
        while (currentCoroutineContext().isActive) {
            var filled = 0
            while (filled < window.size) {
                val read = recording.read(window, filled, window.size - filled)
                if (read <= 0) return@flow
                filled += read
            }
            if (recording.silenced) return@flow
            emit(window.maxOf { abs(it.toInt()) }.coerceAtMost(Short.MAX_VALUE.toInt()))
        }
    } finally {
        recording.release()
    }
}.flowOn(context)

/**
 * The device's microphone at 8 kHz mono through [AudioRecord] (docs/spec.md section 4.11). The blocking reads run on
 * `Dispatchers.IO`. A microphone that will not start, as when another app holds it, gives no windows.
 */
object DeviceMicrophone : Microphone {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun windows(): Flow<Int> = recordingWindows(Dispatchers.IO, ::openRecord)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openRecord(): Recording? {
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(BREATH_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, format)
        if (minimum <= 0) return null
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, BREATH_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, format, maxOf(minimum, BREATH_WINDOW_SAMPLES * 2 * 2))
        } catch (_: IllegalArgumentException) {
            return null
        } catch (_: SecurityException) {
            return null
        }
        return AudioRecordRecording(record)
    }
}

private class AudioRecordRecording(private val record: AudioRecord) : Recording {
    override fun start(): Boolean {
        if (record.state != AudioRecord.STATE_INITIALIZED) return false
        try {
            record.startRecording()
        } catch (_: IllegalStateException) {
            return false
        }
        return record.recordingState == AudioRecord.RECORDSTATE_RECORDING
    }

    override fun read(into: ShortArray, offset: Int, size: Int): Int = record.read(into, offset, size)

    override val silenced: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && record.activeRecordingConfiguration?.isClientSilenced == true

    override fun release() = record.release()
}
