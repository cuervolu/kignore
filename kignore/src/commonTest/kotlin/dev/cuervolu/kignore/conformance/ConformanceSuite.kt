package dev.cuervolu.kignore.conformance

/** What a future matcher has to answer for one path of one scenario. */
internal fun interface ConformanceSubject {
    fun result(
        scenario: Scenario,
        path: String,
        isDirectory: Boolean,
    ): ExpectedResult
}

internal data class Mismatch(
    val scenario: Scenario,
    val case: ExpectedCase,
    val actual: ExpectedResult,
) {
    override fun toString(): String =
        "${scenario.id} [${scenario.kind.label}] ${case.path}: expected ${case.result}, got $actual" +
            (case.rule?.let { " (Git rule $it)" } ?: "")
}

internal object ConformanceSuite {
    fun run(
        subject: ConformanceSubject,
        scenarios: List<Scenario> = Fixtures.scenarios,
        kinds: Set<ScenarioKind> = ScenarioKind.entries.toSet(),
    ): List<Mismatch> =
        scenarios.filter { it.kind in kinds }.flatMap { scenario ->
            scenario.cases.mapNotNull { case ->
                val actual = subject.result(scenario, case.name, case.isDirectory)
                if (actual == case.result) null else Mismatch(scenario, case, actual)
            }
        }
}
