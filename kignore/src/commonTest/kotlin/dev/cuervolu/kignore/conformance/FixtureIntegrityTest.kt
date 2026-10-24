package dev.cuervolu.kignore.conformance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks that the committed fixtures are well formed and cover what the suite promises.
 *
 * Passing here says nothing about gitignore compatibility. Only [GitIgnoreConformanceTest] does, once there is
 * a matcher to run it against.
 */
class FixtureIntegrityTest {
    private val requiredCategories =
        setOf(
            "wildcards",
            "anchoring",
            "directories",
            "negation",
            "nested",
            "characters",
            "lines",
            "unicode",
            "case",
            "edge",
        )

    @Test
    fun fixtureFilesParseAndRecordTheirProvenance() {
        assertTrue(Fixtures.files.isNotEmpty())
        for (file in Fixtures.files) {
            assertTrue(file.gitVersion.startsWith("git version "), "${file.name}: git version '${file.gitVersion}'")
            assertTrue("check-ignore" in file.command && "--no-index" in file.command, "${file.name}: command")
            assertEquals("false", file.defaults["core.ignorecase"], "${file.name}: core.ignorecase default")
            assertTrue(file.scenarios.isNotEmpty(), "${file.name}: no scenarios")
        }
    }

    @Test
    fun allFixturesWereProducedByTheSameGitVersion() {
        assertEquals(
            1,
            Fixtures.files
                .map { it.gitVersion }
                .toSet()
                .size,
        )
    }

    @Test
    fun scenarioIdsAreUniqueAndBelongToTheirFile() {
        val ids = Fixtures.scenarios.map { it.id }
        assertEquals(ids.toSet().size, ids.size, "duplicate scenario ids")
        for (file in Fixtures.files) {
            for (scenario in file.scenarios) {
                assertTrue(scenario.id.contains('/') && scenario.id.substringAfter('/').isNotBlank(), scenario.id)
            }
        }
    }

    @Test
    fun everyRequiredCategoryHasScenarios() {
        val present = Fixtures.scenarios.map { it.id.substringBefore('/') }.toSet()
        assertEquals(emptySet(), requiredCategories - present)
    }

    @Test
    fun everyScenarioHasSpecFilesAndCases() {
        for (s in Fixtures.scenarios) {
            assertTrue(s.spec.isNotBlank(), "${s.id}: spec")
            assertTrue(s.files.isNotEmpty(), "${s.id}: no ignore files")
            assertTrue(s.cases.isNotEmpty(), "${s.id}: no cases")
            assertEquals(
                s.cases
                    .map { it.path }
                    .toSet()
                    .size,
                s.cases.size,
                "${s.id}: duplicate paths",
            )
            assertEquals(
                s.files
                    .map { it.path }
                    .toSet()
                    .size,
                s.files.size,
                "${s.id}: duplicate files",
            )
        }
    }

    @Test
    fun pathsAreNormalizedRelativePaths() {
        for (s in Fixtures.scenarios) {
            val paths = s.files.map { it.path } + s.cases.map { it.name }
            for (path in paths) {
                val segments = path.split('/')
                assertTrue(segments.none { it.isEmpty() || it == "." || it == ".." }, "${s.id}: path '$path'")
            }
            for (file in s.files) {
                assertTrue(file.path.substringAfterLast('/') == ".gitignore", "${s.id}: ignore file '${file.path}'")
            }
        }
    }

    @Test
    fun resultsAndRulesAreConsistent() {
        for (s in Fixtures.scenarios) {
            for (case in s.cases) {
                val where = "${s.id}: ${case.path}"
                when (case.result) {
                    ExpectedResult.NONE -> {
                        assertEquals(null, case.rule, where)
                    }

                    ExpectedResult.IGNORED, ExpectedResult.INCLUDED -> {
                        val rule = assertNotNullRule(case, where)
                        val pattern = rule.split(':', limit = 3)[2]
                        assertEquals(case.result == ExpectedResult.INCLUDED, pattern.startsWith("!"), where)
                    }
                }
            }
        }
    }

    @Test
    fun platformScenariosDeclareTheirConfiguration() {
        val platform = Fixtures.scenarios.filter { it.kind == ScenarioKind.PLATFORM }
        assertTrue(platform.isNotEmpty())
        for (s in platform) assertTrue(s.config.isNotEmpty(), "${s.id}: platform scenario without config")
        for (s in Fixtures.scenarios.filter { it.kind != ScenarioKind.PLATFORM }) {
            assertTrue(s.config.isEmpty(), "${s.id}: configuration only belongs in platform scenarios")
        }
    }

    @Test
    fun allThreeResultsAndAllKindsAreRepresented() {
        assertEquals(
            ExpectedResult.entries.toSet(),
            Fixtures.scenarios
                .flatMap { it.cases }
                .map { it.result }
                .toSet(),
        )
        assertEquals(ScenarioKind.entries.toSet(), Fixtures.scenarios.map { it.kind }.toSet())
    }

    @Test
    fun fixturesExerciseTheByteLevelCasesTheyClaimToCover() {
        val contents = Fixtures.scenarios.flatMap { it.files }.map { it.content }
        val names = Fixtures.scenarios.flatMap { s -> s.files.map { it.path } + s.cases.map { it.path } }
        assertTrue(contents.any { it.startsWith("") }, "byte order mark")
        assertTrue(contents.any { "\r\n" in it }, "CRLF")
        assertTrue(contents.any { it.isNotEmpty() && !it.endsWith("\n") }, "missing final newline")
        assertTrue((contents + names).any { s -> s.any { it.isHighSurrogate() } }, "characters outside the BMP")
        assertTrue(names.any { "é" in it } && names.any { "é" in it }, "NFC and NFD forms")
    }

    @Test
    fun unescapeDecodesEveryEscape() {
        assertEquals("a b\t\r\n`", FixtureParser.unescape("a`sb`t`r`n``"))
        assertEquals("é", FixtureParser.unescape("`u00E9"))
        assertEquals("😀", FixtureParser.unescape("`u{1F600}"))
        assertEquals("\\ * [", FixtureParser.unescape("\\`s*`s["))
    }

    private fun assertNotNullRule(
        case: ExpectedCase,
        where: String,
    ): String {
        val rule = case.rule
        assertTrue(rule != null && rule.count { it == ':' } >= 2, "$where: rule '$rule'")
        return rule
    }
}
