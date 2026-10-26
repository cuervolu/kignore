# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [0.1.0]

First release.

### Added

- `IgnoreRuleSet` parses the text of an ignore file, keeps the valid patterns and reports the lines it left out as
  diagnostics, the way Git warns about lines it cannot compile.
- `IgnoreMatcher` answers whether a path is ignored for one or more ignore files, including negation, directory-only
  patterns, anchoring, `**`, bracket expressions and the rule that a path cannot be re-included once its parent
  directory is excluded.
- `IgnoreScope` evaluates each directory once, for walking a whole tree.
- Matching follows Git byte by byte on UTF-8, with optional `core.ignorecase` behavior.
- Immutable rule sets, matchers and scopes that are safe to share between threads.

### Compatibility

- Behavior is checked against Git with a conformance suite that runs on every supported target.
- Targets: JVM (Java 17), JavaScript (browser), Wasm (browser), `macosArm64`, `linuxX64` and `mingwX64`.
- No dependencies other than the Kotlin standard library.
- Not covered: `.git/info/exclude`, `core.excludesFile` and symbolic links.

[0.1.0]: https://github.com/cuervolu/kignore/releases/tag/v0.1.0
