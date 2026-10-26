package dev.cuervolu.kignore

/**
 * A line of an ignore file that could not be compiled.
 *
 * The line is left out of the [IgnoreRuleSet], like Git does with a warning, and the rest of the file is loaded
 * normally.
 */
public class IgnoreDiagnostic internal constructor(
    /** Name given to [IgnoreRuleSet.parse], usually the path of the ignore file. Empty when none was given. */
    public val origin: String,
    /** Line number in the file, starting at 1. */
    public val line: Int,
    /** The line as it was read, without its line terminator and without a byte order mark. */
    public val text: String,
    /** What is wrong with [text]. [PatternProblem.offset] counts from the start of [text]. */
    public val problem: PatternProblem,
) {
    /** For example `src/.gitignore:3: bracket expression is not closed at index 4`. */
    public val message: String
        get() = (if (origin.isEmpty()) "line $line" else "$origin:$line") + ": ${problem.message}"

    override fun equals(other: Any?): Boolean =
        other is IgnoreDiagnostic &&
            origin == other.origin &&
            line == other.line &&
            text == other.text &&
            problem == other.problem

    override fun hashCode(): Int = 31 * (31 * (31 * origin.hashCode() + line) + text.hashCode()) + problem.hashCode()

    override fun toString(): String = "IgnoreDiagnostic($message)"
}
