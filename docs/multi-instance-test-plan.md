# Multi-Instance Verification Plan

**Status: COMPLETE as of 2026-08-01.** All
three phases below shipped: Phase 1 (Tasks A-E, real multi-process E2E
scenarios on both lock backends), Phase 2 (the chaos/soak harness, which
found and fixed three further engine defects, Issues 18, 19, and 20), and
Phase 3 (the docs this plan asked for). Results:

- `docs/operations.md` — the measured deployment guarantees this plan's
  Phase 3 asked for, with every number traceable to an evidence file.
- `docs/open-issues.md` Issues 11 and 12 — the measured evidence base and
  benchmark this plan's Phase 3 asked for: PR-gate data plus the 120-minute
  soak run of record (`20260801-214325--6973268155056049009`: 2,376
  workflows, 0 duplicate side effects, and the vs-node-count benchmark
  tail), filled into both sections.
- `docs/open-issues.md` Issues 17, 18, 19, 20 — four real `maestro-core`
  defects found and fixed along the way, all via the library-bug protocol
  this plan's §6 mandated.
- The stale "`e2eTest` matches nothing" note this plan's §4 flagged for
  removal is gone — `e2eTest` now runs the chaos/soak harness.

This document is kept as the historical record of what was planned and why;
read `docs/operations.md` and `docs/open-issues.md` for the current state.
The rest of this file (below) is unchanged from when it was written.

---

**Goal:** prove Maestro works when the microservices embedding it run as
multiple instances — real processes, real failures between them — and produce
the measurements Issues 11 and 12 have been waiting for.

**Audience:** the coordinator agent executing this plan (see
`tasks/multi-instance-coordinator-prompt.md`) and its engineering team. This
document is the spec; convert it into an SDD-format task plan before
executing.

**Status date:** 2026-07-30, after PR #29 (issues 13–15 closed).

---

## 1. What already exists — do not rebuild it

**Logical multi-node, single JVM** — `maestro-integration-tests`
`multinode/` package (4 suites, ~12 tests): two `MaestroEngineHarness`
engine instances contend over one real Postgres + Kafka. Covers lock
contention, owner-death → TTL → adoption, cross-node signal routing, and the
no-lock-backend characterization (duplicate execution is real; activities
must be idempotent). Limitation: both "nodes" share one JVM — no independent
crashes, GC, consumer-group rebalance, or clocks.

**Real two-process coverage** — loan-origination E2E scenario 6
(`maestro-samples/sample-loan-origination/e2e/run-e2e.sh`,
`scenario_two_node`): two JVMs of loan-application-service
(`loan-application-service` + `loan-application-service-b`, distinct PIDs
asserted), workflow created on node A, driven entirely through node B, both
nodes assert the same terminal state. Happy-path only; one service; no
failure injected between nodes.

**Engine features built for multi-node** (all currently proven only at the
levels above): instance locks with TTL + renewal (Valkey and Postgres
backends), recovery-poller adoption of ownerless RUNNING workflows,
timer-poller leader election, consumer-group signal routing, node-agnostic
admin terminate (optimistic-version CAS), terminal-state resurrection
guards, graceful shutdown leaving parked workflows `WAITING_*` and
recoverable.

**Relevant fixtures/discipline:** the E2E script's pid-file +
process-identity checks (see `tasks/lessons.md` — binding), Testcontainers
singleton patterns in the integration suites, `KafkaAckOnFailureIT`-style
tight-redelivery test properties.

## 2. Known limitations that shape assertions (not bugs to fix here)

- **Issue 11 (no fencing):** a node that loses its instance lock keeps
  running; duplicate *persisted results* are prevented by the unique event
  index, duplicate *side effects* are not. Multi-node tests must therefore
  assert store-level correctness and **count** side-effect executions
  (tolerating documented duplicates), never assume exactly-once effects.
  Producing hard numbers for duplicate-effect frequency under chaos is a
  deliverable, not a failure.
- **Issue 12 (recovery polling scale):** every node re-reads the whole
  active set each poll. The soak harness must record recovery-query and
  lock-probe rates vs node count and parked-workflow count — this is the
  benchmark Issue 12 explicitly requires before anyone optimises.
- **Issue 16:** compensated-saga retry is guarded off
  (`COMPENSATED_NOT_RETRYABLE`); chaos scenarios must not expect retry to
  re-drive compensated workflows.

## 3. Phase 1 — extend the loan E2E to real multi-instance scenarios

All scenarios follow the existing script's conventions: ports verified free
before start, PIDs asserted against the run's own pid files, deployed-jar
class checks for branch identity, generous Awaitility-style polling (the
script's `wait_for_*` helpers), never sleep-as-synchronisation.

**Task A — scale-out plumbing.** Every service (loan-application, payment,
underwriting) startable ×2 with distinct ports/pid files, env-driven, reusing
the existing `start_loan_node_b` pattern. Keep single-instance scenarios
unchanged. Done when: a "cluster mode" flag brings up 6 service processes and
scenario 6 still passes unmodified.

**Task B — owner-kill → peer adoption.** Start a workflow that parks
mid-flight on node A (e.g. awaiting a signal or a timer); `kill -9` node A
(the owner — prove ownership first via logs/lock inspection); do NOT restart
it; assert node B adopts within one recovery-poll interval + lock TTL and
completes the workflow; assert the event log has no gaps or duplicate
sequences and side-effect counters show at most the documented duplication.
This is the claim scenario 5 (same-node restart) does not make. Done when:
the scenario passes 3 consecutive runs on both lock backends (Task E).

**Task C — rolling restart.** With ≥3 workflows in-flight across states
(running activity, parked on signal, parked on timer), gracefully stop node
A (SIGTERM, real Spring shutdown); assert no workflow is FAILED or
compensated by the deploy (the Issue 4/5 semantics, cross-process), node B
serves reads/signals throughout, and after node A restarts everything
completes. Done when: zero FAILED, zero compensations, all COMPLETED.

**Task D — leader failover + cross-node admin.** (1) Identify the
timer-poller leader (logs/lock key); kill it; assert a due timer still fires
via the new leader within the leader TTL + poll interval. (2) Drive a
workflow to FAILED whose owner was node A; POST the dashboard retry/terminate
while only node B is alive; assert the command executes (node-agnostic CAS)
and `WORKFLOW_RETRIED`/`WORKFLOW_TERMINATED` reach the admin DB. Done when:
both pass 3 consecutive runs.

**Task E — lock-backend matrix.** Tasks B–D run against both `lock-valkey`
and `lock-postgres` profiles (adoption timing rides on TTL semantics; the two
backends age locks differently). Done when: the multi-node scenario set is
green on both, with per-backend timing notes recorded.

## 4. Phase 2 — chaos/soak harness (architect designs first)

An architect agent produces a design doc (coordinator-approved before
implementation) covering:

- **Workload generator:** N workflows/minute mixing the loan sample's paths
  (happy, conditions-loop, saga-withdrawal, signal-timeout), parameterised
  duration (10 min PR-gate mode / hours-long nightly soak mode).
- **Chaos controller:** randomised, seeded (reproducible) actions against the
  cluster — `kill -9`, SIGSTOP/SIGCONT (GC-pause simulation — this is the
  Issue 11 split-brain trigger), `docker network disconnect/connect`
  (broker/store partitions), rolling restarts. Action log with timestamps.
- **Invariant checker:** runs against the store during and after: every
  started workflow terminal within SLA after chaos stops; no workflow stuck
  `WAITING_*` with no pending timer/signal; event logs dense per instance
  (no sequence gaps below the terminal event) and duplicate-free; zero
  unconsumed application signals for completed workflows; zero `$maestro:%`
  rows; side-effect duplicate counters reported (tolerated, counted).
- **Metrics capture for Issue 12:** recovery-query rate, lock probes/renewals
  per node, wake-subscription churn — vs node count and parked count.
- **Packaging:** JUnit suites tagged `@Tag("e2e")` in
  `maestro-integration-tests` (finally making the vacuous `e2eTest` Gradle
  task real — remove the stale note in `docs/open-issues.md` §3 when done),
  Testcontainers-orchestrated or compose-driven per the architect's call;
  wired into the nightly CI workflow alongside the loan E2E.

Done when: the 10-minute mode runs green 3 consecutive times locally and in
CI nightly; the soak mode has produced at least one multi-hour clean run;
invariant violations fail the run with actionable dumps (instance row + full
event log + chaos action log).

## 5. Phase 3 — findings and evidence (docs)

- Append the Issue 12 benchmark numbers to `docs/open-issues.md` §Issue 12
  (it explicitly asks for numbers before any fix).
- Append measured duplicate-side-effect data to §Issue 11 as the evidence
  base for the fencing decision.
- Document the multi-instance deployment guarantees and their measured
  bounds (adoption latency, terminate convergence, deploy safety) in
  `docs/maestro-architecture.md` or a new `docs/operations.md` — whichever
  the docs task judges cleaner.
- Any defect found en route: library-bug protocol (failing test in the
  owning module first), fix, and record in `docs/open-issues.md`.

## 6. Global constraints (bind every task)

Everything in the repo `CLAUDE.md` plus, learned the hard way (see
`tasks/lessons.md` — read it before starting):

- E2E evidence must carry process identity (PIDs vs pid files, ports free
  before start, branch classes in deployed jars) AND artifact identity
  (every log embeds `pwd` + `git rev-parse` + timestamp inside the file;
  per-cycle scratch directories; never trust a log by filename + recency).
- Integration suites: 3 consecutive `--rerun-tasks` green before done.
  Awaitility with generous bounds; never `Thread.sleep` as synchronisation
  (chaos actions themselves may sleep; assertions may not).
- A test that passes on first write is suspect — prove it can fail.
- Expect defects: every previously-untested seam in this project has
  yielded real bugs. Budget for fixing what you find via the library-bug
  protocol, not just for writing tests.
- Never `git stash` (shared stack); temporary reverts via
  `git show HEAD:<path>`.
