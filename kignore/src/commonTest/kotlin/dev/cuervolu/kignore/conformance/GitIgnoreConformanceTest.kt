package dev.cuervolu.kignore.conformance

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs every fixture scenario against the matcher.
 *
 * This is the test that measures gitignore compatibility. It is ignored only because the matcher does not exist
 * yet. Removing the [Ignore] annotations and connecting a real [ConformanceSubject] is mandatory in the milestone
 * that introduces the rule engine. [FixtureIntegrityTest] must not be treated as evidence of conformance.
 */
class GitIgnoreConformanceTest {
    private val subject: ConformanceSubject
        get() = TODO("Connect the matcher once it exists")

    @Ignore
    @Test
    fun normativeScenariosMatchGit() {
        assertEquals(emptyList(), ConformanceSuite.run(subject, kinds = setOf(ScenarioKind.NORMATIVE)))
    }

    @Ignore
    @Test
    fun gitSpecificScenariosAreDecidedExplicitly() {
        assertEquals(emptyList(), ConformanceSuite.run(subject, kinds = setOf(ScenarioKind.GIT_QUIRK)))
    }

    @Ignore
    @Test
    fun platformScenariosMatchGitWithTheirConfiguration() {
        assertEquals(emptyList(), ConformanceSuite.run(subject, kinds = setOf(ScenarioKind.PLATFORM)))
    }
}
