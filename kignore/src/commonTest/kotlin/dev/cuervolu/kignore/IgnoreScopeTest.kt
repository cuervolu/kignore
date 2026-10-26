package dev.cuervolu.kignore

import dev.cuervolu.kignore.internal.StepCounter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnoreScopeTest {
    private fun rules(
        text: String,
        ignoreCase: Boolean = false,
    ) = IgnoreRuleSet.parse(text, ignoreCase = ignoreCase)

    @Test
    fun rootScopeDecidesEntries() {
        val root = IgnoreScope.root(rules("*.log\n!keep.log\nbuild/\n"))
        assertFalse(root.isIgnored)
        assertEquals(IgnoreDecision.IGNORED, root.decide("a.log"))
        assertEquals(IgnoreDecision.INCLUDED, root.decide("keep.log"))
        assertEquals(IgnoreDecision.NONE, root.decide("build"))
        assertTrue(root.ignores("build", isDirectory = true))
        assertEquals(IgnoreDecision.NONE, IgnoreScope.root().decide("anything"))
    }

    @Test
    fun enteringADirectoryAppliesRulesOfEveryLevelWithTheClosestFirst() {
        val root = IgnoreScope.root(rules("*.log\n!a.txt\n"))
        val sub = root.enter("sub", rules("!keep.log\na.txt\n"))
        assertEquals(IgnoreDecision.INCLUDED, sub.decide("keep.log"))
        assertEquals(IgnoreDecision.IGNORED, sub.decide("other.log"))
        assertEquals(IgnoreDecision.IGNORED, sub.decide("a.txt"))
        val deeper = sub.enter("deeper")
        assertEquals(IgnoreDecision.INCLUDED, deeper.decide("keep.log"))
        assertEquals(IgnoreDecision.IGNORED, deeper.decide("a.txt"))
    }

    @Test
    fun rulesOfADirectoryNeverApplyToTheDirectoryItself() {
        val root = IgnoreScope.root()
        val sub = root.enter("sub", rules("sub\n*\n"))
        assertFalse(sub.isIgnored)
        assertTrue(sub.ignores("sub"))
    }

    @Test
    fun relativePatternsUseTheBaseOfTheirFile() {
        val root = IgnoreScope.root()
        val sub = root.enter("sub", rules("/only\ndoc/x\n"))
        assertTrue(sub.ignores("only"))
        assertFalse(sub.enter("deep").ignores("only"))
        assertTrue(sub.enter("doc").ignores("x"))
        assertFalse(sub.enter("deep").enter("doc").ignores("x"))
    }

    @Test
    fun anIgnoredDirectoryIgnoresEverythingAndDropsTheRulesPassedToEnter() {
        val root = IgnoreScope.root(rules("build/\n"))
        val build = root.enter("build", rules("!keep.txt\n"))
        assertTrue(build.isIgnored)
        assertEquals(IgnoreDecision.IGNORED, build.decide("keep.txt"))
        val inner = build.enter("inner", rules("!x\n"))
        assertTrue(inner.isIgnored)
        assertEquals(IgnoreDecision.IGNORED, inner.decide("x"))
    }

    @Test
    fun aReIncludedDirectoryIsNotIgnored() {
        val root = IgnoreScope.root(rules("/gen/*\n!/gen/keep/\n/gen/keep/*\n!/gen/keep/a.txt\n"))
        val gen = root.enter("gen")
        val keep = gen.enter("keep")
        assertFalse(keep.isIgnored)
        assertEquals(IgnoreDecision.INCLUDED, keep.decide("a.txt"))
        assertEquals(IgnoreDecision.IGNORED, keep.decide("b.txt"))
        assertTrue(gen.enter("other").isIgnored)
    }

    @Test
    fun namesMustBeSingleComponents() {
        val scope = IgnoreScope.root(rules("*\n"))
        for (name in listOf("", ".", "..", "a/b", "/a", "a/")) {
            assertFailsWith<IllegalArgumentException>(name) { scope.decide(name) }
            assertFailsWith<IllegalArgumentException>(name) { scope.ignores(name) }
            assertFailsWith<IllegalArgumentException>(name) { scope.enter(name) }
        }
    }

    @Test
    fun namesAreRejectedEvenInAnIgnoredDirectory() {
        val ignored = IgnoreScope.root(rules("build/\n")).enter("build")
        assertFailsWith<IllegalArgumentException> { ignored.decide("a/b") }
        assertFailsWith<IllegalArgumentException> { ignored.enter("") }
    }

    @Test
    fun scopesAreImmutable() {
        val root = IgnoreScope.root(rules("*.log\n"))
        val before = root.decide("a.log")
        root.enter("sub", rules("!a.log\n"))
        assertEquals(before, root.decide("a.log"))
        val sub = root.enter("sub", rules("!a.log\n"))
        val again = root.enter("sub", rules("!a.log\n"))
        assertEquals(sub.decide("a.log"), again.decide("a.log"))
        assertEquals(IgnoreDecision.INCLUDED, sub.decide("a.log"))
    }

    @Test
    fun siblingsDoNotShareRules() {
        val root = IgnoreScope.root()
        val a = root.enter("a", rules("x\n"))
        val b = root.enter("b")
        assertTrue(a.ignores("x"))
        assertFalse(b.ignores("x"))
    }

    @Test
    fun foldedRuleSetsWorkInScopes() {
        val sub = IgnoreScope.root().enter("sub", rules("secret\n", ignoreCase = true))
        assertTrue(sub.ignores("SECRET"))
    }

    // Both entry points must give the same answer for every path of a tree.

    private val tree =
        mapOf(
            "" to "*.log\n!keep.log\nbuild/\n/gen/*\n!/gen/keep/\n",
            "src" to "tmp/\n!tmp/save.txt\n*.o\n",
            "src/gen" to "!a.o\nlocal\n",
            "gen/keep" to "*.x\n!ok.x\n",
            "docs" to "*\n!*.md\n",
        )

    private val paths =
        listOf(
            "a.log",
            "keep.log",
            "build",
            "build/a.log",
            "build/keep.log",
            "src/a.o",
            "src/tmp",
            "src/tmp/save.txt",
            "src/tmp/other",
            "src/gen/a.o",
            "src/gen/b.o",
            "src/gen/local",
            "src/gen/local/x",
            "gen/a",
            "gen/keep",
            "gen/keep/ok.x",
            "gen/keep/no.x",
            "gen/other/ok.x",
            "docs/a.txt",
            "docs/a.md",
            "docs/sub/a.md",
            "x/y/z.log",
            "src/x/y.o",
            "src/gen/deep/a.o",
        )

    private fun scopeDecision(
        sets: Map<String, IgnoreRuleSet>,
        path: String,
        isDirectory: Boolean,
    ): IgnoreDecision {
        val names = path.split('/')
        var scope = IgnoreScope.root(sets[""] ?: IgnoreRuleSet.EMPTY)
        var directory = ""
        for (name in names.dropLast(1)) {
            directory = if (directory.isEmpty()) name else "$directory/$name"
            scope = scope.enter(name, sets[directory] ?: IgnoreRuleSet.EMPTY)
        }
        return scope.decide(names.last(), isDirectory)
    }

    @Test
    fun matcherAndScopeAgreeOnEveryPathOfATree() {
        val sets = tree.mapValues { rules(it.value) }
        val matcher = IgnoreMatcher.of(sets)
        for (path in paths) {
            for (isDirectory in listOf(false, true)) {
                assertEquals(
                    matcher.decide(path, isDirectory),
                    scopeDecision(sets, path, isDirectory),
                    "$path isDirectory=$isDirectory",
                )
            }
        }
    }

    // Cost

    @Test
    fun eachPublicCallEncodesThePathOnceWhateverTheRulesAndAncestors() {
        val many = (1..50).joinToString("\n") { "pattern$it\n*.ext$it" }
        val sets = mapOf("" to rules(many), "a" to rules(many), "a/b" to rules(many), "a/b/c" to rules(many))
        val matcher = IgnoreMatcher.of(sets)
        val matcherCounter = StepCounter()
        matcher.decide("a/b/c/d/e/file.txt", false, matcherCounter)
        assertEquals(1, matcherCounter.encodings)

        val counter = StepCounter()
        var scope = IgnoreScope.root(sets[""]!!)
        for ((index, name) in listOf("a", "b", "c").withIndex()) {
            scope = scope.enter(name, sets[listOf("a", "a/b", "a/b/c")[index]]!!, counter)
        }
        assertEquals(3, counter.encodings)
        scope.decide("file.txt", false, counter)
        assertEquals(4, counter.encodings)
    }

    @Test
    fun scopeEvaluatesEachDirectoryOnceNotOncePerFile() {
        val sets = mapOf("" to rules((1..20).joinToString("\n") { "never$it" }))
        val files = (1..200).map { "dir/sub/deep/file$it.txt" }
        val matcher = IgnoreMatcher.of(sets)
        val viaMatcher = StepCounter()
        for (file in files) matcher.decide(file, false, viaMatcher)

        val viaScope = StepCounter()
        val deep =
            IgnoreScope
                .root(sets[""]!!)
                .enter("dir", IgnoreRuleSet.EMPTY, viaScope)
                .enter("sub", IgnoreRuleSet.EMPTY, viaScope)
                .enter("deep", IgnoreRuleSet.EMPTY, viaScope)
        for (index in 1..200) deep.decide("file$index.txt", false, viaScope)

        assertEquals(files.size, viaMatcher.encodings)
        assertEquals(3 + files.size, viaScope.encodings)
        // Three ancestors per file against twenty patterns, against twenty patterns per file after entering once.
        assertTrue(viaScope.steps * 3 < viaMatcher.steps * 2, "scope ${viaScope.steps} matcher ${viaMatcher.steps}")
    }

    /** Documents why `enter(name)` followed by `isIgnored` is preferred when there is no ignore file to read. */
    @Test
    fun checkingBeforeEnteringEvaluatesTheDirectoryTwice() {
        val root = IgnoreScope.root(rules((1..30).joinToString("\n") { "never$it/" }))
        val enterOnly = StepCounter()
        root.enter("dir", IgnoreRuleSet.EMPTY, enterOnly)
        val checkThenEnter = StepCounter()
        root.decide("dir", true, checkThenEnter)
        root.enter("dir", IgnoreRuleSet.EMPTY, checkThenEnter)
        assertEquals(2 * enterOnly.steps, checkThenEnter.steps)
        assertEquals(1, enterOnly.encodings)
        assertEquals(2, checkThenEnter.encodings)
    }

    @Test
    fun deepTreesStayCheap() {
        val sets = mapOf("" to rules("*.log\n"))
        var scope = IgnoreScope.root(sets[""]!!)
        repeat(2000) { scope = scope.enter("d$it") }
        assertTrue(scope.ignores("a.log"))
        assertFalse(scope.ignores("a.txt"))
    }
}
