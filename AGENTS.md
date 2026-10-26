## Dependency inspection

Use `ksrc` to inspect dependency APIs and implementations instead of browsing Gradle caches manually.

```shell
ksrc --help
ksrc deps --project .
```

Prefer inspecting actual source code over guessing APIs.

## Gradle and verification

Run Gradle commands from the repository root using machine-friendly output:

```shell
./gradlew --quiet --console=plain <task>
```

Before finishing, run relevant compilation and test tasks for the affected targets.

Format and check Kotlin and Gradle Kotlin DSL files with Spotless:

```shell
./gradlew --quiet --console=plain spotlessApply
./gradlew --quiet --console=plain spotlessCheck
```

Spotless does not touch the conformance fixtures or the generated `ConformanceData.kt`. Regenerate them with `generateConformanceFixtures` instead of editing or formatting them.

Fix failures introduced by your changes. Do not modify Gradle configuration or dependencies unnecessarily.

## Testing

Use `commonTest` for platform-independent behavior.

- Prioritize Gitignore compatibility, edge cases, and regression tests.
- Keep conformance fixtures deterministic and version-controlled.
- Use Git as a reference oracle, but never require Git to execute multiplatform tests.
- Test behavior rather than implementation details.
- Add regression tests for every discovered matching bug.
- Run `fuzzAgainstGit` after changing the parser or the matching engine. A divergence it reports becomes a scenario in `conformance/scenarios`, never an edited expectation. See `conformance/README.md`.
- Measure before optimizing. `jvmBenchmarkBenchmark` and `measureAllocations` are development tools and are not part of `check`.
- Run `checkKotlinAbi` after changing the public API, and `updateKotlinAbi` only when the change is intended.

## Kotlin conventions

Write clear, idiomatic Kotlin.

- Prefer immutable data and explicit APIs.
- Keep matching logic independent of filesystem and platform APIs.
- Avoid unnecessary allocations in performance-sensitive paths.
- Do not introduce frameworks or abstractions without a concrete need.

## Comments

Comments should explain non-obvious behavior, constraints, and implementation decisions.

Avoid redundant comments, emojis, em dashes, and artificial `term: description` writing patterns in documentation.

Use clear, concise English.

## Documentation

Write documentation for human readers, not as implementation logs or agent handoff reports.

Keep READMEs concise and focused on current behavior, usage, and maintenance. Avoid milestone histories, exhaustive decision tables, redundant technical explanations, and resolved TODOs.

Document implementation details close to the relevant code or tests. Prefer clear, natural prose over mechanical summaries.

## Scope discipline

Keep changes focused on the requested task.

Do not perform unrelated refactors, introduce speculative abstractions, or implement features beyond the requested scope.

Inspect existing code and conventions before making changes.
