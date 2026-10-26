#!/usr/bin/env bash
# Verifies that the checked out commit can be released as the requested version, builds the
# publications in a temporary repository and prints the digest of their files.
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

declared=$(./gradlew --quiet --console=plain :kignore:properties | sed -n 's/^version: //p')
[[ "$declared" == "$version" ]] || fail "Gradle declares version $declared, not $version"

passed=$(gh run list --workflow ci.yml --commit "$GITHUB_SHA" --status success --json conclusion --jq length)
[[ "$passed" -ge 1 ]] || fail "CI has not succeeded for $GITHUB_SHA"

./gradlew --quiet --console=plain spotlessCheck checkKotlinAbi check >&2
rm -rf "$repo"
./gradlew --quiet --console=plain publishToMavenLocal "-Dmaven.repo.local=$repo" >&2
"$(dirname "$0")/check-publication.sh" "$repo" "$version"
