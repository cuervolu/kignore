package dev.cuervolu.kignore

import dev.cuervolu.kignore.internal.CompiledGlob
import dev.cuervolu.kignore.internal.PatternParser
import dev.cuervolu.kignore.internal.StepCounter

/**
 * One compiled line of a gitignore file.
 *
 * A pattern is compiled once by [parse] and can then be used any number of times, from any thread. Instances are
 * immutable. A value of this type is always valid: lines that cannot be compiled are reported by
 * [PatternParseResult.Invalid] instead.
 *
 * Matching is local. [matches] says whether this pattern alone covers a path. It does not say whether the path ends
 * up ignored, because that also depends on the other rules, on their order and on the parent directories of the path.
 * That decision belongs to the rule set built on top of patterns.
 *
 * Patterns follow the byte semantics of Git. Both the pattern and the path are compared as UTF-8, so `?` and
 * bracket expressions consume a single byte: `a?b` does not match `aéb`, but `a??b` does.
 */
public class IgnorePattern internal constructor(
    /** The line this pattern was compiled from. */
    public val source: String,
    /** `true` when the line started with `!`. */
    public val isNegated: Boolean,
    /** `true` when the line ended with `/`, so only directories can match. */
    public val isDirectoryOnly: Boolean,
    internal val glob: CompiledGlob,
) {
    /**
     * Tells whether this pattern covers [path].
     *
     * [path] is relative to the directory of the gitignore file, uses `/` as the only separator and must already be
     * normalized. kignore does not normalize paths and never touches the file system. Whether the path is a
     * directory is given by [isDirectory] and never by a trailing slash.
     *
     * A path that is not normalized matches no pattern and never causes an exception. That covers the empty string,
     * absolute paths (`/src`), `.` and `..` segments (`./src`, `a/../b`), repeated separators (`a//b`) and a trailing
     * separator (`src/`). A backslash is an ordinary character on every platform.
     *
     * Only [path] itself is considered, not its parent directories. With the pattern `build/`, `build` as a directory
     * matches and `build/out.o` does not.
     */
    public fun matches(
        path: String,
        isDirectory: Boolean = false,
    ): Boolean = (isDirectory || !isDirectoryOnly) && glob.matches(path)

    /**
     * Same as [matches] for a path that is already encoded and known to be normalized, looking only at
     * `path[from, to)`. Used by rule sets so a path is encoded once, however many patterns and ancestors it meets.
     */
    internal fun matchesEncoded(
        path: ByteArray,
        from: Int,
        to: Int,
        isDirectory: Boolean,
        counter: StepCounter? = null,
    ): Boolean = (isDirectory || !isDirectoryOnly) && glob.matches(path, from, to, counter)

    override fun toString(): String = "IgnorePattern($source)"

    public companion object {
        /**
         * Compiles one line of a gitignore file.
         *
         * The line must not contain a line terminator. Splitting a file into lines, including the handling of a
         * byte order mark and of carriage returns, is not done here. This function never throws.
         */
        public fun parse(line: String): PatternParseResult = PatternParser.parse(line)
    }
}
