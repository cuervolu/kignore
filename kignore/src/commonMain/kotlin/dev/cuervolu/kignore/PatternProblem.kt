package dev.cuervolu.kignore

/**
 * Why a gitignore line could not be turned into an [IgnorePattern].
 *
 * Git reports these lines with a warning and never lets them match, which is also what a rule set built on top of
 * [IgnorePattern.parse] is expected to do. Whether to skip, log or reject them is the caller's decision.
 */
public class PatternProblem internal constructor(
    /** What is wrong with the line. */
    public val reason: Reason,
    /** Index in the parsed line, counted in UTF-16 code units, where the problem starts. */
    public val offset: Int,
) {
    public enum class Reason(
        internal val description: String,
    ) {
        /** A `[` that is never closed by a `]`. */
        UNTERMINATED_BRACKET("bracket expression is not closed"),

        /** A `[:name:]` inside a bracket expression whose name is not a POSIX character class. */
        UNKNOWN_CHARACTER_CLASS("unknown POSIX character class"),

        /** A backslash with nothing left to escape. */
        TRAILING_BACKSLASH("pattern ends with an unescaped backslash"),
    }

    public val message: String get() = "${reason.description} at index $offset"

    override fun equals(other: Any?): Boolean =
        other is PatternProblem && reason == other.reason && offset == other.offset

    override fun hashCode(): Int = 31 * reason.hashCode() + offset

    override fun toString(): String = "PatternProblem($reason, offset=$offset)"
}
