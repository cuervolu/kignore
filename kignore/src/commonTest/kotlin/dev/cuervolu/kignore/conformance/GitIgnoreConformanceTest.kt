package dev.cuervolu.kignore.conformance

import dev.cuervolu.kignore.IgnoreDecision
import dev.cuervolu.kignore.IgnoreMatcher
import dev.cuervolu.kignore.IgnoreRuleSet
import dev.cuervolu.kignore.IgnoreScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs every fixture scenario against the real rule engine, through both public entry points.
 *
 * Both subjects must agree with Git on every case. A failure in a normative scenario is a bug to fix and is never
 * excluded here. Deliberate differences from Git would have to be listed in [deliberateDifferences] with the reason
 * written in `conformance/README.md`; the list is empty because there are none.
 */
class GitIgnoreConformanceTest {
    private companion object {
        /** `scenario id` to the paths in which kignore deliberately answers differently from Git. */
        val deliberateDifferences: Map<String, Set<String>> = emptyMap()
    }

    private fun ignoreCase(scenario: Scenario) = scenario.config["core.ignorecase"] == "true"

    private fun ruleSets(scenario: Scenario): Map<String, IgnoreRuleSet> =
        scenario.files.associate { file ->
            file.path.substringBeforeLast('/', "") to
                IgnoreRuleSet.parse(file.content, file.path, ignoreCase(scenario))
        }

    private fun IgnoreDecision.toResult() =
        when (this) {
            IgnoreDecision.IGNORED -> ExpectedResult.IGNORED
            IgnoreDecision.INCLUDED -> ExpectedResult.INCLUDED
            IgnoreDecision.NONE -> ExpectedResult.NONE
        }

    private val matcherSubject =
        ConformanceSubject { scenario, path, isDirectory ->
            IgnoreMatcher.of(ruleSets(scenario)).decide(path, isDirectory).toResult()
        }

    /** Walks from the root one directory at a time, loading the rules of each directory on the way. */
    private val scopeSubject =
        ConformanceSubject { scenario, path, isDirectory ->
            val rules = ruleSets(scenario)
            var scope = IgnoreScope.root(rules[""] ?: IgnoreRuleSet.EMPTY)
            val names = path.split('/')
            val directories = StringBuilder()
            for (name in names.dropLast(1)) {
                if (directories.isNotEmpty()) directories.append('/')
                directories.append(name)
                scope = scope.enter(name, rules[directories.toString()] ?: IgnoreRuleSet.EMPTY)
            }
            scope.decide(names.last(), isDirectory).toResult()
        }

    private fun run(
        subject: ConformanceSubject,
        kind: ScenarioKind,
    ): List<Mismatch> =
        ConformanceSuite.run(subject, kinds = setOf(kind)).filterNot {
            it.case.path in deliberateDifferences[it.scenario.id].orEmpty()
        }

    @Test
    fun normativeScenariosMatchGit() {
        assertEquals(emptyList(), run(matcherSubject, ScenarioKind.NORMATIVE))
        assertEquals(emptyList(), run(scopeSubject, ScenarioKind.NORMATIVE))
    }

    @Test
    fun gitSpecificScenariosAreDecidedExplicitly() {
        assertEquals(emptyList(), run(matcherSubject, ScenarioKind.GIT_QUIRK))
        assertEquals(emptyList(), run(scopeSubject, ScenarioKind.GIT_QUIRK))
    }

    @Test
    fun platformScenariosMatchGitWithTheirConfiguration() {
        assertEquals(emptyList(), run(matcherSubject, ScenarioKind.PLATFORM))
        assertEquals(emptyList(), run(scopeSubject, ScenarioKind.PLATFORM))
    }

    @Test
    fun everyScenarioWasEvaluated() {
        val cases = Fixtures.scenarios.sumOf { it.cases.size }
        println(
            "conformance: ${Fixtures.scenarios.size} scenarios, $cases cases, ${deliberateDifferences.size} with deliberate differences",
        )
        assertEquals(141, Fixtures.scenarios.size)
    }
}
