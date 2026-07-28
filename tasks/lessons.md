# Lessons

## E2E verification must assert process identity, not just liveness (2026-07-28)
**What happened:** After fixing library bugs and deleting sample workarounds, an
E2E re-run reported 5/5 PASS — but stale JVMs from an earlier interrupted run
were still holding ports 8091-8093 and served the readiness probes. The "clean"
run had exercised the old, workaround-laden binaries.
**Rule:** Before trusting an E2E result, verify WHICH process served it: check
the PID in the service logs against the run's own `pids` file, confirm ports
were free before start, and confirm the artifact contents (e.g.
`unzip -l app.jar | grep DeletedClass` → 0). A 200 from a readiness endpoint
proves something is listening, not that *your build* is listening.

## Multi-agent builds need a written contract before parallel fan-out (2026-07-28)
Cross-service DTO shapes not pinned in the spec (UnderwritingRequest fields,
verdict type) had to be reconciled after the fact. Pin every wire shape —
field names AND types — in the spec before launching parallel builders.

## Long-running subagents die on transient API errors — design for resume
Agents were killed mid-task twice ("connection closed mid-response") and once
stalled on a watchdog. Their working-tree progress survives. Resume via
SendMessage with a precise state summary; if an agent dies twice on the same
step, finish the step inline instead of resuming a third time.
