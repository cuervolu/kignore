# Conformance fixtures

This directory holds the executable specification of kignore. It describes, case by case, what Git answers when
asked whether a path is ignored, so that the future matcher can be checked against the same answers on every
Kotlin Multiplatform target.

## What is here

| Path                                                        | Purpose                                                                                                          |
|-------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `scenarios/*.txt`                                           | Hand written scenarios: ignore files and the paths to ask about. No answers.                                     |
| `fixtures/*.txt`                                            | The same scenarios with the answer Git gave for each path. Generated, do not edit.                               |
| `kignore/src/commonTest/.../conformance/ConformanceData.kt` | The fixtures embedded as Kotlin strings, so multiplatform tests never read files. Generated.                     |
| `kignore/src/jvmConformanceTool/`                           | The generator. A JVM only compilation of the `kignore` module, outside `commonMain` and the published artifacts. |

## Commands

Regenerate the fixtures after editing a scenario or after changing the generator:

```shell
./gradlew --quiet --console=plain generateConformanceFixtures
```

Check that the committed fixtures still match what the local Git produces:

```shell
./gradlew --quiet --console=plain checkConformanceFixtures
```

Run the tests that need no Git:

```shell
./gradlew --quiet --console=plain jvmTest macosArm64Test jsTest wasmJsTest
```

The browser targets need a Chromium based browser. Set `CHROME_BIN` when none is installed in the default location.

## Oracle

The generator runs `git check-ignore --no-index -v -n -z --stdin` in a fresh repository for every scenario. The
fixture headers record the Git version and the command, and the versions seen by `--check` are compared without the
version line so a newer Git only produces a warning when the answers agree.

Isolation from the host:

- `GIT_CONFIG_GLOBAL` and `GIT_CONFIG_SYSTEM` point to empty files created inside the temporary directory, and
  `GIT_CONFIG_NOSYSTEM` is set. Nothing depends on `/dev/null`, so the tool is not tied to a POSIX system.
- `HOME`, `XDG_CONFIG_HOME` and `USERPROFILE` point to an empty directory, and `core.excludesFile` points to an empty
  file.
- `core.ignorecase`, `core.precomposeunicode`, `core.quotepath`, `core.autocrlf` and `core.symlinks` are fixed
  explicitly. Scenarios of kind `platform` may override them with a `config` line.
- The environment is cleared apart from `PATH`, `SystemRoot` and `LC_ALL=C`.

The host filesystem is not trusted. Git decides whether a path is a directory by looking at the disk, so every path
is materialized on its own, as a directory when it ends in a slash and as an empty file otherwise, and removed
afterwards. Paths are checked one at a time so names that differ only by case or Unicode normalization never collide.
Before asking Git, the generator lists the repository and aborts if the filesystem stored different names from the
ones the scenario asked for. A scenario that cannot be created faithfully on the host therefore fails loudly and does
not produce a silently altered answer.

## Results

Each case has one of three answers.

| Answer     | Meaning                                                        |
|------------|----------------------------------------------------------------|
| `IGNORED`  | A rule excluded the path.                                      |
| `INCLUDED` | A negated rule matched, so the path is explicitly re-included. |
| `NONE`     | No rule matched.                                               |

`INCLUDED` and `NONE` both mean the path is not ignored, but they are different outcomes and the suite keeps them
apart. They are read from the `-v -z` output, which prints source, line number, pattern and path separated by NUL.
An empty source means no match, and a pattern that starts with `!` means a re-inclusion. The matching rule is kept
after `#` on each `path` line for diagnosis and is not compared.

A path that ends in a slash is a directory. The slash is removed before Git is asked, because passing it makes Git
match `*` against an empty remainder, which a real directory walk never does.

## Scenario kinds

| Kind        | Meaning                                                                                            |
|-------------|----------------------------------------------------------------------------------------------------|
| `normative` | Behavior stated by gitignore(5). The `spec` line quotes the sentence it comes from.                |
| `git-quirk` | Behavior the manual does not state, or that goes beyond it. The `note` line records what Git does. |
| `platform`  | Behavior that depends on configuration, such as `core.ignorecase`.                                 |

A matcher is expected to pass every `normative` scenario. For the others, the second milestone decides case by case
whether to follow Git, and records the decision here.

## Format

A scenario file is plain text. Blank lines and lines starting with `#` between scenarios are ignored.

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

- `scenario` gives a unique id of the form `category/name`.
- `kind`, `spec`, and the optional `note` and `config key=value` lines describe the scenario.
- `file` starts an ignore file at the given repository relative path. The lines that follow, each starting with `|`,
  are its content. `file <path> noeol` leaves out the final line feed.
- `path` lists a path to ask about. Generated fixtures append ` => RESULT # rule`.

Names and content use a backtick as the escape character so that gitignore backslashes can be written as they are:
`` `s `` is a space, `` `t `` a tab, `` `r `` a carriage return, `` `n `` a line feed, ```` `` ```` a backtick,
`` `u00E9 `` a code unit and `` `u{1F600} `` a code point. Leading and trailing spaces and invisible characters are
always escaped in generated files, which makes trailing spaces, CRLF and byte order marks explicit.

Fixtures are excluded from Spotless on purpose. Trimming whitespace or rewriting line endings there would change what
the cases mean.

## Adding a regression

1. Add a scenario to the matching file in `scenarios/`, or create a new `.txt` file there for a new category.
2. Run `generateConformanceFixtures` and read the answers Git gave. Compare them with gitignore(5) and pick the
   `kind` accordingly.
3. Commit the scenario, the regenerated fixtures and `ConformanceData.kt` together.

A new category must also be added to `requiredCategories` in `FixtureIntegrityTest`.

## Tests

`FixtureIntegrityTest` checks that the fixtures are well formed and that they cover what they claim to cover. It is
not evidence of gitignore compatibility.

`GitIgnoreConformanceTest` runs every scenario against a matcher and is the test that measures compatibility. It is
ignored for now because there is no rule set yet, only single patterns. Enabling it is mandatory in the milestone that
introduces the rule engine.

`SingleRuleConformanceTest` checks the pattern matcher against the fixture cases that one pattern can answer without
any precedence or inheritance logic. It is partial evidence and does not mean full compatibility. See the next section.

`ConformanceHarnessTest` checks the harness with a subject that is wrong on purpose.

## Pattern matcher status

The second milestone added `IgnorePattern`, which compiles one gitignore line and answers whether that pattern alone
covers a path. Rules that depend on several patterns, on parent directories or on several files belong to the rule set.

`SingleRuleConformanceTest` reads every scenario that has one root `.gitignore`, no configuration override and no
carriage return or byte order mark. For each case:

- When Git answered `NONE`, no pattern of the file may match the path.
- When Git answered `IGNORED` or `INCLUDED`, the pattern on the line that the fixture names is compared by itself, and
  its negation must agree with the answer.
- When that pattern does not match the path, the case stays pending. The fixture does not say whether Git matched the
  path or one of its parent directories, and telling them apart needs inheritance.

At the time of writing, 481 cases are verified, 43 cases are pending because the match may come from a parent
directory, and 26 scenarios are pending as a whole. The test prints the pending cases when it runs.

| Pending scenarios                                  | Reason                                                              | Milestone |
|----------------------------------------------------|---------------------------------------------------------------------|-----------|
| `case/ignorecase-*`, `unicode/precomposeunicode-*` | Git configuration overrides                                         | 3         |
| `nested/*`, `edge/same-pattern-in-two-levels`      | Several ignore files, relative bases and precedence between files   | 3         |
| `lines/crlf-*`, `lines/bom-*`, `lone-carriage-*`   | Splitting a file into lines, byte order marks and carriage returns  | 3         |

Decisions about Git specific behavior, taken scenario by scenario. Every `git-quirk` scenario that a single pattern can
answer is followed as Git does it.

| Behavior                                                         | Decision                                                                     |
|------------------------------------------------------------------|------------------------------------------------------------------------------|
| `?` and bracket expressions consume one byte                     | Followed. Patterns and paths are compared as UTF-8 bytes.                    |
| Unterminated bracket, unknown `[:class:]`, trailing backslash    | Reported as `Invalid`. Git never lets such a line match.                     |
| Reversed range such as `[z-a]`                                   | Followed. Only the first character of the range is in the set.               |
| Dash first or last, `]` first in a set                           | Followed. They are literal members.                                          |
| A run of three or more stars forming a whole segment             | Followed. It acts as `**`, so `a/***/b` spans directories.                   |
| `\/` in a pattern                                               | Followed. It separates segments like an unescaped slash.                     |
| Trailing slash, then the separator test                          | Followed. Only the last slash is removed, so `foo//` never matches.          |
| Lone `!`, `/` or `//`                                            | Reported as `Skipped`. They match nothing in Git either.                     |
| Trailing tab, trailing spaces after an escaped space             | Followed. Only unescaped spaces at the very end are removed.                 |
| CR before LF, byte order mark                                    | Not handled by `parse`, which takes one line. Left to the rule set.          |
| Lone surrogate in a Kotlin string                                | Encoded as U+FFFD on every platform, which `encodeToByteArray` does not do.  |

Path contract of `IgnorePattern.matches`: the path is relative, uses `/`, is already normalized and does not end in a
slash. An empty path, an absolute path, a `.` or `..` segment, a repeated separator and a trailing separator match no
pattern and never raise an error. The caller normalizes paths.

Cost: a match takes at most about `pattern bytes * path bytes` steps plus one attempt for each pair of pattern segment
and path segment, with no recursion. Neither input has to be small for the matcher to stay predictable.

## Limitations of the oracle

- Git matches bytes, not characters. A `?` matches one byte, so `a?b` does not match `aéb` while `a??b` does. A
  matcher on Kotlin strings has to decide how to reproduce this, for example by comparing UTF-8 bytes.
- The answers come from `git check-ignore --no-index` with one path at a time. Behavior of directory walks, such as
  `git status` or `git add`, is not covered.
- Paths that are not normalized, such as `a//b` or `./a`, are not part of the cases. Git cleans them before matching.
  kignore does not clean them and treats them as matching nothing.
- Names that the host filesystem cannot store faithfully, for example a name containing a backslash on Windows, make
  the generator abort. Generate fixtures on a POSIX host.
- Case insensitive behavior is only covered through `core.ignorecase=true` and only for the file name part of the
  path. Case folding in Git is limited to ASCII letters.
- Symbolic links, `.git/info/exclude`, `core.excludesFile` and `git check-ignore` without `--no-index` are out of
  scope for this milestone.
- The embedded Kotlin copy stores each fixture file in one function. A very large fixture file may reach the JVM
  method size limit and would need to be split.
