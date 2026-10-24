package dev.cuervolu.kignore.conformance

/** Answer Git gives for a path: matched by an exclude rule, re-included by a negated rule, or matched by nothing. */
internal enum class ExpectedResult { IGNORED, INCLUDED, NONE }

internal enum class ScenarioKind(
    val label: String,
) {
    /** Behavior stated by gitignore(5). */
    NORMATIVE("normative"),

    /** Behavior of Git that gitignore(5) does not state or that goes beyond it. */
    GIT_QUIRK("git-quirk"),

    /** Behavior that depends on configuration or the host platform. */
    PLATFORM("platform"),
}

internal data class IgnoreFile(
    val path: String,
    val content: String,
)

/** A path relative to the repository root. A trailing slash marks a directory. */
internal data class ExpectedCase(
    val path: String,
    val result: ExpectedResult,
    val rule: String?,
) {
    val isDirectory: Boolean get() = path.endsWith("/")
    val name: String get() = path.removeSuffix("/")
}

internal data class Scenario(
    val id: String,
    val kind: ScenarioKind,
    val spec: String,
    val note: String?,
    val config: Map<String, String>,
    val files: List<IgnoreFile>,
    val cases: List<ExpectedCase>,
    val source: String,
)

internal data class FixtureFile(
    val name: String,
    val gitVersion: String,
    val command: String,
    val defaults: Map<String, String>,
    val scenarios: List<Scenario>,
)

internal object Fixtures {
    val files: List<FixtureFile> by lazy {
        ConformanceData.files.map { (name, text) -> FixtureParser.parse(name, text) }
    }

    val scenarios: List<Scenario> by lazy { files.flatMap { it.scenarios } }
}
