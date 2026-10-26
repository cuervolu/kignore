package dev.cuervolu.kignore

/**
 * Outcome of [IgnorePattern.parse]. Handle it with an exhaustive `when`.
 *
 * The set of cases is closed. Under semantic versioning, adding or removing one is a breaking change.
 */
public sealed interface PatternParseResult {
    /** The line is a valid pattern. */
    public class Parsed internal constructor(
        public val pattern: IgnorePattern,
    ) : PatternParseResult {
        override fun equals(other: Any?): Boolean = other is Parsed && pattern == other.pattern

        override fun hashCode(): Int = pattern.hashCode()

        override fun toString(): String = "Parsed($pattern)"
    }

    /** The line is meant to be a pattern but is malformed, so it must not be used. */
    public class Invalid internal constructor(
        public val problem: PatternProblem,
    ) : PatternParseResult {
        override fun equals(other: Any?): Boolean = other is Invalid && problem == other.problem

        override fun hashCode(): Int = problem.hashCode()

        override fun toString(): String = "Invalid($problem)"
    }

    /** The line holds no pattern: it is blank, only spaces, a comment, or consists of nothing but `!` or `/`. */
    public data object Skipped : PatternParseResult

    /**
     * The pattern when the line parsed, or `null` for [Skipped] and [Invalid].
     *
     * Using it discards malformed lines silently. Prefer a `when` unless that is what you want.
     */
    public val patternOrNull: IgnorePattern?
        get() = (this as? Parsed)?.pattern
}
