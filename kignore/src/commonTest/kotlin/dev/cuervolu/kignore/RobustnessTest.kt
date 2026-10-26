package dev.cuervolu.kignore

import dev.cuervolu.kignore.internal.StepCounter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hostile and random input, checked against statements that do not depend on the engine: what must throw, what must
 * be counted, and how the three entry points relate to each other.
 */
class RobustnessTest {
    private val random = Random(20260601)

    private fun randomText(
        pieces: List<String>,
        maxLength: Int,
    ): String = buildString { repeat(random.nextInt(maxLength + 1)) { append(pieces[random.nextInt(pieces.size)]) } }

    private fun isNormalized(path: String) =
        path.isNotEmpty() && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    @Test
    fun matcherThrowsExactlyForPathsThatAreNotNormalized() {
        val patterns = listOf("*", "**", "a", "a/b", "/a", "**/b", "[ab]*", "?", "*/", "\\\\*").map { pattern(it) }
        val matcher = IgnoreMatcher.of(IgnoreRuleSet.parse("*.log\n!keep.log\nbuild/\n"))
        val pieces = listOf("a", "b", "/", ".", "..", "\\", " ", "\uD800", "\u0000", "é", "😀", "log")
        repeat(20_000) {
            val path = randomText(pieces, 8)
            for (pattern in patterns) {
                pattern.matches(path)
                pattern.matches(path, isDirectory = true)
            }
            val failure = runCatching { matcher.decide(path) }.exceptionOrNull()
            if (isNormalized(
                    path,
                )
            ) {
                assertNull(failure, "'$path'")
            } else {
                assertIs<IllegalArgumentException>(failure, "'$path'")
            }
        }
    }

    @Test
    fun scopeThrowsExactlyForNamesThatAreNotOneComponent() {
        val scope = IgnoreScope.root(IgnoreRuleSet.parse("*.log"))
        val pieces = listOf("a", ".", "..", "/", "\\", " ", "\uD800", "é")
        repeat(5_000) {
            val name = randomText(pieces, 4)
            val valid = name.isNotEmpty() && name != "." && name != ".." && '/' !in name
            val failure = runCatching { scope.decide(name) }.exceptionOrNull()
            val enterFailure = runCatching { scope.enter(name) }.exceptionOrNull()
            if (valid) {
                assertNull(failure, "'$name'")
                assertNull(enterFailure, "'$name'")
            } else {
                assertIs<IllegalArgumentException>(failure, "'$name'")
                assertIs<IllegalArgumentException>(enterFailure, "'$name'")
            }
        }
    }

    @Test
    fun parsingNeverThrowsAndEveryLineIsAccountedFor() {
        val pieces =
            listOf("a", "*", "[", "]", "\\", "!", "/", "#", " ", "\r", "\n", "", "[:alpha:]", "é", "\uD83D", "**")
        repeat(3_000) {
            val text = randomText(pieces, 60)
            val rules = IgnoreRuleSet.parse(text, "file")
            val body = text.removePrefix("﻿")
            val lines = body.split('\n').let { if (body.endsWith('\n') || body.isEmpty()) it.dropLast(1) else it }
            assertTrue(rules.patterns.size + rules.diagnostics.size <= lines.size, "'$text'")
            var previous = 0
            for (diagnostic in rules.diagnostics) {
                assertTrue(diagnostic.line > previous && diagnostic.line <= lines.size, "'$text'")
                previous = diagnostic.line
                assertEquals(lines[diagnostic.line - 1].removeSuffix("\r"), diagnostic.text, "'$text'")
            }
        }
    }

    @Test
    fun aLargeRuleFileIsCompiledCompletely() {
        val lines = 100_000
        val text =
            (0 until lines).joinToString("\n") {
                when (it % 4) {
                    0 -> "dir$it/"
                    1 -> "*.ext$it"
                    2 -> "[bad$it"
                    else -> "# comment $it"
                }
            }
        val rules = IgnoreRuleSet.parse(text, "large")
        assertEquals(lines / 2, rules.patterns.size)
        assertEquals(lines / 4, rules.diagnostics.size)
        assertEquals(lines - 1, rules.diagnostics.last().line)
        val matcher = IgnoreMatcher.of(rules)
        assertTrue(matcher.ignores("x/dir4/file.txt"))
        assertTrue(matcher.ignores("x/y.ext99997"))
        assertTrue(!matcher.ignores("x/y.ext2"))
    }

    // The matcher, the scope and a reference built only on IgnorePattern.matches must always agree.

    private fun ownDecision(
        sets: Map<String, IgnoreRuleSet>,
        path: String,
        isDirectory: Boolean,
    ): IgnoreDecision {
        val parts = path.split('/')
        for (n in parts.size - 1 downTo 0) {
            val set = sets[parts.take(n).joinToString("/")] ?: continue
            val relative = parts.drop(n).joinToString("/")
            for (pattern in set.patterns.asReversed()) {
                if (pattern.matches(relative, isDirectory)) {
                    return if (pattern.isNegated) IgnoreDecision.INCLUDED else IgnoreDecision.IGNORED
                }
            }
        }
        return IgnoreDecision.NONE
    }

    private fun referenceDecision(
        sets: Map<String, IgnoreRuleSet>,
        path: String,
        isDirectory: Boolean,
    ): IgnoreDecision {
        val parts = path.split('/')
        for (n in 1 until parts.size) {
            if (ownDecision(sets, parts.take(n).joinToString("/"), true) == IgnoreDecision.IGNORED) {
                return IgnoreDecision.IGNORED
            }
        }
        return ownDecision(sets, path, isDirectory)
    }

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
    fun matcherScopeAndReferenceAgreeOnRandomTreesWithNestedRules() {
        val names = listOf("a", "b", "c", "é", "a.b", "ab")
        val tokens = listOf("*", "?", "a", "b", "c", "/", "/", "**", "!", "[ab]", ".", "é", "\\*", "[!a]", "ab")
        repeat(400) {
            val paths =
                List(
                    1 + random.nextInt(12),
                ) { List(1 + random.nextInt(4)) { names[random.nextInt(names.size)] }.joinToString("/") }
            val directories =
                paths
                    .flatMap { path ->
                        val parts = path.split('/')
                        (0 until parts.size).map { parts.take(it).joinToString("/") }
                    }.toSet()
            val sets =
                directories
                    .filter { it.isEmpty() || random.nextInt(3) == 0 }
                    .associateWith {
                        IgnoreRuleSet.parse(
                            List(1 + random.nextInt(5)) { randomText(tokens, 5) }.joinToString("\n"),
                        )
                    }
            val matcher = IgnoreMatcher.of(sets)
            for (path in paths) {
                for (isDirectory in listOf(false, true)) {
                    val expected = referenceDecision(sets, path, isDirectory)
                    val label = "$path isDirectory=$isDirectory rules=${sets.mapValues { entry ->
                        entry.value.patterns.map { p -> p.source }
                    }}"
                    assertEquals(expected, matcher.decide(path, isDirectory), "matcher $label")
                    assertEquals(expected, scopeDecision(sets, path, isDirectory), "scope $label")
                }
            }
        }
    }

    // Cost, counted in steps so the result does not depend on the machine.

    private fun deepPath(depth: Int) = (1..depth).joinToString("/") { "d$it" } + "/f.txt"

    @Test
    fun matcherCostGrowsWithTheSquareOfTheDepthAndLinearlyWithTheRules() {
        val rules = IgnoreRuleSet.parse((1..40).joinToString("\n") { "pattern$it\n*.ext$it" })
        val matcher = IgnoreMatcher.of(rules)
        val steps =
            listOf(8, 16, 32, 64).associateWith { depth ->
                val counter = StepCounter()
                matcher.decide(deepPath(depth), false, counter)
                counter.steps
            }
        for ((depth, count) in steps) {
            val bytes = deepPath(depth).length.toLong()
            // Each ancestor is checked against every rule, and a rule reads at most the bytes of the ancestor path.
            assertTrue(count <= 8L * 80 * bytes * (depth + 1), "depth $depth took $count steps")
        }
        // Doubling the depth must not more than about quadruple the work.
        assertTrue(steps.getValue(64) < steps.getValue(32) * 5, "$steps")
        assertTrue(steps.getValue(32) < steps.getValue(16) * 5, "$steps")
    }

    @Test
    fun scopeCostPerEntryDoesNotRepeatTheAncestors() {
        val rules = IgnoreRuleSet.parse((1..40).joinToString("\n") { "pattern$it\n*.ext$it" })
        val steps =
            listOf(8, 16, 32, 64).associateWith { depth ->
                var scope = IgnoreScope.root(rules)
                for (i in 1..depth) scope = scope.enter("d$i")
                val counter = StepCounter()
                scope.decide("f.txt", false, counter)
                counter.steps
            }
        // The entry is a single name below an unrelated directory chain: its cost is that of one path.
        for ((depth, count) in steps) {
            val bytes = deepPath(depth).length.toLong()
            assertTrue(count <= 8L * 80 * bytes, "depth $depth took $count steps")
        }
        assertTrue(steps.getValue(64) < steps.getValue(32) * 3, "$steps")
    }
}
