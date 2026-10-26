package dev.cuervolu.kignore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IgnoreRuleSetTest {
    private fun sources(rules: IgnoreRuleSet) = rules.patterns.map { it.source }

    private fun decide(
        rules: IgnoreRuleSet,
        path: String,
        isDirectory: Boolean = false,
    ) = IgnoreMatcher.of(rules).decide(path, isDirectory)

    @Test
    fun keepsPatternsInFileOrderAndSkipsCommentsAndBlankLines() {
        val rules = IgnoreRuleSet.parse("# comment\n\n*.log\n   \n!keep.log\nbuild/\n")
        assertEquals(listOf("*.log", "!keep.log", "build/"), sources(rules))
        assertTrue(rules.diagnostics.isEmpty())
    }

    @Test
    fun emptyTextGivesTheEmptyRuleSet() {
        assertSame(IgnoreRuleSet.EMPTY, IgnoreRuleSet.parse(""))
        assertTrue(IgnoreRuleSet.EMPTY.isEmpty)
    }

    @Test
    fun lastLineWithoutTerminatorIsRead() {
        assertEquals(listOf("a", "b"), sources(IgnoreRuleSet.parse("a\nb")))
    }

    @Test
    fun crlfLineEndingsDoNotBecomePartOfThePattern() {
        val rules = IgnoreRuleSet.parse("*.log\r\n!keep.log\r\nbuild/\r\n")
        assertEquals(listOf("*.log", "!keep.log", "build/"), sources(rules))
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "build", true))
        assertEquals(IgnoreDecision.INCLUDED, decide(rules, "keep.log"))
    }

    @Test
    fun aCarriageReturnInsideALineIsKept() {
        assertEquals(listOf("a\rb"), sources(IgnoreRuleSet.parse("a\rb\n")))
    }

    @Test
    fun byteOrderMarkIsDroppedOnlyAtTheStartOfTheText() {
        assertEquals(listOf("*.log", "\uFEFFx"), sources(IgnoreRuleSet.parse("\uFEFF*.log\n\uFEFFx\n")))
        assertTrue(IgnoreRuleSet.parse("\uFEFF# comment\n").isEmpty)
        assertEquals(listOf("!a"), sources(IgnoreRuleSet.parse("\uFEFF!a\n")))
    }

    @Test
    fun trailingSpacesAreRemovedUnlessEscaped() {
        val rules = IgnoreRuleSet.parse("a  \nb\\ \n")
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "a"))
        assertEquals(IgnoreDecision.NONE, decide(rules, "b"))
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "b "))
    }

    @Test
    fun invalidLinesAreReportedWithOriginLineAndReasonAndTheRestLoads() {
        val rules = IgnoreRuleSet.parse("good\r\n[abc\nalso-good\nbad\\\n", origin = "src/.gitignore")
        assertEquals(listOf("good", "also-good"), sources(rules))
        assertEquals(2, rules.diagnostics.size)
        val first = rules.diagnostics[0]
        assertEquals("src/.gitignore", first.origin)
        assertEquals(2, first.line)
        assertEquals("[abc", first.text)
        assertEquals(PatternProblem.Reason.UNTERMINATED_BRACKET, first.problem.reason)
        assertEquals("src/.gitignore:2: bracket expression is not closed at index 0", first.message)
        val second = rules.diagnostics[1]
        assertEquals(4, second.line)
        assertEquals(PatternProblem.Reason.TRAILING_BACKSLASH, second.problem.reason)
    }

    @Test
    fun diagnosticWithoutOriginNamesOnlyTheLine() {
        val rules = IgnoreRuleSet.parse("a\n[x\n")
        assertEquals("line 2: bracket expression is not closed at index 0", rules.diagnostics.single().message)
    }

    @Test
    fun invalidLineNeverMatches() {
        val rules = IgnoreRuleSet.parse("[abc\n")
        assertTrue(rules.isEmpty)
        assertEquals(IgnoreDecision.NONE, decide(rules, "[abc"))
    }

    @Test
    fun lastMatchingLineWins() {
        val rules = IgnoreRuleSet.parse("*.log\n!keep.log\nkeep.log\n")
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "keep.log"))
        val reincluded = IgnoreRuleSet.parse("*.log\nkeep.log\n!keep.log\n")
        assertEquals(IgnoreDecision.INCLUDED, decide(reincluded, "keep.log"))
    }

    // Case folding. Git folds the text character to lower case and compares it with the pattern as written.

    private fun folded(vararg lines: String) = IgnoreRuleSet.parse(lines.joinToString("\n"), ignoreCase = true)

    @Test
    fun caseIsSensitiveByDefault() {
        val rules = IgnoreRuleSet.parse("Readme.md\n[a-c]x\n[[:upper:]]y\n")
        assertEquals(IgnoreDecision.NONE, decide(rules, "readme.md"))
        assertEquals(IgnoreDecision.NONE, decide(rules, "Ax"))
        assertEquals(IgnoreDecision.NONE, decide(rules, "ay"))
    }

    @Test
    fun foldedLiteralsMatchEitherCase() {
        val rules = folded("Readme.md", "*.LOG")
        for (path in listOf("Readme.md", "readme.md", "README.MD", "a.log", "a.Log")) {
            assertEquals(IgnoreDecision.IGNORED, decide(rules, path), path)
        }
        assertEquals(IgnoreDecision.NONE, decide(rules, "readme.mdx"))
    }

    @Test
    fun foldedRangesMatchEitherCase() {
        val rules = folded("[a-c]x", "[X-Z]three")
        for (path in listOf("ax", "Ax", "Cx", "ythree", "Ythree")) {
            assertEquals(IgnoreDecision.IGNORED, decide(rules, path), path)
        }
        assertEquals(IgnoreDecision.NONE, decide(rules, "dx"))
    }

    @Test
    fun foldedPosixClassesMatchEitherCase() {
        val rules = folded("[[:upper:]]y", "[[:lower:]]z")
        for (path in listOf("ay", "Ay", "az", "Az")) {
            assertEquals(IgnoreDecision.IGNORED, decide(rules, path), path)
        }
        assertEquals(IgnoreDecision.NONE, decide(rules, "1y"))
    }

    /** Regression: found by the `ignorecase-bracket-members` fixture. Git never matches an upper case member. */
    @Test
    fun foldedUpperCaseSetMemberDoesNotMatchByItself() {
        val rules = folded("[A]one", "[a]two")
        assertEquals(IgnoreDecision.NONE, decide(rules, "aone"))
        assertEquals(IgnoreDecision.NONE, decide(rules, "Aone"))
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "atwo"))
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "Atwo"))
    }

    @Test
    fun foldedNegatedSetRejectsBothCases() {
        val rules = folded("[^b]two")
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "atwo"))
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "Atwo"))
        assertEquals(IgnoreDecision.NONE, decide(rules, "btwo"))
        assertEquals(IgnoreDecision.NONE, decide(rules, "Btwo"))
    }

    @Test
    fun foldingCoversAsciiLettersOnly() {
        val rules = folded("café", "École")
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "café"))
        assertEquals(IgnoreDecision.NONE, decide(rules, "cafÉ"))
        assertEquals(IgnoreDecision.NONE, decide(rules, "école"))
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "École"))
    }

    @Test
    fun foldingAppliesToEverySegmentBelowTheBase() {
        val rules = folded("doc/x")
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "DOC/X"))
        assertEquals(IgnoreDecision.IGNORED, decide(rules, "Doc/x"))
    }
}
