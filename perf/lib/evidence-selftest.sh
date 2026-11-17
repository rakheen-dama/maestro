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

for f in "${files[@]}"; do
  name=${f##*/}
  problems=$(jq -r '
    def host_keys: ["uname", "cpus", "memoryMb", "dockerServer", "dockerCompose", "dockerMemoryMb"];
    [ (paths(type == "string") as $p | getpath($p) as $v
       | select(($v | startswith("/")) or ($v | test("/Users/|/home/")))
       | "string at \($p | map(tostring) | join(".")) is a path: \($v[0:40])"),
      (.identity // empty
       | (if has("pwd") then "identity.pwd is present" else empty end),
         (.host // {} | keys[] | select(. as $k | host_keys | index($k) | not) | "identity.host.\(.) is not a field run.sh writes"),
         (.host.uname // null | select(. != null and (test("^[^ ]+ [^ ]+ [^ ]+$") | not)) | "identity.host.uname is not three words (uname -srm): \(.)"),
         ((.nodes // []) + (.nodesAfter // []) | .[] | .hostname // null
          | select(. != null and (test("^[0-9a-f]{12}$") | not)) | "a node hostname is not a container id: \(.)"))
    ] | .[]' "$f" 2>&1) || problems="not readable as JSON: ${problems}"
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

if ((failures > 0)); then
  echo "evidence-selftest: ${failures} problem(s)"
  exit 1
fi
echo "evidence-selftest: no personal detail in committed evidence or baselines"
