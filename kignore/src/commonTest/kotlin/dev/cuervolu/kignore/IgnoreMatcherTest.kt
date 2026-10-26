package dev.cuervolu.kignore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnoreMatcherTest {
    private fun rules(
        text: String,
        origin: String = "",
        ignoreCase: Boolean = false,
    ) = IgnoreRuleSet.parse(text, origin, ignoreCase)

    private fun matcher(vararg files: Pair<String, String>) =
        IgnoreMatcher.of(files.associate { (directory, text) -> directory to rules(text) })

    @Test
    fun rootRulesDecideWithNegationAndOrder() {
        val m = IgnoreMatcher.of(rules("*.log\n!keep.log\n"))
        assertEquals(IgnoreDecision.IGNORED, m.decide("a.log"))
        assertEquals(IgnoreDecision.INCLUDED, m.decide("keep.log"))
        assertEquals(IgnoreDecision.NONE, m.decide("a.txt"))
        assertTrue(m.ignores("deep/dir/a.log"))
        assertFalse(m.ignores("keep.log"))
    }

    @Test
    fun directoryOnlyPatternsNeedADirectoryButIgnoreWhatIsInside() {
        val m = IgnoreMatcher.of(rules("build/\n"))
        assertEquals(IgnoreDecision.NONE, m.decide("build"))
        assertEquals(IgnoreDecision.IGNORED, m.decide("build", isDirectory = true))
        assertEquals(IgnoreDecision.IGNORED, m.decide("build/out.o"))
        assertEquals(IgnoreDecision.IGNORED, m.decide("build/a/b/out.o"))
    }

    @Test
    fun nestedRulesAreRelativeToTheirDirectory() {
        val m = matcher("" to "", "sub" to "/only\nlocal.log\ndoc/x\n")
        assertTrue(m.ignores("sub/only"))
        assertFalse(m.ignores("sub/deep/only"))
        assertFalse(m.ignores("only"))
        assertTrue(m.ignores("sub/a/local.log"))
        assertFalse(m.ignores("local.log"))
        assertTrue(m.ignores("sub/doc/x"))
        assertFalse(m.ignores("sub/a/doc/x"))
    }

    @Test
    fun deeperFilesTakePrecedenceOverShallowerOnes() {
        val m = matcher("" to "*.log\n!a.txt\n", "sub" to "!keep.log\na.txt\n")
        assertEquals(IgnoreDecision.INCLUDED, m.decide("sub/keep.log"))
        assertEquals(IgnoreDecision.IGNORED, m.decide("sub/other.log"))
        assertEquals(IgnoreDecision.IGNORED, m.decide("sub/a.txt"))
        assertEquals(IgnoreDecision.INCLUDED, m.decide("a.txt"))
        assertEquals(IgnoreDecision.INCLUDED, m.decide("sub/deeper/keep.log"))
    }

    @Test
    fun aDirectoryWithoutRulesInTheMapInheritsTheRulesAround() {
        val m = matcher("" to "*.log\n", "a/b" to "!x.log\n")
        assertTrue(m.ignores("a/y.log"))
        assertTrue(m.ignores("a/b/y.log"))
        assertEquals(IgnoreDecision.INCLUDED, m.decide("a/b/x.log"))
        assertEquals(IgnoreDecision.INCLUDED, m.decide("a/b/c/x.log"))
    }

    @Test
    fun matcherUsesOnlyTheRuleSetsItWasGiven() {
        // The ignore file of `sub` exists on disk in this story, but it was not given, so it is not applied.
        val m = matcher("" to "")
        assertFalse(m.ignores("sub/secret.txt"))
        assertTrue(matcher("" to "", "sub" to "secret.txt\n").ignores("sub/secret.txt"))
    }

    @Test
    fun cannotReIncludeBelowAnExcludedParent() {
        val m = matcher("" to "build/\n!build/keep.txt\n")
        assertEquals(IgnoreDecision.IGNORED, m.decide("build/keep.txt"))
        assertEquals(IgnoreDecision.IGNORED, m.decide("build/other.txt"))
    }

    @Test
    fun negationInADeeperFileDoesNotEscapeAnExcludedParent() {
        val m = matcher("" to "build/\n", "build" to "!keep.txt\n")
        assertEquals(IgnoreDecision.IGNORED, m.decide("build/keep.txt"))
        assertEquals(IgnoreDecision.IGNORED, m.decide("build/deeper/keep.txt"))
    }

    @Test
    fun includedIsReportedOnlyWhenNoParentIsExcluded() {
        val m = matcher("" to "*.tmp\n!/gen/\n/gen/*\n!/gen/keep.txt\n")
        assertEquals(IgnoreDecision.INCLUDED, m.decide("gen/keep.txt"))
        val blocked = matcher("" to "gen/\n!gen/keep.txt\n")
        assertEquals(IgnoreDecision.IGNORED, blocked.decide("gen/keep.txt"))
    }

    @Test
    fun aReIncludedParentDirectoryDoesNotBlockItsChildren() {
        val m = matcher("" to "/gen/*\n!/gen/keep/\n/gen/keep/*\n!/gen/keep/a.txt\n")
        assertEquals(IgnoreDecision.INCLUDED, m.decide("gen/keep", isDirectory = true))
        assertEquals(IgnoreDecision.INCLUDED, m.decide("gen/keep/a.txt"))
        assertEquals(IgnoreDecision.IGNORED, m.decide("gen/keep/b.txt"))
    }

    @Test
    fun theIgnoreFileIsAnOrdinaryPath() {
        val m = matcher("" to ".gitignore\n", "sub" to "*\n")
        assertTrue(m.ignores(".gitignore"))
        assertTrue(m.ignores("sub/.gitignore"))
    }

    @Test
    fun aRuleSetCanBeSharedBetweenMatchers() {
        val shared = rules("*.log\n")
        val first = IgnoreMatcher.of(shared)
        val second = IgnoreMatcher.of(mapOf("" to shared, "sub" to rules("!a.log\n")))
        assertTrue(first.ignores("sub/a.log"))
        assertFalse(second.ignores("sub/a.log"))
        assertTrue(first.ignores("sub/a.log"))
    }

    // Case

    @Test
    fun mapKeysAreComparedExactlyEvenWhenPatternsIgnoreCase() {
        val m = IgnoreMatcher.of(mapOf("sub" to rules("secret\n", ignoreCase = true)))
        assertTrue(m.ignores("sub/secret"))
        assertTrue(m.ignores("sub/SECRET"))
        assertFalse(m.ignores("SUB/secret"))
    }

    @Test
    fun foldedRuleSetsFoldEverySegmentBelowTheirBase() {
        val m = IgnoreMatcher.of(rules("doc/x\nDoc/*.tmp\n", ignoreCase = true))
        assertTrue(m.ignores("DOC/X"))
        assertTrue(m.ignores("doc/a.TMP"))
        assertFalse(m.ignores("other/doc/x"))
    }

    @Test
    fun ruleSetsWithDifferentSensitivityCanBeCombined() {
        val m = IgnoreMatcher.of(mapOf("" to rules("Readme\n"), "sub" to rules("Notes\n", ignoreCase = true)))
        assertFalse(m.ignores("readme"))
        assertTrue(m.ignores("sub/NOTES"))
    }

    // Invalid input

    @Test
    fun invalidPathsThrowInsteadOfAnsweringNotIgnored() {
        val m = IgnoreMatcher.of(rules("*\n"))
        for (path in listOf("", "/a", "a/", "a//b", "./a", "a/./b", "../a", "a/..", "a/../b", ".", "..")) {
            assertFailsWith<IllegalArgumentException>(path) { m.decide(path) }
            assertFailsWith<IllegalArgumentException>(path) { m.ignores(path, isDirectory = true) }
        }
    }

    /** The two layers differ on purpose: a single pattern answers `false` where the matcher refuses. */
    @Test
    fun patternMatchingKeepsItsLenientContract() {
        val pattern = pattern("*")
        for (path in listOf("", "/a", "a/", "a//b", "./a", "a/../b")) {
            assertFalse(pattern.matches(path), path)
            assertFailsWith<IllegalArgumentException>(path) { IgnoreMatcher.of(rules("*\n")).decide(path) }
        }
    }

    @Test
    fun aBackslashIsAnOrdinaryCharacterInPaths() {
        val m = IgnoreMatcher.of(rules("a\\\\b\n"))
        assertTrue(m.ignores("a\\b"))
    }

    @Test
    fun invalidDirectoryKeysThrow() {
        for (key in listOf("/", "/sub", "sub/", "a//b", "./sub", "sub/..")) {
            assertFailsWith<IllegalArgumentException>(key) { IgnoreMatcher.of(mapOf(key to IgnoreRuleSet.EMPTY)) }
        }
    }

    @Test
    fun nonAsciiPathsWork() {
        val m = matcher("" to "école/\n", "café" to "*.tmp\n")
        assertTrue(m.ignores("école/x"))
        assertTrue(m.ignores("café/a.tmp"))
        assertFalse(m.ignores("cafe/a.tmp"))
    }
}
