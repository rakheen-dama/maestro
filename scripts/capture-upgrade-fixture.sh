#!/usr/bin/env bash
#
# capture-upgrade-fixture.sh — the only producer of Maestro's frozen upgrade
# fixtures. Every artefact is taken from a git ref, never from the working tree.
#
# Usage (from the repository root):
#   scripts/capture-upgrade-fixture.sh migrations <ref> <name>
#   scripts/capture-upgrade-fixture.sh history <ref> <name>
#
# migrations  Writes maestro-store-postgres/src/test/resources/upgrade/<name>/
#             <component>/<file> for every
#             */src/main/resources/db/maestro/(store|lock|messaging)/V<n>__*.sql
#             at <ref>, byte for byte as git stores it, plus SOURCE (Java
#             Properties: ref, capture time, SHA-256 of every file's git blob).
#
# history     Writes maestro-integration-tests/src/test/resources/upgrade/<name>/
#             {seeded-history.sql,capture.properties}: a pg_dump of the history
#             that <ref>'s own engine and migrations wrote. It checks <ref> out
#             as a temporary detached worktree, copies the frozen fixture
#             package io.b2mash.maestro.integration.upgrade.<name> (fixture
#             workflows and the SeededHistoryCapture driver) from this checkout
#             into it, runs that driver there with a cold Gradle build, and
#             copies its two outputs here. The temporary worktree is removed on
#             every exit path (success, failure, INT/TERM/HUP).
#
# Regeneration rule: a fixture records what <ref> shipped. It is never edited
# and never regenerated at a later commit — that would turn it into "what the
# newer build ships" and the guard would pass against the very regression it
# exists to catch. The script therefore refuses to write into an existing
# fixture directory. Re-capturing needs the maintainer's explicit approval, is
# run at the same ref, and goes in its own commit (RELEASING.md § Upgrade
# fixtures). A later release adds a NEW fixture under a new name.
#
# history mode builds with --no-daemon --no-build-cache: every task of the
# capture build runs in the fresh worktree, nothing is restored from a cache.
#
# The script writes nothing on failure and commits nothing.
set -euo pipefail

SCRIPT="scripts/capture-upgrade-fixture.sh"
MIGRATIONS_ROOT="maestro-store-postgres/src/test/resources/upgrade"
HISTORY_ROOT="maestro-integration-tests/src/test/resources/upgrade"
HISTORY_SOURCES="maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade"
MIGRATION_PATH_RE='^.+/src/main/resources/db/maestro/(store|lock|messaging)/V[0-9]+__[^/]*\.sql$'
# A fixture name is one plain path segment, so <name> can never point outside
# the fixture root (no '/', no '..', no hidden directory).
NAME_RE='^[A-Za-z0-9._-]+$'
# A history fixture name is also a Java package segment.
HISTORY_NAME_RE='^[a-z][a-z0-9_]*$'
SHA256_RE='^[0-9a-f]{64}$'

die() {
    echo "$SCRIPT: $*" >&2
    exit 1
}

usage() {
    echo "usage: $SCRIPT migrations <ref> <name>" >&2
    echo "       $SCRIPT history <ref> <name>" >&2
    exit 2
}

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

# Version number of a migration file name: V<n>__… -> n.
version_of() {
    local base="${1##*/}"
    base="${base#V}"
    echo "${base%%__*}"
}

capture_migrations() {
    local ref="$1" name="$2"
    [[ "$name" =~ $NAME_RE && "$name" != .* ]] \
        || die "invalid fixture name '$name': use only [A-Za-z0-9._-], not starting with '.'"
    local sha
    sha="$(git rev-parse --verify --quiet "${ref}^{commit}")" || die "ref '$ref' does not resolve to a commit"

    local target="$MIGRATIONS_ROOT/$name"
    [[ ! -e "$target" ]] || die "$target already exists; frozen fixtures are never regenerated (see the header of $SCRIPT)"

    local paths
    paths="$(git ls-tree -r --name-only "$sha" | grep -E "$MIGRATION_PATH_RE" || true)"
    [[ -n "$paths" ]] || die "no Maestro migrations found at $sha"

    # Sorted by version number, so SOURCE reads in the order Flyway resolves them.
    paths="$(while IFS= read -r p; do printf '%s\t%s\n' "$(version_of "$p")" "$p"; done <<<"$paths" \
        | sort -n -k1,1 | cut -f2)"

    # Stage inside the fixture root, so the final mv is a rename on one
    # filesystem: the fixture appears whole or not at all. The staging
    # directory is removed on every failure path (die, a failing command
    # under set -e, or an interrupt).
    # The traps go in before mktemp, so no signal can leave a staging
    # directory behind; STAGING is global so the EXIT trap sees it.
    mkdir -p "$MIGRATIONS_ROOT"
    STAGING=""
    trap 'if [[ -n "$STAGING" ]]; then rm -rf -- "$STAGING"; fi' EXIT
    trap 'exit 130' INT TERM HUP
    STAGING="$(mktemp -d "$MIGRATIONS_ROOT/.capture.XXXXXX")"
    local staging="$STAGING"
    # mktemp -d creates 0700; give the fixture the mode mkdir would.
    chmod "$(printf '%o' $((0777 & ~0$(umask))))" "$staging"

    {
        echo "# Frozen Maestro migrations, produced by $SCRIPT migrations $ref $name."
        echo "# Every migration Maestro shipped at the ref below, byte for byte, and the"
        echo "# SHA-256 of each file's git blob. Never edit or regenerate this directory;"
        echo "# a later release adds a new fixture (RELEASING.md § Upgrade fixtures)."
        echo "ref=$sha"
        echo "captured=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } >"$staging/SOURCE"

    local path component file hash
    while IFS= read -r path; do
        component="$(basename "$(dirname "$path")")"
        file="$(basename "$path")"
        mkdir -p "$staging/$component"
        [[ ! -e "$staging/$component/$file" ]] || die "two modules ship $component/$file at $sha"
        git cat-file blob "$sha:$path" >"$staging/$component/$file"
        # Hash into a variable first: inside "$(...)" in an echo argument a
        # failing hash would not trip set -e and SOURCE would record garbage.
        hash="$(sha256 "$staging/$component/$file")" || die "could not hash $component/$file"
        [[ "$hash" =~ $SHA256_RE ]] || die "could not hash $component/$file (got '$hash')"
        echo "$component/$file=$hash" >>"$staging/SOURCE"
    done <<<"$paths"

    # Still racy: a directory created between this check and the mv would get
    # the staging directory moved inside it. mv -T would close that, but it is
    # GNU-only and this script must also run on macOS (BSD mv).
    [[ ! -e "$target" ]] || die "$target appeared during the capture; refusing to overwrite it"
    mv "$staging" "$target"
    STAGING=""
    trap - EXIT INT TERM HUP
    echo "$SCRIPT: wrote $target ($(grep -c '^[a-z]*/V' "$target/SOURCE") migrations at $sha)"
}

# ── history mode ─────────────────────────────────────────────────────────
# Globals so the EXIT trap sees them.
STAGING=""
CAPTURE_DIR=""
CAPTURE_WT=""
GRADLE_PID=""

cleanup_history() {
    local status=$?
    if [[ -n "$GRADLE_PID" ]] && kill -0 "$GRADLE_PID" 2>/dev/null; then
        echo "$SCRIPT: stopping the capture build ($GRADLE_PID)" >&2
        kill -TERM "$GRADLE_PID" 2>/dev/null || true
        wait "$GRADLE_PID" 2>/dev/null || true
    fi
    if [[ -n "$STAGING" ]]; then
        rm -rf -- "$STAGING"
    fi
    # Removed by path: only this capture's worktree entry is touched, never
    # another stale entry of the repository (so no repo-wide 'git worktree prune').
    if [[ -n "$CAPTURE_WT" ]]; then
        git worktree remove --force "$CAPTURE_WT" >/dev/null 2>&1 \
            || echo "$SCRIPT: WARNING: could not remove worktree $CAPTURE_WT;" \
                "remove it with: git worktree remove --force '$CAPTURE_WT'" >&2
    fi
    if [[ -n "$CAPTURE_DIR" ]]; then
        rm -rf -- "$CAPTURE_DIR"
    fi
    return "$status"
}

capture_history() {
    local ref="$1" name="$2"
    [[ "$name" =~ $HISTORY_NAME_RE ]] \
        || die "invalid history fixture name '$name': it is a Java package segment, use [a-z][a-z0-9_]*"
    local sha
    sha="$(git rev-parse --verify --quiet "${ref}^{commit}")" || die "ref '$ref' does not resolve to a commit"

    local target="$HISTORY_ROOT/$name"
    [[ ! -e "$target" ]] || die "$target already exists; frozen fixtures are never regenerated (see the header of $SCRIPT)"
    local sources="$HISTORY_SOURCES/$name"
    [[ -f "$sources/SeededHistoryCapture.java" ]] || die "no capture driver at $sources/SeededHistoryCapture.java"

    # Traps before mktemp, so no signal can leave a worktree or a temporary
    # directory behind. 'exit' from the signal trap runs the EXIT trap.
    trap cleanup_history EXIT
    trap 'exit 130' INT TERM HUP
    CAPTURE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/maestro-capture.XXXXXX")"
    CAPTURE_WT="$CAPTURE_DIR/capture"
    git worktree add --detach "$CAPTURE_WT" "$sha" >&2
    [[ "$(git -C "$CAPTURE_WT" rev-parse HEAD)" == "$sha" ]] || die "capture worktree is not at $sha"
    [[ ! -e "$CAPTURE_WT/$sources" ]] || die "$sha already has $sources; refusing to mix fixture sources"
    mkdir -p "$CAPTURE_WT/$sources"
    cp -R "$sources/." "$CAPTURE_WT/$sources/"
    mkdir "$CAPTURE_DIR/out"

    echo "$SCRIPT: capturing history at $sha in $CAPTURE_WT" >&2
    # In the background with 'wait', so a signal is handled at once instead
    # of after the build finishes; the EXIT trap then stops the build.
    (
        cd "$CAPTURE_WT"
        MAESTRO_UPGRADE_CAPTURE_OUT="$CAPTURE_DIR/out" MAESTRO_UPGRADE_CAPTURE_REF="$sha" \
            exec ./gradlew --no-watch-fs --no-daemon --no-build-cache :maestro-integration-tests:test \
            --tests "io.b2mash.maestro.integration.upgrade.$name.SeededHistoryCapture" --rerun
    ) &
    GRADLE_PID=$!
    local rc=0
    wait "$GRADLE_PID" || rc=$?
    GRADLE_PID=""
    [[ "$rc" -eq 0 ]] || die "the capture build at $sha failed (exit $rc)"

    local file
    for file in seeded-history.sql capture.properties; do
        [[ -s "$CAPTURE_DIR/out/$file" ]] || die "the capture wrote no $file (was SeededHistoryCapture skipped?)"
    done
    grep -qx "ref=$sha" "$CAPTURE_DIR/out/capture.properties" \
        || die "capture.properties does not record ref=$sha"

    # Stage inside the fixture root, as migrations mode does, so the final mv
    # is a rename on one filesystem.
    mkdir -p "$HISTORY_ROOT"
    STAGING="$(mktemp -d "$HISTORY_ROOT/.capture.XXXXXX")"
    chmod "$(printf '%o' $((0777 & ~0$(umask))))" "$STAGING"
    cp "$CAPTURE_DIR/out/seeded-history.sql" "$CAPTURE_DIR/out/capture.properties" "$STAGING/"

    local hashes="" hash
    for file in seeded-history.sql capture.properties; do
        hash="$(sha256 "$STAGING/$file")" || die "could not hash $file"
        [[ "$hash" =~ $SHA256_RE ]] || die "could not hash $file (got '$hash')"
        hashes+="  $hash  $target/$file"$'\n'
    done

    [[ ! -e "$target" ]] || die "$target appeared during the capture; refusing to overwrite it"
    mv "$STAGING" "$target"
    STAGING=""
    echo "$SCRIPT: wrote $target (history at $sha)"
    printf 'SHA-256:\n%s' "$hashes"
}

main() {
    [[ $# -ge 1 ]] || usage
    local toplevel
    toplevel="$(git rev-parse --show-toplevel 2>/dev/null)" || die "not inside a git repository"
    [[ "$(pwd -P)" == "$(cd "$toplevel" && pwd -P)" ]] || die "run from the repository root ($toplevel)"

    case "$1" in
        migrations)
            [[ $# -eq 3 ]] || usage
            capture_migrations "$2" "$3"
            ;;
        history)
            [[ $# -eq 3 ]] || usage
            capture_history "$2" "$3"
            ;;
        *)
            usage
            ;;
    esac
}

main "$@"
