package dev.cuervolu.kignore.internal

import dev.cuervolu.kignore.IgnorePattern
import dev.cuervolu.kignore.PatternParseResult
import dev.cuervolu.kignore.PatternProblem
import dev.cuervolu.kignore.PatternProblem.Reason

/**
 * Turns one gitignore line into an [IgnorePattern], written from gitignore(5) and the conformance fixtures.
 *
 * The line is read as UTF-16 so diagnostics can point into it, while the compiled tokens work on UTF-8 bytes.
 * Only ASCII characters are special, so the two views never disagree about where a special character is.
 */
internal object PatternParser {
    fun parse(
        line: String,
        ignoreCase: Boolean = false,
    ): PatternParseResult {
        if (line.startsWith('#')) return PatternParseResult.Skipped
        var start = 0
        var end = trimmedEnd(line)
        val negated = end > 0 && line[0] == '!'
        if (negated) start++
        val directoryOnly = end > start && line[end - 1] == '/'
        if (directoryOnly) end--
        if (start >= end) return PatternParseResult.Skipped

        // The separator test looks at the text left after the trailing slash is gone, so `foo/` is not anchored.
        val anchored = line.indexOf('/', start).let { it in start until end }
        if (line[start] == '/') start++
        if (start >= end) return PatternParseResult.Skipped

        val segments = ArrayList<GlobSegment>()
        if (!anchored) segments.add(GlobSegment.DOUBLE_STAR)
        val problem = Tokenizer(line, start, end, segments, ignoreCase).run()
        if (problem != null) return PatternParseResult.Invalid(problem)
        val pattern = IgnorePattern(line, negated, directoryOnly, CompiledGlob(segments.toTypedArray()))
        return PatternParseResult.Parsed(pattern)
    }

    /** End of the line without trailing spaces, keeping a space that is escaped by a backslash. */
    private fun trimmedEnd(line: String): Int {
        var lastSpace = -1
        var i = 0
        while (i < line.length) {
            when (line[i]) {
                ' ' -> {
                    if (lastSpace < 0) lastSpace = i
                }

                '\\' -> {
                    i++
                    if (i >= line.length) return line.length
                    lastSpace = -1
                }

                else -> {
                    lastSpace = -1
                }
            }
            i++
        }
        return if (lastSpace < 0) line.length else lastSpace
    }

    private class Tokenizer(
        private val line: String,
        private var index: Int,
        private val end: Int,
        private val segments: MutableList<GlobSegment>,
        private val ignoreCase: Boolean,
    ) {
        private val segment = GlobSegment.Builder(ignoreCase)

        /** Returns the first problem found or `null` when the whole range compiled. */
        fun run(): PatternProblem? {
            while (index < end) {
                when (line[index]) {
                    '/' -> {
                        endSegment()
                    }

                    '*' -> {
                        segment.star()
                    }

                    '?' -> {
                        segment.any()
                    }

                    '[' -> {
                        val problem = bracket()
                        if (problem != null) return problem
                        continue
                    }

                    '\\' -> {
                        if (index + 1 >= end) return PatternProblem(Reason.TRAILING_BACKSLASH, index)
                        if (line[index + 1] == '/') {
                            // An escaped slash still separates segments, as it does in Git.
                            endSegment()
                            index++
                        } else {
                            index++
                            literalAtIndex()
                            continue
                        }
                    }

                    else -> {
                        literalAtIndex()
                        continue
                    }
                }
                index++
            }
            endSegment()
            return null
        }

        private fun endSegment() {
            segments.add(segment.build())
        }

        /** Adds the character at [index], which may be a surrogate pair, as UTF-8 literal bytes and moves past it. */
        private fun literalAtIndex() {
            val codePoint = Utf8.codePointAt(line, index, end)
            index += if (codePoint > 0xFFFF) 2 else 1
            Utf8.encode(codePoint) { segment.literal(it) }
        }

        /** Parses the bracket expression starting at [index]. Leaves [index] after the closing bracket. */
        private fun bracket(): PatternProblem? {
            val open = index
            var i = open + 1
            val bits = GlobSegment.newSet()
            val negate = i < end && (line[i] == '!' || line[i] == '^')
            if (negate) i++
            var first = true
            while (true) {
                if (i >= end) return PatternProblem(Reason.UNTERMINATED_BRACKET, open)
                val c = line[i]
                if (c == ']' && !first) {
                    i++
                    break
                }
                first = false
                if (c == '[' && i + 1 < end && line[i + 1] == ':') {
                    val close = line.indexOf(":]", i + 2)
                    if (close in 0 until end - 1) {
                        if (!addPosixClass(bits, line.substring(i + 2, close))) {
                            return PatternProblem(Reason.UNKNOWN_CHARACTER_CLASS, i)
                        }
                        i = close + 2
                        continue
                    }
                }
                if (c == '\\') {
                    if (i + 1 >= end) return PatternProblem(Reason.TRAILING_BACKSLASH, i)
                    i++
                }
                val codePoint = Utf8.codePointAt(line, i, end)
                i += if (codePoint > 0xFFFF) 2 else 1
                if (codePoint > 0x7F) {
                    Utf8.encode(codePoint) { GlobSegment.addToSet(bits, it) }
                    continue
                }
                // Git folds the text character to lower case and compares it with the members as written, so with
                // folding an upper case member never matches by itself. It only counts as the start of a range.
                if (!(ignoreCase && isUpper(codePoint))) GlobSegment.addToSet(bits, codePoint)
                // A dash between two single byte characters makes a range, unless it is the last member.
                if (i + 1 < end && line[i] == '-' && line[i + 1] != ']') {
                    var hi = i + 1
                    if (line[hi] == '\\') {
                        if (hi + 1 >= end) return PatternProblem(Reason.TRAILING_BACKSLASH, hi)
                        hi++
                    }
                    val high = Utf8.codePointAt(line, hi, end)
                    if (high <= 0x7F) {
                        // A reversed range keeps only its first character, like Git.
                        for (b in codePoint..high) GlobSegment.addToSet(bits, b)
                        i = hi + 1
                    }
                }
            }
            // Folding comes before negation, so `[^a]` rejects both cases of the letter. A set that holds a letter
            // through a range or a class, or a lower case member, accepts both cases of it.
            if (ignoreCase) GlobSegment.foldSet(bits)
            if (negate) {
                for (w in bits.indices) bits[w] = bits[w].inv()
            }
            // A bracket expression never matches a separator.
            bits[0] = bits[0] and (1L shl SLASH_BIT).inv()
            segment.set(bits)
            index = i
            return null
        }
    }

    private const val SLASH_BIT = 0x2F

    private fun addPosixClass(
        bits: LongArray,
        name: String,
    ): Boolean {
        val accepts: (Int) -> Boolean =
            when (name) {
                "alpha" -> { b -> isUpper(b) || isLower(b) }
                "digit" -> { b -> isDigit(b) }
                "alnum" -> { b -> isUpper(b) || isLower(b) || isDigit(b) }
                "upper" -> { b -> isUpper(b) }
                "lower" -> { b -> isLower(b) }
                "space" -> { b -> b == 0x20 || b in 0x09..0x0D }
                "blank" -> { b -> b == 0x20 || b == 0x09 }
                "punct" -> { b -> b in 0x21..0x7E && !(isUpper(b) || isLower(b) || isDigit(b)) }
                "print" -> { b -> b in 0x20..0x7E }
                "graph" -> { b -> b in 0x21..0x7E }
                "cntrl" -> { b -> b < 0x20 || b == 0x7F }
                "xdigit" -> { b -> isDigit(b) || b in 'a'.code..'f'.code || b in 'A'.code..'F'.code }
                else -> return false
            }
        for (b in 0..0x7F) if (accepts(b)) GlobSegment.addToSet(bits, b)
        return true
    }

    private fun isUpper(b: Int) = b in 'A'.code..'Z'.code

    private fun isLower(b: Int) = b in 'a'.code..'z'.code

    private fun isDigit(b: Int) = b in '0'.code..'9'.code
}
