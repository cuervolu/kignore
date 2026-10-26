#!/usr/bin/env bash
# Verifies that the checked out commit can be released as the requested version, builds the
# publications in a temporary repository and prints the digest of their files. The version is
# checked against the published files, because the plugin sets it on the publications only.
# Usage: verify-release.sh <version> <repository>
# Needs GH_TOKEN, GITHUB_REF and GITHUB_SHA, which GitHub Actions provides.
set -euo pipefail

version=$1
repo=$2

fail() {
  echo "error: $*" >&2
  exit 1
}

[[ "$GITHUB_REF" == "refs/heads/main" ]] || fail "releases are only built from main, not $GITHUB_REF"
[[ "$(git rev-parse HEAD)" == "$GITHUB_SHA" ]] || fail "the checked out commit is not $GITHUB_SHA"
[[ -z "$(git status --porcelain)" ]] || fail "the working tree is not clean"

# CI only runs for changes that can affect the build, so the commit may not have a run of its own.
# Accept the latest successful run on main when nothing that CI watches changed since then.
# Keep these pathspecs in sync with the paths in .github/workflows/ci.yml.
tested=$(gh run list --workflow ci.yml --branch main --status success --limit 1 --json headSha --jq '.[0].headSha // empty')
[[ -n "$tested" ]] || fail "CI has not succeeded on main"
changed=$(git diff --name-only "$tested" HEAD -- \
  kignore conformance/fixtures conformance/scenarios gradle ':(glob)*.gradle.kts' \
  gradle.properties gradlew gradlew.bat .editorconfig .github/workflows/ci.yml)
[[ -z "$changed" ]] || fail "files changed since the last successful CI run $tested: $changed"

./gradlew --quiet --console=plain spotlessCheck checkKotlinAbi check >&2
rm -rf "$repo"
./gradlew --quiet --console=plain publishToMavenLocal "-Dmaven.repo.local=$repo" >&2
"$(dirname "$0")/check-publication.sh" "$repo" "$version"
