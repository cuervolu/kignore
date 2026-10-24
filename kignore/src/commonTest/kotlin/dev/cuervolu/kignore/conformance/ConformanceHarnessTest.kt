package dev.cuervolu.kignore.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tests the harness itself with a subject that is wrong on purpose. This is not a conformance check. */
class ConformanceHarnessTest {
    @Test
    fun aSubjectThatNeverMatchesIsReportedWhereGitMatched() {
        val mismatches = ConformanceSuite.run(ConformanceSubject { _, _, _ -> ExpectedResult.NONE })
        val expected = Fixtures.scenarios.flatMap { it.cases }.count { it.result != ExpectedResult.NONE }
        assertTrue(expected > 0)
        assertEquals(expected, mismatches.size)
        assertTrue(mismatches.all { it.actual == ExpectedResult.NONE && it.case.result != ExpectedResult.NONE })
    }

    @Test
    fun includedAndNoneAreDifferentAnswers() {
        val mismatches = ConformanceSuite.run(ConformanceSubject { _, _, _ -> ExpectedResult.INCLUDED })
        assertTrue(mismatches.any { it.case.result == ExpectedResult.NONE })
        assertTrue(mismatches.any { it.case.result == ExpectedResult.IGNORED })
    }

    @Test
    fun theSubjectReceivesRelativePathsAndTheDirectoryFlag() {
        val seen = ArrayList<Triple<String, String, Boolean>>()
        ConformanceSuite.run(
            subject =
                ConformanceSubject { scenario, path, isDirectory ->
                    seen.add(Triple(scenario.id, path, isDirectory))
                    ExpectedResult.NONE
                },
            scenarios =
                Fixtures.scenarios.filter {
                    it.id == "directories/trailing-slash-matches-only-directories"
                },
        )
        assertTrue(Triple("directories/trailing-slash-matches-only-directories", "build", true) in seen)
        assertTrue(Triple("directories/trailing-slash-matches-only-directories", "build", false) in seen)
    }

    @Test
    fun scenariosCanBeFilteredByKind() {
        val mismatches =
            ConformanceSuite.run(
                ConformanceSubject {
                    _,
                    _,
                    _,
                    ->
                    ExpectedResult.NONE
                },
                kinds = setOf(ScenarioKind.PLATFORM),
            )
        assertTrue(mismatches.isNotEmpty())
        assertTrue(mismatches.all { it.scenario.kind == ScenarioKind.PLATFORM })
    }
}
