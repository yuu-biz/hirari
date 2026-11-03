package io.github.yuubiz.hirari.feature.reader

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** The loudness the microphone hears: the maximum amplitude of each window of 100 ms, from 0 to 32767. */
fun interface Microphone {
    /** The microphone is on while this is collected. Nothing it hears is kept past its window. */
    fun windows(): Flow<Int>
}

/**
 * The device's microphone at 8 kHz mono through [AudioRecord] (docs/spec.md section 4.11). The blocking reads run on
 * `Dispatchers.IO`. A microphone that will not start, as when another app holds it, gives no windows.
 */
object DeviceMicrophone : Microphone {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun windows(): Flow<Int> = flow {
        val format = AudioFormat.ENCODING_PCM_16BIT
        val minimum = AudioRecord.getMinBufferSize(BREATH_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, format)
        if (minimum <= 0) return@flow
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, BREATH_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, format, maxOf(minimum, BREATH_WINDOW_SAMPLES * 2 * 2))
        } catch (_: IllegalArgumentException) {
            return@flow
        } catch (_: SecurityException) {
            return@flow
        }
        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) return@flow
            currentCoroutineContext().ensureActive()
            try {
                record.startRecording()
            } catch (_: IllegalStateException) {
                return@flow
            }
            val window = ShortArray(BREATH_WINDOW_SAMPLES)
            while (currentCoroutineContext().isActive) {
                var filled = 0
                while (filled < window.size) {
                    val read = record.read(window, filled, window.size - filled)
                    if (read <= 0) return@flow
                    filled += read
                }
                emit(window.maxOf { abs(it.toInt()) }.coerceAtMost(Short.MAX_VALUE.toInt()))
            }
        } finally {
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
