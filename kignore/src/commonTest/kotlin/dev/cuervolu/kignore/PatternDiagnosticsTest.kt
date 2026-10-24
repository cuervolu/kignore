package dev.cuervolu.kignore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternDiagnosticsTest {
    @Test
    fun unterminatedBracket() {
        val cases = listOf("a[bc" to 1, "[" to 0, "x[" to 1, "a[]b" to 1, "c[!]d" to 1, "e[^]f" to 1, "a[b-" to 1)
        cases.forEach { (line, offset) ->
            val problem = invalid(line)
            assertEquals(PatternProblem.Reason.UNTERMINATED_BRACKET, problem.reason, line)
            assertEquals(offset, problem.offset, line)
        }
    }

    @Test
    fun unknownCharacterClass() {
        val problem = invalid("a[[:nosuch:]]")
        assertEquals(PatternProblem.Reason.UNKNOWN_CHARACTER_CLASS, problem.reason)
        assertEquals(2, problem.offset)
    }

    @Test
    fun trailingBackslash() {
        val problem = invalid("abc\\")
        assertEquals(PatternProblem.Reason.TRAILING_BACKSLASH, problem.reason)
        assertEquals(3, problem.offset)
        assertEquals(PatternProblem.Reason.TRAILING_BACKSLASH, invalid("a[\\").reason)
    }

    @Test
    fun escapedBackslashAtTheEndIsValid() {
        assertIs<PatternParseResult.Parsed>(IgnorePattern.parse("def\\\\"))
    }

    @Test
    fun offsetsPointIntoTheOriginalLine() {
        // The negation prefix is part of the line, so offsets include it.
        assertEquals(2, invalid("!a[b").offset)
    }

    @Test
    fun messagesMentionTheOffset() {
        assertTrue(invalid("[").message.contains("index 0"))
    }

    @Test
    fun invalidLinesHaveNoPattern() {
        assertNull(IgnorePattern.parse("[").patternOrNull)
        assertNull(IgnorePattern.parse("a\\").patternOrNull)
    }

    @Test
    fun invalidLinesAreReportedEvenWhenNegatedOrDirectoryOnly() {
        assertEquals(PatternProblem.Reason.UNTERMINATED_BRACKET, invalid("![a").reason)
        assertEquals(PatternProblem.Reason.UNTERMINATED_BRACKET, invalid("[a/").reason)
    }

    @Test
    fun problemsAreComparableByValue() {
        assertEquals(invalid("[a"), invalid("[a"))
        assertEquals(invalid("[a").hashCode(), invalid("[a").hashCode())
    }
}
