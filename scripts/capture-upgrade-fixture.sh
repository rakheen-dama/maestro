#!/usr/bin/env bash
#
# capture-upgrade-fixture.sh — the only producer of Maestro's frozen upgrade
# fixtures. Every artefact is taken from a git ref, never from the working tree.
#
# Usage (from the repository root):
#   scripts/capture-upgrade-fixture.sh migrations <ref> <name>
#
# migrations  Writes maestro-store-postgres/src/test/resources/upgrade/<name>/
#             <component>/<file> for every
#             */src/main/resources/db/maestro/(store|lock|messaging)/V<n>__*.sql
#             at <ref>, byte for byte as git stores it, plus SOURCE (Java
#             Properties: ref, capture time, SHA-256 of every file's git blob).
#
# Regeneration rule: a fixture records what <ref> shipped. It is never edited
# and never regenerated at a later commit — that would turn it into "what the
# newer build ships" and the guard would pass against the very regression it
# exists to catch. The script therefore refuses to write into an existing
# fixture directory. Re-capturing needs the maintainer's explicit approval, is
# run at the same ref, and goes in its own commit (RELEASING.md § Upgrade
# fixtures). A later release adds a NEW fixture under a new name.
#
# The script writes nothing on failure and commits nothing.
set -euo pipefail

SCRIPT="scripts/capture-upgrade-fixture.sh"
MIGRATIONS_ROOT="maestro-store-postgres/src/test/resources/upgrade"
MIGRATION_PATH_RE='^.+/src/main/resources/db/maestro/(store|lock|messaging)/V[0-9]+__[^/]*\.sql$'

die() {
    echo "$SCRIPT: $*" >&2
    exit 1
}

usage() {
    echo "usage: $SCRIPT migrations <ref> <name>" >&2
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

    local staging
    staging="$(mktemp -d)"
    # shellcheck disable=SC2064 # expand now: staging is local
    trap "rm -rf '$staging'" EXIT

    {
        echo "# Frozen Maestro migrations, produced by $SCRIPT migrations $ref $name."
        echo "# Every migration Maestro shipped at the ref below, byte for byte, and the"
        echo "# SHA-256 of each file's git blob. Never edit or regenerate this directory;"
        echo "# a later release adds a new fixture (RELEASING.md § Upgrade fixtures)."
        echo "ref=$sha"
        echo "captured=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    } >"$staging/SOURCE"

    local path component file
    while IFS= read -r path; do
        component="$(basename "$(dirname "$path")")"
        file="$(basename "$path")"
        mkdir -p "$staging/$component"
        [[ ! -e "$staging/$component/$file" ]] || die "two modules ship $component/$file at $sha"
        git cat-file blob "$sha:$path" >"$staging/$component/$file"
        echo "$component/$file=$(sha256 "$staging/$component/$file")" >>"$staging/SOURCE"
    done <<<"$paths"

    mkdir -p "$MIGRATIONS_ROOT"
    mv "$staging" "$target"
    trap - EXIT
    echo "$SCRIPT: wrote $target ($(grep -c '^[a-z]*/V' "$target/SOURCE") migrations at $sha)"
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
        *)
            usage
            ;;
    esac
}

main "$@"
