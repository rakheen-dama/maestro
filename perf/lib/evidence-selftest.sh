#!/usr/bin/env bash
# Evidence privacy self-test: perf/lib/evidence-selftest.sh [<json file>...]
#
# Committed run evidence (perf/.evidence/**/*.json) and baselines
# (perf/baselines/*.json) must carry no personal detail of the machine that
# produced them. With no argument it checks every such file in the tree; with
# arguments, those files. For each file:
#   - no string value anywhere starts with "/" (an absolute path: a home or
#     working directory) or contains "/Users/" or "/home/";
#   - a result's identity has no pwd, its host record only the fields
#     run.sh writes (uname, cpus, memoryMb, dockerServer, dockerCompose,
#     dockerMemoryMb: no hostname or node name), and host.uname is exactly
#     three words (`uname -srm`: system, release, machine; `uname -a` carries
#     the node name);
#   - the only hostnames are the node containers' (identity.nodes[] and
#     .nodesAfter[]: 12 hex characters, Docker's container id), which
#     validity.identity needs to tell replicas apart.
# And perf/run.sh itself still writes identity.json that way (uname -srm, no
# pwd). No Docker. Exit 0 when every check holds, 1 otherwise.
# SC2016: the single-quoted '$x' strings are jq filters, expanded by jq.
# shellcheck disable=SC2016
set -Eeuo pipefail

PERF_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
failures=0
fail() {
  printf 'FAIL  %s\n' "$1"
  failures=$((failures + 1))
}

files=("$@")
if ((${#files[@]} == 0)); then
  while IFS= read -r f; do files+=("${PERF_DIR}/../${f}"); done \
    < <(git -C "${PERF_DIR}/.." ls-files -- 'perf/.evidence/**/*.json' 'perf/.evidence/*/*.json' 'perf/baselines/*.json' | sort -u)
fi
((${#files[@]} > 0)) || { echo "evidence-selftest: no file to check"; exit 1; }

# problems_of <json file>: one line per personal detail found (nothing when
# clean); "not readable as JSON" when jq cannot read it.
problems_of() {
  jq -r '
    def host_keys: ["uname", "cpus", "memoryMb", "dockerServer", "dockerCompose", "dockerMemoryMb"];
    def container_id: type == "string" and test("^[0-9a-f]{12}$");
    [ (paths(type == "string") as $p | getpath($p) as $v
       | select(($v | startswith("/")) or ($v | test("/Users/|/home/")))
       | "string at \($p | map(tostring) | join(".")) is a path: \($v[0:40])"),
      (.identity // empty
       | (if has("pwd") then "identity.pwd is present" else empty end),
         (.host // {} | keys[] | select(. as $k | host_keys | index($k) | not) | "identity.host.\(.) is not a field run.sh writes"),
         (.host.uname // null | select(. != null and (test("^[^ ]+ [^ ]+ [^ ]+$") | not)) | "identity.host.uname is not three words (uname -srm): \(.)")),
      # Any key named like a host or node name, anywhere (identity.nodes[],
      # .nodesAfter[] and the check values that copy them): its value must be
      # a node container id (12 hex), or a list of them.
      (paths as $p | select(($p[-1] | type) == "string" and ($p[-1] | test("^(host|node)_?names?$"; "i")))
       | getpath($p) as $v
       | select((($v | container_id) or (($v | type) == "array" and ($v | all(container_id)))) | not)
       | "\($p | map(tostring) | join(".")) is not a node container id: \($v | tojson | .[0:40])")
    ] | .[]' "$1" 2>&1 || echo "not readable as JSON"
}

for f in "${files[@]}"; do
  name=${f##*/}
  problems=$(problems_of "$f")
  if [[ -n "$problems" ]]; then
    while IFS= read -r p; do fail "${name}: ${p}"; done <<<"$problems"
  fi
done
printf 'checked %s file(s)\n' "${#files[@]}"

# run.sh writes identity.json with uname -srm and no pwd.
grep -q -- '--arg uname "$(uname -srm)"' "${PERF_DIR}/run.sh" || fail "perf/run.sh does not record host.uname as uname -srm"
if grep -E -- '--arg pwd|pwd: \$pwd|\$\(uname -a\)' "${PERF_DIR}/run.sh" >/dev/null; then
  fail "perf/run.sh records pwd or uname -a in identity.json"
fi

# The check itself: each negative fixture (a clean committed result with one
# personal detail planted) must be caught.
fixture_src=$(git -C "${PERF_DIR}/.." ls-files -- 'perf/.evidence/*/*.result.json' | head -1)
if [[ -n "$fixture_src" ]]; then
  fx=$(mktemp -d "${TMPDIR:-/tmp}/perf-evidence-selftest.XXXXXX")
  n=0
  for plant in '.identity.hostname = "developer-laptop"' '.identity.host.hostname = "developer-laptop"' \
    '.identity.nodes[0].hostname = "developer-laptop"' '.measurements.nodename = "developer-laptop.local"' \
    '.identity.pwd = "/Users/someone/src/maestro"' '.identity.host.uname = "Darwin someones-laptop.local 25.5.0 Darwin Kernel arm64"' \
    '.measurements.note = "ran in /home/someone/maestro"'; do
    n=$((n + 1))
    jq -c "$plant" "${PERF_DIR}/../${fixture_src}" >"${fx}/${n}.json"
    if [[ -z "$(problems_of "${fx}/${n}.json")" ]]; then
      fail "negative fixture not caught: ${plant}"
    fi
  done
  rm -rf "$fx"
  printf 'negative fixtures: %s planted details, each caught unless reported above\n' "$n"
fi

if ((failures > 0)); then
  echo "evidence-selftest: ${failures} problem(s)"
  exit 1
fi
echo "evidence-selftest: no personal detail in committed evidence or baselines"
