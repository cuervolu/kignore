package dev.cuervolu.kignore.internal

/**
 * A list over a private copy of its elements.
 *
 * `toList()` and `Collections.unmodifiableList` are not used because what they return is not specified the same way
 * on every platform, and a cast to `MutableList` must never be able to change what a rule set reports.
 */
internal class ReadOnlyList<T> private constructor(
    private val items: Array<Any?>,
) : AbstractList<T>(),
    RandomAccess {
    override val size: Int get() = items.size

    @Suppress("UNCHECKED_CAST")
    override fun get(index: Int): T {
        if (index < 0 || index >= items.size) throw IndexOutOfBoundsException("index: $index, size: ${items.size}")
        return items[index] as T
    }

    companion object {
        private val EMPTY = ReadOnlyList<Nothing>(emptyArray())

        @Suppress("UNCHECKED_CAST")
        fun <T> copyOf(source: Collection<T>): List<T> =
            if (source.isEmpty()) EMPTY as List<T> else ReadOnlyList(source.toTypedArray<Any?>())
    }
}
