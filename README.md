# kignore

kignore is a Kotlin Multiplatform library that answers the question `git check-ignore` answers: given the rules of one
or more `.gitignore` files, is this path ignored?

It does only the matching. It does not read files, walk directories or look for ignore files, so the same code runs on
the JVM, JavaScript, Wasm and native targets. Its answers are checked against a real Git by a conformance suite, which
is described in [conformance/README.md](conformance/README.md).

## Installation

kignore is published to Maven Central. In a Kotlin Multiplatform project, add it to the common source set:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("dev.cuervolu:kignore:0.1.0")
        }
    }
}
```

In a JVM-only project, use the same coordinates in `dependencies`.

## Checking a path

Parse the text of an ignore file once and ask as many questions as you like.

```kotlin
val rules = IgnoreRuleSet.parse("*.log\n!keep.log\nbuild/\n")
val matcher = IgnoreMatcher.of(rules)

matcher.ignores("debug.log")                       // true
matcher.ignores("keep.log")                        // false
matcher.ignores("build", isDirectory = true)       // true
matcher.ignores("build/out.o")                     // true, its parent is excluded
matcher.decide("keep.log")                         // IgnoreDecision.INCLUDED
```

Git needs to know whether a path is a directory, because rules such as `build/` only match directories. kignore asks
you through `isDirectory`, which is `false` unless you say otherwise. A trailing slash in the path is not accepted.

`decide` tells more than `ignores`. `IGNORED` means a rule excludes the path, `INCLUDED` means a negated rule brought it
back, and `NONE` means no rule said anything. Only `IGNORED` counts as ignored.

## Nested ignore files

Give the matcher the rules of each directory that has an ignore file. The key is the directory, relative to the root,
and the empty string is the root itself.

```kotlin
val matcher = IgnoreMatcher.of(
    mapOf(
        "" to IgnoreRuleSet.parse("*.log"),
        "docs" to IgnoreRuleSet.parse("!*.log"),
    ),
)

matcher.ignores("a.log")          // true
matcher.ignores("docs/a.log")     // false
```

For `docs/a.log`, the rule in `docs` is the one that applies. A file deeper in the tree has the last word on the paths
its rules match, and inside a file the last matching line wins. When nothing in the deeper file matches, the rules of
the directories above still apply, so `docs/a.txt` is judged by the root file. A directory excluded by any file stays
excluded, whatever the files below it say.

## Walking a directory tree

`IgnoreMatcher` checks every parent of the path on each question, which suits an isolated check such as a file watcher
event. To scan a whole tree, use `IgnoreScope`. It evaluates each directory once, when you enter it, and then answers
for the entries inside it.

```kotlin
val root = IgnoreScope.root(IgnoreRuleSet.parse("*.log\nbuild/\n"))

root.ignores("main.kt")                                        // false
val src = root.enter("src") { IgnoreRuleSet.parse("!debug.log") }
src.ignores("debug.log")                                       // false
src.ignores("app.log")                                         // true
root.enter("build") { error("not called") }.isIgnored          // true
```

The function passed to `enter` loads the ignore file of that directory. It runs once, and only when the directory is not
ignored, so you never read a file Git would not read. Walking a tree is a loop that calls `enter` for each subdirectory
and `ignores` for each file. You can also pass an `IgnoreRuleSet` directly, but it is dropped when the directory is
ignored. A scope takes one name at a time, never a path, so no directory can be skipped by accident.

## Malformed lines

Git warns about a line it cannot compile and carries on. kignore does the same, and tells you which lines it left out.

```kotlin
val rules = IgnoreRuleSet.parse("*.log\n[abc\n", origin = ".gitignore")

rules.patterns.size                            // 1
rules.diagnostics.single().message             // .gitignore:2: bracket expression is not closed at index 0
```

To work with a single line, use `IgnorePattern.parse`. It returns `Parsed`, `Invalid` or `Skipped` for blank lines and
comments.

## What to know before using it

- Paths are relative to the directory of the ignore file, use `/` as the only separator and must already be
  normalized. The matcher and the scope throw `IllegalArgumentException` for an empty path, a leading or trailing slash,
  or a `.`, `..` or empty segment. `IgnorePattern.matches` answers `false` instead. On Windows, convert backslashes
  before asking, because kignore treats a backslash as an ordinary character on every platform.
- Matching follows Git byte by byte on UTF-8. In `a?b`, the `?` matches one byte, so it does not match `aéb`, but `a??b`
  does. The same applies to bracket expressions.
- `IgnoreRuleSet.parse(text, ignoreCase = true)` imitates `core.ignorecase`. Only ASCII letters are folded, as in Git.
- Rule sets, matchers and scopes are immutable and safe to share between threads.
- `.git/info/exclude`, `core.excludesFile` and symbolic links are not part of the model.

## Compatibility

Tests run on the JVM, macOS arm64, JavaScript (browser) and Wasm (browser). The `linuxX64` and `mingwX64` targets are
compiled but their tests are not run. Behavior is checked against Git itself, as described in
[conformance/README.md](conformance/README.md).

## Building and testing

Run Gradle from the repository root.

```shell
./gradlew --quiet --console=plain jvmTest macosArm64Test jsTest wasmJsTest
./gradlew --quiet --console=plain spotlessCheck
```

The JavaScript and Wasm tests need a Chromium based browser, and `CHROME_BIN` can point to it. The tests never need Git. Git is only needed
to regenerate or check the conformance fixtures, and to run the differential fuzzer, as explained in
[conformance/README.md](conformance/README.md).
