package io.github.yuubiz.hirari.tools.corpus

import java.io.ByteArrayOutputStream

/** A byte buffer with the little-endian integers the archive formats use. */
internal class LittleEndianBytes : ByteArrayOutputStream() {
    fun u8(value: Int) = write(value)

    fun u16(value: Int) {
        write(value)
        write(value ushr 8)
    }

    fun u32(value: Int) {
        u16(value and 0xffff)
        u16(value ushr 16)
    }

    fun u64(value: Long) {
        u32(value.toInt())
        u32((value ushr 32).toInt())
    }

    /** RAR 5's variable-length integer: seven bits per byte, low bits first, the top bit set on all but the last. */
    fun vint(value: Long) {
        var rest = value
        while (rest >= 0x80) {
            write(((rest and 0x7f) or 0x80).toInt())
            rest = rest ushr 7
        }
        write(rest.toInt())
    }
}

internal fun bytes(block: LittleEndianBytes.() -> Unit): ByteArray = LittleEndianBytes().apply(block).toByteArray()
