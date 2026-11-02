package io.github.yuubiz.hirari.core.image

import android.os.Build
import io.github.yuubiz.hirari.core.model.ImageType

/** How this device decodes one image type (docs/spec.md section 3.6). */
internal sealed interface Codec {
    /**
     * `ImageDecoder` decodes it.
     *
     * @property averagingLimit the largest sample size at which the decoder still averages pixels rather than
     *   picking them (docs/spec.md section 5.3). JPEG scales in the DCT down to 1/8, and libwebp's scaler averages
     *   at any ratio. The others sample by picking rows and columns, or are not known to average, so they decode
     *   whole and are halved afterwards.
     * @property regions whether `BitmapRegionDecoder` reads it, so tiles come from the encoded bytes (section 3.7).
     * @property needsDeviceDecoder whether a decoder error means the device lacks the decoder rather than that the
     *   data is broken. HEIF depends on the hardware decoder, AVIF on the platform version.
     */
    data class Platform(val averagingLimit: Int, val regions: Boolean, val needsDeviceDecoder: Boolean = false) : Codec

    /** Nothing decodes it here. [feature] is what the error card names. */
    data class Missing(val feature: String) : Codec
}

/** The decoder for [type] on a device at API level [sdk]. */
internal fun codecFor(type: ImageType, sdk: Int = Build.VERSION.SDK_INT): Codec = when (type) {
    ImageType.Jpeg -> Codec.Platform(averagingLimit = 8, regions = true)
    ImageType.Png -> Codec.Platform(averagingLimit = 1, regions = true)
    ImageType.WebP -> Codec.Platform(averagingLimit = Int.MAX_VALUE, regions = true)
    ImageType.Gif -> Codec.Platform(averagingLimit = 1, regions = false)
    ImageType.Bmp -> Codec.Platform(averagingLimit = 1, regions = false)
    ImageType.Heif -> Codec.Platform(averagingLimit = 1, regions = true, needsDeviceDecoder = true)
    ImageType.Avif -> if (sdk >= AVIF_PLATFORM_SDK) {
        Codec.Platform(averagingLimit = 1, regions = false, needsDeviceDecoder = true)
    } else {
        Codec.Missing(type.displayName)
    }
    // libjxl arrives as its own unit (docs/spec.md Q7).
    ImageType.JpegXl -> Codec.Missing(type.displayName)
    ImageType.PdfPage -> Codec.Missing(type.displayName)
}

/** `ImageDecoder` reads AVIF from Android 12. Below it the app bundles no AVIF decoder (docs/spec.md Q6). */
internal const val AVIF_PLATFORM_SDK = 31

/** What the error card names for a whole image type. */
internal val ImageType.displayName: String
    get() = when (this) {
        ImageType.Jpeg -> "JPEG"
        ImageType.Png -> "PNG"
        ImageType.WebP -> "WebP"
        ImageType.Gif -> "GIF"
        ImageType.Bmp -> "BMP"
        ImageType.Heif -> "HEIF"
        ImageType.Avif -> "AVIF"
        ImageType.JpegXl -> "JPEG XL"
        ImageType.PdfPage -> "PDF"
    }

/**
 * The JPEG coding process that 8-bit baseline and progressive decoders lack, from the frame header of [bytes]:
 * a sample precision other than 8 bits, or lossless coding. Null when the header shows neither or is not found.
 */
internal fun jpegFeature(bytes: ByteArray): String? {
    var at = 2
    while (at + 4 <= bytes.size) {
        if (bytes.u8(at) != 0xff) return null
        val marker = bytes.u8(at + 1)
        when {
            marker == 0xff -> at += 1
            marker == 0x01 || marker in 0xd0..0xd8 -> at += 2
            marker in FRAME_MARKERS -> {
                if (at + 4 >= bytes.size) return null
                val precision = bytes.u8(at + 4)
                return when {
                    marker in LOSSLESS_FRAME_MARKERS -> "lossless JPEG"
                    precision != 8 -> "$precision-bit JPEG"
                    else -> null
                }
            }
            marker == 0xda -> return null
            else -> at += 2 + ((bytes.u8(at + 2) shl 8) or bytes.u8(at + 3))
        }
    }
    return null
}

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xff

/** SOF0 to SOF15, without DHT (C4), JPG (C8) and DAC (CC), which share the range. */
private val FRAME_MARKERS = (0xc0..0xcf).toSet() - setOf(0xc4, 0xc8, 0xcc)
private val LOSSLESS_FRAME_MARKERS = setOf(0xc3, 0xc7, 0xcb, 0xcf)
