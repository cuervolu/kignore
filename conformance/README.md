# Conformance suite

kignore aims to answer exactly what Git answers when it is asked whether a path is ignored. This directory holds the
evidence. It describes small situations (a few ignore files and some paths), records what Git said about each path, and
lets the tests check that kignore says the same thing on every Kotlin Multiplatform target.

## How it fits together

You write scenarios by hand in `scenarios/`. A generator runs your local Git on each one and writes the answers to
`fixtures/`. Because the tests run on JS, wasm and native targets that cannot read files or run Git, the generator also
embeds the fixtures in `kignore/src/commonTest/.../conformance/ConformanceData.kt`. Do not edit `fixtures/` or
`ConformanceData.kt` by hand, and Spotless leaves them alone on purpose because changing whitespace there would change
what the cases mean. Git is only needed to regenerate or check fixtures, never to run the tests.

## Commands

```shell
./gradlew --quiet --console=plain generateConformanceFixtures   # rewrite fixtures and ConformanceData.kt from scenarios
./gradlew --quiet --console=plain checkConformanceFixtures      # fail if the committed fixtures differ from local Git
./gradlew --quiet --console=plain jvmTest macosArm64Test jsTest wasmJsTest
```

The browser targets need a Chromium based browser. Set `CHROME_BIN` if none is in the default location. The
`linuxX64` and `mingwX64` targets are only compiled, since their tests cannot run on a macOS host.

## Adding a case

Add a scenario to the matching file in `scenarios/`, or create a new file for a new category (and add the category to
`requiredCategories` in `FixtureIntegrityTest`):

```
scenario negation/parent-excluded
kind normative
spec It is not possible to re-include a file if a parent directory of that file is excluded.
file .gitignore
| build/
| !build/keep.txt
path build/keep.txt
path build/
```

`file` starts an ignore file at a repository relative path and the `|` lines are its content. Each `path` is a question
for Git, and a trailing slash marks a directory. Inside names and content, a backtick escapes the characters that are
hard to see: `` `s `` is a space, `` `t `` a tab, `` `r `` a carriage return, `` `u00E9 `` a code unit and
`` `u{1F600} `` a code point.

Choose `kind` by looking at what Git answered. Use `normative` when gitignore(5) states the behavior, `git-quirk` when
it goes beyond the manual, and `platform` when it depends on configuration such as `core.ignorecase` (set with a
`config key=value` line). Then regenerate and commit the scenario, `fixtures/` and `ConformanceData.kt` together.

## Current status

There are 141 scenarios and 666 cases: 95 normative, 37 git-quirk and 9 platform scenarios. All of them agree with Git
through both `IgnoreMatcher` and `IgnoreScope`, on the JVM, macOS arm64, JS and wasmJs. Nothing is pending, and kignore
does not differ from Git on any case.

`GitIgnoreConformanceTest` is the test that measures compatibility. A failure in a normative scenario is a bug to fix,
never something to skip. If kignore ever has to differ from Git on purpose, list the scenario and path in
`deliberateDifferences` in that test and explain why here. The expectations produced by Git are never edited to get a
green build. `FixtureIntegrityTest` only checks that the fixtures are well formed, and `SingleRuleConformanceTest`
checks single patterns against the cases that one pattern can answer. Neither one is evidence of full compatibility.

Where kignore follows Git on details that gitignore(5) leaves open, the behavior is described in the KDoc of
`IgnorePattern`, `IgnoreRuleSet.parse` and `IgnoreMatcher`, and is pinned by the scenarios themselves.

## Differential fuzzing

The scenarios are written by hand, so they only cover what someone thought of. `fuzzAgainstGit` generates random
scenarios (trees, nested ignore files, wildcards, bracket expressions, escapes, negation, non-ASCII names and
`core.ignorecase`), asks Git about every path and compares the answer with `IgnoreMatcher` and with an `IgnoreScope` walk.

```shell
./gradlew --quiet --console=plain fuzzAgainstGit --args='--seed 1 --count 5000'
```

A scenario is a function of the seed and its index, so `--seed 1 --from 4210 --count 1` reproduces one exactly. Other
options are `--threads` and `--max-divergences`. At the end the tool reports how many cases a rule matched, how many
different rule lines it produced and how many lines used each piece of syntax, so a run can be judged by its variety and
not only by its size. It exits with a failure when it finds a difference.

When kignore and Git disagree, the tool removes paths, files, lines and characters for as long as the difference
remains and prints the smallest scenario in the format of `scenarios/`. Check what Git does, add the scenario to the
right file with an honest `kind` and `spec`, fix kignore if it is the one that is wrong, regenerate the fixtures and
update the scenario count in `GitIgnoreConformanceTest`. Never change an expectation to make a run pass.
`--inject-fault drop-negation` makes kignore ignore every negated line on purpose, which shows that the tool notices and
reduces a real difference.

The fuzzer asks Git about all the paths of a scenario in one `git check-ignore` call, from a template repository, which
is much faster than the one path at a time oracle that generates the fixtures. `verifyBatchOracle` runs both on every
conformance scenario that fits in one tree and fails if their answers differ. Run it after touching the oracle code.
Scenarios that the host cannot hold, for example names that differ only by case on a case insensitive volume, use the
slow oracle or are skipped and counted. Names that start with a colon are never generated, because Git reads them as
pathspecs.

## Limits of the oracle

- The answers come from `git check-ignore --no-index` with one path at a time, so directory walks such as
  `git status` are not covered. Symbolic links, `.git/info/exclude` and `core.excludesFile` are out of scope.
- Paths that are not normalized, such as `a//b` or `./a`, are not part of the cases. Git cleans them and kignore
  rejects them.
- The generator creates every path on the real filesystem and stops if the host stores a different name than the one
  requested. Generate fixtures on a POSIX host with a case sensitive volume if you need names that differ only by case.
  For that reason it is not verified against Git whether, with `core.ignorecase`, the directory holding a nested ignore
  file is found without regard to case. `IgnoreMatcher.of` compares those directory names exactly, and its tests pin
  that choice.
- Git folds case for ASCII letters only, and so does kignore.
- Each fixture file is embedded in one Kotlin function, so a very large one could hit the JVM method size limit and
  would need to be split.
