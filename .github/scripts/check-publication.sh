#!/usr/bin/env bash
# Checks a Maven repository produced by publishToMavenLocal before it is uploaded.
# Usage: check-publication.sh <repository> <version>
# Prints a digest of every artifact to stdout so that two builds can be compared.
set -euo pipefail

repo=$1
version=$2
group_dir="$repo/dev/cuervolu"

publications=(kignore kignore-jvm kignore-js kignore-wasm-js kignore-linuxx64 kignore-macosarm64 kignore-mingwx64)

fail() {
  echo "error: $*" >&2
  exit 1
}

for name in "${publications[@]}"; do
  dir="$group_dir/$name/$version"
  for ext in pom module; do
    [[ -f "$dir/$name-$version.$ext" ]] || fail "missing $name-$version.$ext"
  done
  [[ -f "$dir/$name-$version-sources.jar" ]] || fail "missing $name-$version-sources.jar"
  [[ -f "$dir/$name-$version-javadoc.jar" ]] || fail "missing $name-$version-javadoc.jar"
done

[[ -f "$group_dir/kignore/$version/kignore-$version-kotlin-tooling-metadata.json" ]] ||
  fail "missing kotlin-tooling-metadata.json, which klibs.io requires"
for name in kignore-js kignore-wasm-js kignore-linuxx64 kignore-macosarm64 kignore-mingwx64; do
  [[ -f "$group_dir/$name/$version/$name-$version.klib" ]] || fail "missing $name klib"
done
[[ -f "$group_dir/kignore-jvm/$version/kignore-jvm-$version.jar" ]] || fail "missing the jvm jar"

# Every file that a module file points to must exist, so that Gradle can resolve each variant.
for module in "$group_dir"/*/"$version"/*.module; do
  module_dir=$(dirname "$module")
  while IFS= read -r url; do
    [[ -f "$module_dir/$url" ]] || fail "$(basename "$module") references $url, which does not exist"
  done < <(grep -oE '"url": *"[^"]+"' "$module" | sed -E 's/"url": *"([^"]+)"/\1/')
  grep -q "\"version\": \"$version\"" "$module" || fail "$(basename "$module") does not mention $version"
done

# Fuzzing, benchmark and conformance tooling must not leak into the library.
for name in "${publications[@]}"; do
  for archive in "$group_dir/$name/$version"/*.jar "$group_dir/$name/$version"/*.klib; do
    [[ -e "$archive" ]] || continue
    if unzip -l "$archive" | grep -qiE 'benchmark|conformance|jmh'; then
      fail "$(basename "$archive") contains development tooling"
    fi
  done
done

# The only dependency of any publication is the Kotlin standard library.
if grep -hE '<groupId>' "$group_dir"/*/"$version"/*.pom | grep -vE 'dev.cuervolu|org.jetbrains.kotlin' >/dev/null; then
  fail "a POM declares a dependency other than the Kotlin standard library"
fi
grep -q "<license>" "$group_dir/kignore/$version/kignore-$version.pom" || fail "the POM has no license"
grep -q "https://github.com/cuervolu/kignore" "$group_dir/kignore/$version/kignore-$version.pom" || fail "the POM has no project URL"

(cd "$repo" && find dev -type f ! -name 'maven-metadata-local.xml' ! -name '*.md5' ! -name '*.sha*' -print0 | sort -z | xargs -0 shasum -a 256)
