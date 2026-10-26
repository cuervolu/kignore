package dev.cuervolu.kignore

import dev.cuervolu.kignore.internal.PatternParser
import dev.cuervolu.kignore.internal.ReadOnlyList
import dev.cuervolu.kignore.internal.StepCounter

/**
 * The compiled rules of one ignore file, in file order.
 *
 * Parse a file once and reuse the result for as many matchers and scopes as needed. Instances are immutable and safe
 * to share between threads. A rule set does not know where its file lives: its patterns are relative to the
 * directory that [IgnoreMatcher] or [IgnoreScope] associates it with.
 */
public class IgnoreRuleSet private constructor(
    patterns: List<IgnorePattern>,
    diagnostics: List<IgnoreDiagnostic>,
) {
    /**
     * The valid patterns of the file, in file order. Comments, blank lines and invalid lines are not here.
     * The list cannot be modified, not even through a cast.
     */
    public val patterns: List<IgnorePattern> = ReadOnlyList.copyOf(patterns)

    /** One entry for each line that was left out because it is malformed, in file order. */
    public val diagnostics: List<IgnoreDiagnostic> = ReadOnlyList.copyOf(diagnostics)

    private val ordered: Array<IgnorePattern> = patterns.toTypedArray()

    /** `true` when the file holds no valid pattern. */
    public val isEmpty: Boolean get() = ordered.isEmpty()

    /**
     * Decision of this file alone for `path[from, to)`, with the part before [from] being the directory of the file.
     * The last pattern that matches wins, as in Git.
     */
    internal fun decide(
        path: ByteArray,
        from: Int,
        to: Int,
        isDirectory: Boolean,
        counter: StepCounter?,
    ): IgnoreDecision {
        var i = ordered.size - 1
        while (i >= 0) {
            val pattern = ordered[i]
            if (pattern.matchesEncoded(path, from, to, isDirectory, counter)) {
                return if (pattern.isNegated) IgnoreDecision.INCLUDED else IgnoreDecision.IGNORED
            }
            i--
        }
        return IgnoreDecision.NONE
    }

    override fun toString(): String = "IgnoreRuleSet(${patterns.size} patterns, ${diagnostics.size} diagnostics)"

    public companion object {
        /** A file without rules. */
        public val EMPTY: IgnoreRuleSet = IgnoreRuleSet(emptyList(), emptyList())

        private const val BYTE_ORDER_MARK = 0xFEFF

        /**
         * Compiles the text of an ignore file. This function never throws.
         *
         * Lines end at `\n`, and one `\r` before it is removed, so files with CRLF line endings work. A `\r` anywhere
         * else is part of the line, like in Git. A byte order mark at the very start of the text is dropped. Lines
         * that are not valid patterns are reported in [IgnoreRuleSet.diagnostics] and do not stop the rest of the
         * file from loading.
         *
         * [origin] only labels the diagnostics, for example with the path of the file. When [ignoreCase] is `true`
         * the patterns ignore the case of ASCII letters, like Git with `core.ignorecase`. Other characters, including
         * accented letters, are still compared exactly. The setting belongs to the rule set because it changes how
         * the patterns are compiled.
         */
        public fun parse(
            text: String,
            origin: String = "",
            ignoreCase: Boolean = false,
        ): IgnoreRuleSet {
            val patterns = ArrayList<IgnorePattern>()
            val diagnostics = ArrayList<IgnoreDiagnostic>()
            var start = if (text.isNotEmpty() && text[0].code == BYTE_ORDER_MARK) 1 else 0
            var lineNumber = 0
            while (start < text.length) {
                lineNumber++
                val newline = text.indexOf('\n', start)
                val next = if (newline < 0) text.length else newline
                val end = if (next > start && text[next - 1] == '\r') next - 1 else next
                val line = text.substring(start, end)
                when (val result = PatternParser.parse(line, ignoreCase)) {
                    is PatternParseResult.Parsed -> {
                        patterns.add(result.pattern)
                    }

                    is PatternParseResult.Invalid -> {
                        diagnostics.add(
                            IgnoreDiagnostic(origin, lineNumber, line, result.problem),
                        )
                    }

                    PatternParseResult.Skipped -> {}
                }
                start = next + 1
            }
            if (patterns.isEmpty() && diagnostics.isEmpty()) return EMPTY
            return IgnoreRuleSet(patterns, diagnostics)
        }
    }
}
