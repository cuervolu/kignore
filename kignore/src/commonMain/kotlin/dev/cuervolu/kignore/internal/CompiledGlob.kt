package dev.cuervolu.kignore.internal

private const val SLASH = '/'.code.toByte()
private const val DOT = '.'.code.toByte()

/**
 * A pattern reduced to path segments. Immutable and free of any dependency on the platform.
 *
 * Relative patterns carry an implicit leading `**` segment, so they are matched like any anchored pattern.
 * A `**` segment consumes zero or more path segments, except as the last segment of the pattern, where it needs at
 * least one, so a pattern made of `abc`, a slash and `**` matches what is inside `abc` and not `abc` itself.
 */
internal class CompiledGlob(
    private val segments: Array<GlobSegment>,
) {
    fun matches(
        path: String,
        counter: StepCounter? = null,
    ): Boolean {
        val bytes = Utf8.encode(path)
        counter?.let { it.encodings++ }
        if (!PathBytes.isNormalized(bytes, 0, bytes.size)) return false
        return matchSegments(bytes, 0, bytes.size, counter)
    }

    /**
     * Matches the bytes `path[from, to)` of a path that is already encoded and known to be normalized.
     *
     * [from] must be the start of a segment, so a rule set based in a subdirectory can look at the part of the path
     * below its base without copying it. [to] may end a prefix of the path, which is how ancestors are checked
     * without encoding them again.
     */
    fun matches(
        path: ByteArray,
        from: Int,
        to: Int,
        counter: StepCounter? = null,
    ): Boolean = matchSegments(path, from, to, counter)

    /**
     * Same scheme as [GlobSegment.matches], one level up: each pattern segment consumes exactly one path segment
     * and `**` is the only wildcard, so remembering the last `**` is enough to retry. Each retry gives that `**` one
     * more path segment, hence at most `pattern segments * path segments` attempts.
     */
    private fun matchSegments(
        path: ByteArray,
        from: Int,
        to: Int,
        counter: StepCounter?,
    ): Boolean {
        val n = to
        val count = segments.size
        var pi = 0
        var pos = from
        var starPi = -1
        var starPos = 0
        while (true) {
            var advanced = false
            if (pi < count) {
                val segment = segments[pi]
                if (segment.isDoubleStar) {
                    if (pi == count - 1) {
                        if (pos < n) return true
                    } else {
                        starPi = pi + 1
                        starPos = pos
                        pi++
                        advanced = true
                    }
                } else if (pos < n) {
                    val end = segmentEnd(path, pos, n, counter)
                    if (segment.matches(path, pos, end, counter)) {
                        pi++
                        pos = end + 1
                        advanced = true
                    }
                }
            } else if (pos > n) {
                return true
            }
            if (advanced) continue
            if (starPi < 0 || starPos >= n) return false
            starPos = segmentEnd(path, starPos, n, counter) + 1
            pos = starPos
            pi = starPi
        }
    }

    private fun segmentEnd(
        path: ByteArray,
        from: Int,
        limit: Int,
        counter: StepCounter?,
    ): Int {
        var end = from
        while (end < limit && path[end] != SLASH) end++
        if (counter != null) counter.steps += end - from + 1
        return end
    }
}

/** Checks on encoded paths, shared by everything that accepts a path from the caller. */
internal object PathBytes {
    /** `true` for a non empty, relative path without empty, `.` or `..` segments in `path[from, to)`. */
    fun isNormalized(
        path: ByteArray,
        from: Int,
        to: Int,
    ): Boolean {
        if (to <= from) return false
        var segmentStart = from
        for (i in from..to) {
            if (i < to && path[i] != SLASH) continue
            val length = i - segmentStart
            if (length == 0) return false
            if (length == 1 && path[segmentStart] == DOT) return false
            if (length == 2 && path[segmentStart] == DOT && path[segmentStart + 1] == DOT) return false
            segmentStart = i + 1
        }
        return true
    }
}
