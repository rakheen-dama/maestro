#!/usr/bin/env bash
#
# previous-release.sh — prints the newest previous release the upgrade checks
# run against, as GitHub Actions step outputs:
#
#   maestro-version=<version>
#   spring-boot-version=<the Spring Boot version it was built with>
#
# The single source is the last row of previousReleases in
# maestro-integration-tests/rolling-node/build.gradle.kts (RELEASING.md,
# "The rolling test against previous releases"): the rolling test, the
# consumer smoke test and the API compatibility report all use that row.
#
# With --check-release <version> it instead verifies the release gate for a
# tag about to be published (RELEASING.md, "The release gate") and exits
# non-zero naming what is missing.
set -euo pipefail

ROWS_FILE="maestro-integration-tests/rolling-node/build.gradle.kts"
ROW_RE='PreviousRelease\(maestroVersion = "([^"]+)", springBootVersion = "([^"]+)"\)'

die() {
    echo "previous-release.sh: $*" >&2
    exit 1
}

[[ -f "$ROWS_FILE" ]] || die "no $ROWS_FILE (run from the repository root)"
rows="$(grep -oE "$ROW_RE" "$ROWS_FILE" || true)"
[[ -n "$rows" ]] || die "no PreviousRelease row in $ROWS_FILE"
last="$(tail -n 1 <<<"$rows")"
[[ "$last" =~ $ROW_RE ]] || die "cannot parse '$last'"
maestro_version="${BASH_REMATCH[1]}"
spring_boot_version="${BASH_REMATCH[2]}"

if [[ "${1:-}" == "--check-release" ]]; then
    release="${2:-}"
    [[ -n "$release" ]] || die "usage: $0 --check-release <version being released>"
    [[ "$release" != "$maestro_version" ]] \
        || die "$release is already the newest previous release in $ROWS_FILE; a release is checked against the one before it"
    # The fixture of the release being published is captured after its tag
    # exists; what must exist before it is the fixture of the previous release.
    name="v${maestro_version//[.-]/_}"
    for fixture in "maestro-store-postgres/src/test/resources/upgrade/$name/SOURCE" \
                   "maestro-integration-tests/src/test/resources/upgrade/$name/seeded-history.sql" \
                   "maestro-integration-tests/src/test/resources/upgrade/$name/capture.properties"; do
        [[ -f "$fixture" ]] || die "release gate: the previous release $maestro_version has no fixture $fixture (RELEASING.md, Upgrade fixtures)"
    done
    # Its SeededFixture implementation names it (name() returns "<name>").
    grep -qF "return \"$name\";" maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/*.java \
        || die "release gate: no SeededFixture implementation named $name (RELEASING.md, Upgrade fixtures)"
    echo "previous-release.sh: release gate for $release: previous release $maestro_version has its fixtures ($name)" >&2
fi

echo "maestro-version=$maestro_version"
echo "spring-boot-version=$spring_boot_version"
