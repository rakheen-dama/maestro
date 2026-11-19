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
# With --check-release <version> it also verifies the release gate for a tag
# about to be published (RELEASING.md, "The release gate") and exits non-zero
# naming what is missing: the release before <version>, taken from the
# published GitHub releases, must be the newest previousReleases row, and that
# release must have its upgrade fixtures. Needs `gh` with a token (GH_TOKEN).
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

# The release before <version>: the highest released tag below it under
# semantic-version precedence (a pre-release sorts before its release:
# 0.3.0-rc.1 < 0.3.0-rc.2 < 0.3.0). A tag counts as released only when it has a
# published GitHub release, which both release workflows create as their last
# step, after every module uploaded: a tag whose publication failed part-way
# (RELEASING.md, "Publishing one") has none and is skipped, so the next suffix
# is checked against the last release that did publish. Experimental tags
# (-exp.) are never a previous release. Listing the releases needs `gh` and a
# token (GH_TOKEN; in CI the job's own, contents: read); if it fails, the gate
# fails. MAESTRO_RELEASE_TAGS (whitespace-separated tags) replaces the listing,
# for checking the gate without creating tags or releases.
released_tags() {
    if [[ -n "${MAESTRO_RELEASE_TAGS+set}" ]]; then
        echo "$MAESTRO_RELEASE_TAGS"
        return
    fi
    gh release list --limit 1000 --exclude-drafts --json tagName --jq '.[].tagName'
}

previous_tag_release() {
    local release="$1" tags
    tags="$(released_tags)" || die "release gate: cannot list the published GitHub releases (gh release list); set GH_TOKEN"
    python3 -I - "$release" $tags <<'PY'
import re, sys
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$")
def key(version):
    m = SEMVER.match(version)
    if not m:
        return None
    core = tuple(int(x) for x in m.group(1, 2, 3))
    pre = m.group(4)
    if pre is None:
        return (core, 1, ())
    ids = tuple((0, int(i), "") if i.isdigit() else (1, 0, i) for i in pre.split("."))
    return (core, 0, ids)
release = sys.argv[1]
release_key = key(release)
if release_key is None:
    sys.exit("not a version: " + release)
below = []
for tag in sys.argv[2:]:
    version = tag[1:] if tag.startswith("v") else tag
    k = key(version)
    if k is None or "-exp." in version or version == release or k >= release_key:
        continue
    below.append((k, version))
print(max(below)[1] if below else "")
PY
}

if [[ "${1:-}" == "--check-release" ]]; then
    release="${2:-}"
    [[ -n "$release" ]] || die "usage: $0 --check-release <version being released>"
    [[ "$release" != "$maestro_version" ]] \
        || die "$release is already the newest previous release in $ROWS_FILE; a release is checked against the one before it"
    previous="$(previous_tag_release "$release")" || die "cannot order the release tags"
    if [[ -z "$previous" ]]; then
        # No earlier release tag: the first release has nothing to be compatible
        # with. Only an explicit MAESTRO_FIRST_RELEASE=true lets it through
        # (RELEASING.md, "The release gate"); a checkout without tags fails here.
        [[ "${MAESTRO_FIRST_RELEASE:-}" == "true" ]] \
            || die "release gate: no published release below v$release; for the first release only, set MAESTRO_FIRST_RELEASE=true"
        echo "previous-release.sh: release gate for $release: first release (MAESTRO_FIRST_RELEASE=true), nothing to check" >&2
    else
        [[ "$previous" == "$maestro_version" ]] \
            || die "release gate: the release before $release is $previous (tag v$previous), but the newest row of previousReleases in $ROWS_FILE is $maestro_version; add the row for $previous and capture its fixtures (RELEASING.md, Upgrade fixtures)"
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
        echo "previous-release.sh: release gate for $release: previous release $previous (published) is the newest previousReleases row and has its fixtures ($name)" >&2
    fi
fi

echo "maestro-version=$maestro_version"
echo "spring-boot-version=$spring_boot_version"
