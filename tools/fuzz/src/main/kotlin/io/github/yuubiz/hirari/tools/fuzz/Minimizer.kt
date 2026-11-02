package io.github.yuubiz.hirari.tools.fuzz

/**
 * Delta debugging of a reproducer (Zeller's ddmin). Each round cuts [input] into `parts` pieces and asks [firstFailing]
 * about the inputs with one piece removed. [firstFailing] gets all of a round's candidates at once, so the device
 * can run them in one process start, and returns the index of the first that still fails, or null. A failure ends
 * the process, so a round costs one start however many candidates it has.
 */
class Minimizer(
    private val maxRounds: Int = 40,
    private val maxParts: Int = 128,
    private val firstFailing: (List<ByteArray>) -> Int?,
) {
    var rounds = 0
        private set

    fun minimize(input: ByteArray): ByteArray {
        var current = input
        var parts = 2
        while (current.size >= 2 && rounds < maxRounds) {
            parts = minOf(parts, current.size, maxParts)
            val candidates = withoutEachPart(current, parts)
            rounds++
            val index = firstFailing(candidates)
            if (index != null) {
                current = candidates[index]
                parts = maxOf(parts - 1, 2)
            } else if (parts >= minOf(current.size, maxParts)) {
                break
            } else {
                parts = minOf(parts * 2, current.size, maxParts)
            }
        }
        return current
    }

    private fun withoutEachPart(bytes: ByteArray, parts: Int): List<ByteArray> {
        val size = bytes.size
        return (0 until parts).map { i ->
            val from = (size.toLong() * i / parts).toInt()
            val to = (size.toLong() * (i + 1) / parts).toInt()
            bytes.copyOfRange(0, from) + bytes.copyOfRange(to, size)
        }
    }
}
