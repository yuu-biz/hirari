package io.github.yuubiz.hirari.core.image.fuzz

import java.util.SplittableRandom

/**
 * Byte-level mutations for the device fuzzer (docs/spec.md section 6.2). It has no coverage feedback; it relies on
 * the corpus seeds being valid enough that small edits to a length, an offset or a magic number reach the code
 * behind the first check. A [SplittableRandom] seeded with [mix] of the run seed and the iteration makes every
 * input a function of those two numbers.
 */
internal class Mutator(private val random: SplittableRandom) {
    /** [seed] with one to four stacked mutations. [donor] is another seed to splice from, or null. */
    fun mutate(seed: ByteArray, donor: ByteArray?, maxSize: Int): ByteArray {
        var bytes = seed
        repeat(1 + random.nextInt(4)) { bytes = once(bytes, donor) }
        return if (bytes.size > maxSize) bytes.copyOf(maxSize) else bytes
    }

    private fun once(input: ByteArray, donor: ByteArray?): ByteArray {
        if (input.isEmpty()) return ByteArray(1 + random.nextInt(16)).also { random.nextBytes(it) }
        return when (random.nextInt(14)) {
            0 -> input.copyOf().also { it[position(it.size)] = (it[position(it.size)].toInt() xor (1 shl random.nextInt(8))).toByte() }
            1 -> input.copyOf().also { it[position(it.size)] = random.nextInt(256).toByte() }
            2 -> input.copyOf().also { it[position(it.size)] = INTERESTING_BYTES[random.nextInt(INTERESTING_BYTES.size)] }
            3 -> input.copyOf().also { putInteger(it, 2, INTERESTING[random.nextInt(INTERESTING.size)]) }
            4 -> input.copyOf().also { putInteger(it, 4, INTERESTING[random.nextInt(INTERESTING.size)]) }
            5 -> input.copyOf().also { putInteger(it, 8, INTERESTING[random.nextInt(INTERESTING.size)]) }
            6 -> input.copyOf().also { addToInteger(it, intArrayOf(1, 2, 4, 8)[random.nextInt(4)], random.nextInt(-35, 36)) }
            7 -> delete(input)
            8 -> insert(input, ByteArray(1 + random.nextInt(32)).also { random.nextBytes(it) })
            9 -> duplicate(input)
            10 -> fill(input)
            11 -> if (donor != null && donor.isNotEmpty()) splice(input, donor) else swap(input)
            12 -> input.copyOf(random.nextInt(input.size))
            else -> input + ByteArray(1 + random.nextInt(64))
        }
    }

    /** Half of the positions are in the first 64 or the last 128 bytes, where headers and directories are. */
    private fun position(size: Int): Int = when (random.nextInt(4)) {
        0 -> random.nextInt(minOf(size, 64))
        1 -> size - 1 - random.nextInt(minOf(size, 128))
        else -> random.nextInt(size)
    }

    private fun putInteger(bytes: ByteArray, width: Int, value: Long) {
        if (bytes.size < width) return
        val at = minOf(position(bytes.size), bytes.size - width)
        val big = random.nextBoolean()
        for (i in 0 until width) {
            val shift = 8 * (if (big) width - 1 - i else i)
            bytes[at + i] = (value ushr shift).toByte()
        }
    }

    private fun addToInteger(bytes: ByteArray, width: Int, delta: Int) {
        if (bytes.size < width) return
        val at = minOf(position(bytes.size), bytes.size - width)
        val big = random.nextBoolean()
        var value = 0L
        for (i in 0 until width) {
            val shift = 8 * (if (big) width - 1 - i else i)
            value = value or ((bytes[at + i].toLong() and 0xff) shl shift)
        }
        value += delta
        for (i in 0 until width) {
            val shift = 8 * (if (big) width - 1 - i else i)
            bytes[at + i] = (value ushr shift).toByte()
        }
    }

    private fun chunk(size: Int): IntRange {
        val length = 1 + random.nextInt(minOf(size, if (random.nextInt(4) == 0) 4096 else 32))
        val start = minOf(position(size), size - length)
        return start until start + length
    }

    private fun delete(input: ByteArray): ByteArray {
        val range = chunk(input.size)
        return input.copyOfRange(0, range.first) + input.copyOfRange(range.last + 1, input.size)
    }

    private fun insert(input: ByteArray, bytes: ByteArray): ByteArray {
        val at = random.nextInt(input.size + 1)
        return input.copyOfRange(0, at) + bytes + input.copyOfRange(at, input.size)
    }

    private fun duplicate(input: ByteArray): ByteArray {
        val range = chunk(input.size)
        return insert(input, input.copyOfRange(range.first, range.last + 1))
    }

    private fun fill(input: ByteArray): ByteArray {
        val range = chunk(input.size)
        val value = if (random.nextBoolean()) 0 else 0xff
        return input.copyOf().also { for (i in range) it[i] = value.toByte() }
    }

    private fun swap(input: ByteArray): ByteArray {
        val a = chunk(input.size)
        val b = chunk(input.size)
        val result = input.copyOf()
        val n = minOf(a.last - a.first, b.last - b.first) + 1
        for (i in 0 until n) {
            result[a.first + i] = input[b.first + i]
            result[b.first + i] = input[a.first + i]
        }
        return result
    }

    private fun splice(input: ByteArray, donor: ByteArray): ByteArray {
        val range = chunk(donor.size)
        val piece = donor.copyOfRange(range.first, range.last + 1)
        if (random.nextBoolean()) return insert(input, piece)
        val at = random.nextInt(input.size)
        val result = input.copyOf()
        for (i in piece.indices) if (at + i < result.size) result[at + i] = piece[i]
        return result
    }

    companion object {
        /** The stream of a run is a pure function of [seed] and [iteration]. */
        fun mix(seed: Long, iteration: Long): Long {
            var z = seed * -0x61c8864680b583ebL + iteration * 0x2545F4914F6CDD1DL + 0x1234567L
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        private val INTERESTING_BYTES = byteArrayOf(0, 1, 2, 0x7f, -0x80, -2, -1, 0x20, 0x5c, 0x2f)
        private val INTERESTING = longArrayOf(
            0, 1, 2, 3, 0xff, 0x100, 0x7fff, 0x8000, 0xffff, 0x10000, 0xffffff, 0x1000000,
            0x7fffffff, 0x80000000, 0xfffffffeL, 0xffffffffL, 0x100000000L, 0x7fffffffffffffffL, -1L, 4096, 65535, 65536,
        )
    }
}
