package dev.cuervolu.kignore

import dev.cuervolu.kignore.internal.PatternParser
import dev.cuervolu.kignore.internal.StepCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Inputs designed to make a backtracking matcher blow up.
 *
 * The cost is asserted through the work counter of the matcher, never through time, so the tests are deterministic.
 * The bound is the one the algorithm promises: the pattern size times the path size, plus one attempt per pair of
 * pattern segment and path segment.
 */
class AdversarialInputTest {
    private fun assertBounded(
        line: String,
        path: String,
        expected: Boolean,
    ) {
        val pattern = pattern(line)
        val counter = StepCounter()
        assertEquals(expected, pattern.glob.matches(path, counter), "'${line.take(40)}' on '${path.take(40)}'")
        val m = line.encodeToByteArray().size.toLong()
        val n = path.encodeToByteArray().size.toLong()
        val patternSegments = line.count { it == '/' } + 2L
        val pathSegments = path.count { it == '/' } + 1L
        val bound = 8 * (m * n + patternSegments * pathSegments + m + n)
        assertTrue(
            counter.steps <= bound,
            "${counter.steps} steps exceeds $bound for '${line.take(40)}' on '${path.take(40)}'",
        )
    }

    @Test
    fun manyStarsAgainstARunOfTheSameCharacter() {
        val line = "*a".repeat(30) + "b"
        assertBounded(line, "a".repeat(5000), false)
        assertBounded(line, "a".repeat(5000) + "b", true)
        assertBounded("a*a*a*a*a*a*a*a*b", "a".repeat(10000), false)
    }

    @Test
    fun manyBracketExpressionsWithStars() {
        assertBounded("[ab]*[ab]*[ab]*[ab]*c", "ab".repeat(5000), false)
        assertBounded("[ab]*[ab]*[ab]*[ab]*c", "ab".repeat(5000) + "c", true)
        assertBounded("*[!a]*[!a]*[!a]*", "a".repeat(8000), false)
    }

    @Test
    fun manyQuestionMarksAndStars() {
        assertBounded("*?*?*?*?*?*?*?*?*?*?z", "a".repeat(4000), false)
        assertBounded("?".repeat(200) + "*b", "a".repeat(4000), false)
    }

    @Test
    fun manyDoubleStarsAgainstDeepPaths() {
        val deepA = "a/".repeat(2000) + "a"
        assertBounded("**/a/**/a/**/a/**/b", deepA, false)
        assertBounded("**/a/**/a/**/a/**/b", deepA + "/b", true)
        assertBounded("a/**/a/**/a/**/a/**/b/**/c", deepA, false)
    }

    @Test
    fun doubleStarsMixedWithStarsInLongSegments() {
        val segment = "a".repeat(300)
        val path = List(60) { segment }.joinToString("/")
        assertBounded("**/*a*a*a*b/**/c", path, false)
        assertBounded("**/*a*a*a*/**/*a*a*b", path, false)
        assertBounded("**/a*a*a*a*b/**", path + "/b", false)
    }

    @Test
    fun hugePatternsAndPathsAreHandledWithoutRecursion() {
        val longName = "a".repeat(200_000)
        assertBounded("*a*", longName, true)
        assertBounded("a".repeat(100_000) + "*", longName, true)
        assertBounded("**/" + "x/".repeat(1_500) + "y", "x/".repeat(3_000) + "y", true)
        assertBounded("a/".repeat(20_000) + "**", "a/".repeat(20_000) + "b", true)
        assertTrue(pattern("*".repeat(100_000)).matches("anything"))
    }

    @Test
    fun separatorsOnlyAndRepeatedPathsAreRejectedInLinearTime() {
        val counter = StepCounter()
        assertFalse(pattern("**").glob.matches("/".repeat(1_000_000), counter))
        assertEquals(0L, counter.steps)
        assertFalse(pattern("a*").matches("a/".repeat(100_000) + "/"))
    }

    @Test
    fun longRunsOfBackslashesAndBrackets() {
        assertTrue(pattern("\\\\".repeat(10_000)).matches("\\".repeat(10_000)))
        invalid("[".repeat(1000))
        invalid("[a".repeat(5000))
        assertTrue(pattern("[" + "a".repeat(10_000) + "]").matches("a"))
    }

    @Test
    fun matchingTheSamePatternRepeatedlyDoesNotGrow() {
        val pattern = pattern("**/*a*a*b")
        val counter = StepCounter()
        pattern.glob.matches("a/a/a/aaaa", counter)
        val first = counter.steps
        counter.steps = 0
        repeat(50) { pattern.glob.matches("a/a/a/aaaa", counter) }
        assertEquals(first * 50, counter.steps)
    }

    private fun parseSteps(line: String): Long {
        val counter = StepCounter()
        PatternParser.parse(line, counter = counter)
        return counter.steps
    }

    @Test
    fun runsOfPosixClassOpeningsAreScannedOnce() {
        for (line in listOf(
            "[" + "[:".repeat(20_000),
            "[" + "[:a".repeat(20_000) + "]",
            "[[:alpha:" + "[:".repeat(20_000),
        )) {
            // Reading the line once costs about its length, so a quadratic scan would exceed this by orders of magnitude.
            assertTrue(parseSteps(line) <= 4L * line.length, "${parseSteps(line)} steps for ${line.length} characters")
        }
    }
}
