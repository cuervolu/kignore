package dev.cuervolu.kignore

import kotlin.test.assertEquals
import kotlin.test.assertIs

internal fun pattern(line: String): IgnorePattern {
    val result = IgnorePattern.parse(line)
    assertIs<PatternParseResult.Parsed>(result, "expected '$line' to parse, got $result")
    return result.pattern
}

/** Asserts the verdict of [line] for each path. A trailing slash on a path marks a directory. */
internal fun assertMatches(
    line: String,
    expected: Boolean,
    vararg paths: String,
) {
    val pattern = pattern(line)
    for (path in paths) {
        val isDirectory = path.endsWith("/")
        val name = path.removeSuffix("/")
        assertEquals(expected, pattern.matches(name, isDirectory), "'$line' on '$path'")
    }
}

internal fun invalid(line: String): PatternProblem {
    val result = IgnorePattern.parse(line)
    assertIs<PatternParseResult.Invalid>(result, "expected '$line' to be invalid, got $result")
    return result.problem
}
