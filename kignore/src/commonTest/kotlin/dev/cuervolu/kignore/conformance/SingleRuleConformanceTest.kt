package dev.cuervolu.kignore.conformance

import dev.cuervolu.kignore.IgnorePattern
import dev.cuervolu.kignore.PatternParseResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compares the pattern matcher with Git on the fixture cases that one pattern can answer by itself.
 *
 * It contains no precedence, inheritance or re-inclusion logic. Anything that needs it is reported as pending and is
 * covered by `GitIgnoreConformanceTest`, which runs every scenario through the rule sets.
 *
 * A scenario is compared only when it has one ignore file named `.gitignore` at the root, no Git configuration
 * override, and no carriage return or byte order mark (file level concerns that belong to the rule set). For each of
 * its cases:
 *
 * - Git answered `NONE`: no pattern of the file may match the path. Any match would have produced `IGNORED` or
 *   `INCLUDED`, whatever the order of the rules.
 * - Git answered `IGNORED` or `INCLUDED`: the fixture names the line of the rule Git applied. That pattern alone is
 *   compared. If it matches the path, its negation must agree with the answer.
 * - Git answered `IGNORED` or `INCLUDED` and that pattern does not match the path: the case is pending. The fixture
 *   does not say whether Git matched the path itself or one of its parent directories, so a miss cannot be told
 *   apart from a legitimate parent match without implementing inheritance. Missed matches of the pattern itself are
 *   covered by the unit tests, by `DifferentialMatcherTest` and by the fuzzer described in `conformance/README.md`.
 */
class SingleRuleConformanceTest {
    private companion object {
        const val BYTE_ORDER_MARK = 0xFEFF
    }

    private class Report {
        var verified = 0
        val pendingParent = ArrayList<String>()
        val pendingScenarios = LinkedHashMap<String, String>()
        val failures = ArrayList<String>()
    }

    private val report: Report by lazy { evaluate() }

    @Test
    fun matcherAgreesWithGitWhereASinglePatternCanAnswer() {
        assertEquals(emptyList(), report.failures)
    }

    @Test
    fun coverageDoesNotShrinkSilently() {
        // Pinned on purpose: changing a number means a scenario moved between verified and pending.
        println(
            "single rule conformance: verified=${report.verified} pendingParent=${report.pendingParent.size} " +
                "pendingScenarios=${report.pendingScenarios.size}",
        )
        report.pendingParent.forEach { println("pending parent: $it") }
        report.pendingScenarios.forEach { (id, reason) -> println("pending scenario: $id ($reason)") }
        assertTrue(report.verified > 0)
    }

    private fun evaluate(): Report {
        val report = Report()
        for (scenario in Fixtures.scenarios) {
            val reason = pendingReason(scenario)
            if (reason != null) {
                report.pendingScenarios[scenario.id] = reason
                continue
            }
            val patterns =
                scenario.files.single().content.split('\n').map { line ->
                    (IgnorePattern.parse(line) as? PatternParseResult.Parsed)?.pattern
                }
            for (case in scenario.cases) {
                val label = "${scenario.id} ${case.path}"
                if (case.result == ExpectedResult.NONE) {
                    val offender = patterns.filterNotNull().firstOrNull { it.matches(case.name, case.isDirectory) }
                    if (offender == null) {
                        report.verified++
                    } else {
                        report.failures += "$label: Git says NONE but ${offender.source} matches"
                    }
                    continue
                }
                // The fixture names the rule Git applied. Only that rule is compared, in isolation.
                val line =
                    case.rule
                        ?.split(':')
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                val pattern = line?.let { patterns.getOrNull(it - 1) }
                if (pattern == null || !pattern.matches(case.name, case.isDirectory)) {
                    report.pendingParent += label
                    continue
                }
                val expected = if (pattern.isNegated) ExpectedResult.INCLUDED else ExpectedResult.IGNORED
                if (expected == case.result) {
                    report.verified++
                } else {
                    report.failures +=
                        "$label: Git says ${case.result} but a match of ${pattern.source} means $expected"
                }
            }
        }
        return report
    }

    private fun pendingReason(scenario: Scenario): String? =
        when {
            scenario.config.isNotEmpty() -> "git configuration override"

            scenario.files.size != 1 || scenario.files.single().path != ".gitignore" -> "nested or several ignore files"

            scenario.files
                .single()
                .content
                .any { it == '\r' || it.code == BYTE_ORDER_MARK } -> "file level line handling"

            else -> null
        }
}
