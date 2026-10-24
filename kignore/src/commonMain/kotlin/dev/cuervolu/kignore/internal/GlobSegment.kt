package dev.cuervolu.kignore.internal

/** Counts the work done by the matcher so tests can check the cost bounds without measuring time. */
internal class StepCounter {
    var steps: Long = 0
}

/**
 * One `/` separated component of a pattern, compiled to byte tokens.
 *
 * Every token other than a star consumes exactly one byte, which is what makes the single retry point in
 * [matches] sufficient. A segment made only of two or more stars is a [DOUBLE_STAR] and is handled by [CompiledGlob].
 */
internal class GlobSegment private constructor(
    val isDoubleStar: Boolean,
    private val kinds: ByteArray,
    private val operands: IntArray,
    private val sets: Array<LongArray>,
) {
    /**
     * Matches the bytes `path[from, to)`, which contain no `/`.
     *
     * Iterative with one retry point for the last star. A mismatch after a star moves that star one byte forward and
     * resumes right after it, so every byte of the input is offered to the last star at most once and the cost is
     * at most `tokens * bytes`.
     */
    fun matches(
        path: ByteArray,
        from: Int,
        to: Int,
        counter: StepCounter?,
    ): Boolean {
        val count = kinds.size
        var token = 0
        var index = from
        var starToken = -1
        var starIndex = 0
        while (true) {
            if (counter != null) counter.steps++
            if (index < to) {
                if (token < count) {
                    when (kinds[token]) {
                        STAR -> {
                            starToken = token
                            starIndex = index
                            token++
                            continue
                        }

                        LITERAL -> {
                            if (path[index].toInt() and 0xFF == operands[token]) {
                                token++
                                index++
                                continue
                            }
                        }

                        ANY -> {
                            token++
                            index++
                            continue
                        }

                        else -> {
                            if (setContains(sets[operands[token]], path[index].toInt() and 0xFF)) {
                                token++
                                index++
                                continue
                            }
                        }
                    }
                }
            } else {
                while (token < count && kinds[token] == STAR) token++
                if (token == count) return true
            }
            if (starToken < 0) return false
            starIndex++
            if (starIndex > to) return false
            index = starIndex
            token = starToken + 1
        }
    }

    class Builder {
        private var kinds = ByteArray(8)
        private var operands = IntArray(8)
        private var sets = ArrayList<LongArray>()
        private var size = 0

        /** Number of unescaped stars seen and whether anything else was added, to recognize `**`. */
        private var plainStars = 0
        private var hasOtherTokens = false

        fun star() {
            plainStars++
            // Adjacent stars are equivalent to one.
            if (size > 0 && kinds[size - 1] == STAR) return
            add(STAR, 0)
        }

        fun any() = other(ANY, 0)

        fun literal(byte: Int) = other(LITERAL, byte)

        fun set(bits: LongArray) {
            sets.add(bits)
            other(CLASS, sets.lastIndex)
        }

        private fun other(
            kind: Byte,
            operand: Int,
        ) {
            hasOtherTokens = true
            add(kind, operand)
        }

        private fun add(
            kind: Byte,
            operand: Int,
        ) {
            if (size == kinds.size) {
                kinds = kinds.copyOf(size * 2)
                operands = operands.copyOf(size * 2)
            }
            kinds[size] = kind
            operands[size] = operand
            size++
        }

        /** Finishes the segment and resets the builder for the next one. */
        fun build(): GlobSegment {
            val result =
                if (!hasOtherTokens && plainStars >= 2) {
                    DOUBLE_STAR
                } else {
                    GlobSegment(false, kinds.copyOf(size), operands.copyOf(size), sets.toTypedArray())
                }
            sets = ArrayList()
            size = 0
            plainStars = 0
            hasOtherTokens = false
            return result
        }
    }

    companion object {
        private const val LITERAL: Byte = 0
        private const val ANY: Byte = 1
        private const val STAR: Byte = 2
        private const val CLASS: Byte = 3

        val DOUBLE_STAR = GlobSegment(true, ByteArray(0), IntArray(0), emptyArray())

        fun newSet(): LongArray = LongArray(4)

        fun addToSet(
            set: LongArray,
            byte: Int,
        ) {
            set[byte ushr 6] = set[byte ushr 6] or (1L shl (byte and 63))
        }

        fun setContains(
            set: LongArray,
            byte: Int,
        ): Boolean = set[byte ushr 6] and (1L shl (byte and 63)) != 0L
    }
}
