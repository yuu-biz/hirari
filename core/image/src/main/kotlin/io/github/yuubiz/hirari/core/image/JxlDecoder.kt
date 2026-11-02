package io.github.yuubiz.hirari.core.image

import android.graphics.Bitmap
import java.util.function.BooleanSupplier

/** What a JPEG XL header declares. The sides are after the image's orientation, as the decoder outputs them. */
data class JxlHeader(val width: Int, val height: Int, val hasAnimation: Boolean)

enum class JxlFailure {
    Corrupt,
    Truncated,

    /** An allocation would have passed the memory limit, and libjxl stopped. */
    OverLimit,
    Cancelled,
}

sealed interface JxlHeaderResult {
    data class Read(val header: JxlHeader) : JxlHeaderResult

    data class Failed(val failure: JxlFailure) : JxlHeaderResult
}

sealed interface JxlDecodeResult {
    /** [peakNativeBytes] is the most that libjxl and the downscaling held at once. */
    data class Decoded(val bitmap: Bitmap, val peakNativeBytes: Long) : JxlDecodeResult

    data class Failed(val failure: JxlFailure) : JxlDecodeResult
}

/**
 * libjxl behind JNI (docs/spec.md sections 3.6 and 8.3). Decodes the first frame only, on the calling thread, in
 * sRGB where libjxl can convert without a colour management system. Safe to call from several threads at once.
 */
object JxlDecoder {
    fun header(bytes: ByteArray): JxlHeaderResult {
        val out = IntArray(3)
        val status = nativeHeader(bytes, out)
        return failureOf(status)?.let(JxlHeaderResult::Failed)
            ?: JxlHeaderResult.Read(JxlHeader(out[0], out[1], out[2] != 0))
    }

    /**
     * Decodes [bytes], whose header is [header], averaging each [sampleSize] x [sampleSize] square of pixels
     * into one, into a new ARGB_8888 bitmap of `sampled(side, sampleSize)` on each side. [sampleSize] is a power of
     * two. What libjxl allocates through its memory manager, and the sums of the averaging, may total
     * [memoryLimitBytes]; libjxl's own containers are not counted (docs/spec.md section 3.6). [isActive] is
     * checked before each group of pixels.
     */
    fun decode(
        bytes: ByteArray,
        header: JxlHeader,
        sampleSize: Int,
        memoryLimitBytes: Long,
        isActive: () -> Boolean,
    ): JxlDecodeResult {
        require(sampleSize > 0 && sampleSize and (sampleSize - 1) == 0) { "sample size $sampleSize" }
        require(dimensionRefusal(header.width, header.height) == null) { "implausible header $header" }
        val bitmap = Bitmap.createBitmap(
            sampled(header.width, sampleSize),
            sampled(header.height, sampleSize),
            Bitmap.Config.ARGB_8888,
        )
        val report = LongArray(2)
        val status = try {
            nativeDecode(bytes, bitmap, Integer.numberOfTrailingZeros(sampleSize), memoryLimitBytes, { !isActive() }, report)
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        failureOf(status)?.let {
            bitmap.recycle()
            return JxlDecodeResult.Failed(it)
        }
        bitmap.setHasAlpha(report[1] != 0L)
        return JxlDecodeResult.Decoded(bitmap, peakNativeBytes = report[0])
    }

    /** The status codes of jxl_jni.cc, 0 being success. */
    private fun failureOf(status: Int): JxlFailure? = when (status) {
        0 -> null
        1 -> JxlFailure.Corrupt
        2 -> JxlFailure.Truncated
        3 -> JxlFailure.OverLimit
        4 -> JxlFailure.Cancelled
        else -> error("jxl_jni.cc returned $status")
    }

    private external fun nativeHeader(bytes: ByteArray, out: IntArray): Int

    /** [stop] says whether to stop. [report] receives the peak native bytes and whether the image has alpha. */
    private external fun nativeDecode(
        bytes: ByteArray,
        bitmap: Bitmap,
        sampleShift: Int,
        memoryLimitBytes: Long,
        stop: BooleanSupplier,
        report: LongArray,
    ): Int

    init {
        System.loadLibrary("hirari_jxl")
    }
}
