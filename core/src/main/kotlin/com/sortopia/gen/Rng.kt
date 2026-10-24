package com.sortopia.gen

/**
 * Deterministic SplitMix64 RNG. Implemented here (not java.util.Random) so level generation
 * is identical on every device and JVM (CLAUDE.md, Architecture).
 */
@Suppress("MagicNumber") // SplitMix64 / FNV-1a reference shift and mask constants.
class Rng(
    seed: Long,
) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX1
        z = (z xor (z ushr 27)) * MIX2
        return z xor (z ushr 31)
    }

    /** Uniform int in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((nextLong() ushr 1) % bound).toInt()
    }

    /** Uniform int in [from, to] inclusive. */
    fun nextInt(
        from: Int,
        to: Int,
    ): Int = from + nextInt(to - from + 1)

    /** Uniform double in [0, 1). */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()

    fun <T> shuffle(list: MutableList<T>) {
        for (i in list.size - 1 downTo 1) {
            val j = nextInt(i + 1)
            val tmp = list[i]
            list[i] = list[j]
            list[j] = tmp
        }
    }

    companion object {
        private const val GOLDEN = -0x61c8864680b583ebL
        private const val MIX1 = -0x40a7b892e31b1a47L
        private const val MIX2 = -0x6b2fb644ecceee15L
        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L

        /** Stable 64-bit FNV-1a hash of a string, used to derive seeds from level ids. */
        fun seedOf(text: String): Long {
            var h = FNV_OFFSET
            for (b in text.toByteArray(Charsets.UTF_8)) {
                h = (h xor (b.toLong() and 0xff)) * FNV_PRIME
            }
            return h
        }
    }
}
