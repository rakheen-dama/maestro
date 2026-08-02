# Multi-Instance Verification — SDD Plan

Converted from `docs/multi-instance-test-plan.md` (the spec). The spec remains
the authority on intent; this plan is the execution cut. Coordinator:
per-task implementer + independent reviewer, fix loops, final whole-branch
review, QA gate.

**Branch:** `worktree-multi-instance-verification`, based on `origin/main`
(`883197f`, contains PR #29).

**SDD workspace:** `.superpowers/sdd/multi-instance/` (briefs, reports,
review packages, design doc, evidence). Ledger:
`.superpowers/sdd/progress.md`.

**Evidence directory (this cycle):**
`.superpowers/sdd/multi-instance/evidence/` — pruned at cycle start; every
log written there must embed `pwd`, `git rev-parse --show-toplevel`, branch,
and timestamp INSIDE the file at write time.

---

## Global Constraints

These bind every task. Copied from spec §6 plus repo `CLAUDE.md`
non-negotiables.

From the spec (learned the hard way — see `tasks/lessons.md`):

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
  protocol (reproduce as a failing test in the owning module first, fix
  that module, then continue — never work around a proven engine bug
  inside a test), not just for writing tests.
- Never `git stash` (shared stack); temporary reverts via
  `git show HEAD:<path>`.

Known limitations that shape assertions (spec §2 — not bugs to fix here):

- **Issue 11 (no fencing):** multi-node tests assert store-level
  correctness and COUNT side-effect executions (tolerating documented
  duplicates); never assume exactly-once side effects. Measuring
  duplicate-effect frequency is a deliverable, not a failure.
- **Issue 12 (recovery polling scale):** the harness must RECORD
  recovery-query and lock-probe rates vs node count and parked count —
  the benchmark Issue 12 requires before anyone optimises.
- **Issue 16:** compensated-saga retry is guarded off
  (`COMPENSATED_NOT_RETRYABLE`); scenarios must not expect retry to
  re-drive compensated workflows.

From repo `CLAUDE.md` (non-negotiables):

- `maestro-core` must NEVER depend on Spring.
- Jackson 3 (`tools.jackson`), never `com.fasterxml.jackson`; `jakarta.*`,
  never `javax.*`; no Lombok; JSpecify `@Nullable` on public APIs;
  exceptions extend `MaestroException` (sole documented exceptions:
  `ExecutorShutdownException`, `WorkflowTerminatedException` extend
  `Error` — check `instanceof Error` before `Exception` at unwrap sites).
- Never `Thread.sleep()` in workflow code — `workflow.sleep()`.
- Never break `(workflow_instance_id, sequence_number)` uniqueness.
- Kafka topics are never auto-created; pre-declared in configuration.
- Optimistic locking convention: the caller builds new state with
  `version = current + 1`; the store CASes against `version - 1`.
- Javadoc + thread-safety notes on public classes. SLF4J with MDC.
- Unit tests in their module; real-backend tests in
  `maestro-integration-tests` (read its `SPEC.md` first) except
  backend-specific suites which live with their backend module.
- Commit incrementally: every coherent green checkpoint, never >30 min
  uncommitted.

E2E script conventions (spec §3, bind Tasks 1–5): ports verified free
before start, PIDs asserted against the run's own pid files, deployed-jar
class checks for branch identity, `wait_for_*`-style polling helpers,
never sleep-as-synchronisation.

---

## Task 1: Scale-out plumbing (spec Phase 1 Task A)

**Goal.** Every loan-origination service startable ×2 with distinct
ports/pid files, env-driven, reusing the existing `start_loan_node_b`
pattern in `maestro-samples/sample-loan-origination/e2e/run-e2e.sh`. A
"cluster mode" flag (`E2E_CLUSTER=1`) brings up 6 service processes
(loan-application ×2, verification-gateway ×2, underwriting ×2 — note: the
spec says "payment" but the loan sample's three services are
loan-application-service, verification-gateway-service,
underwriting-service; that is a spec slip, use the real services). Existing
single-instance scenarios 1–6 must pass unmodified in non-cluster mode.

**Context.** `run-e2e.sh` already has: `start_service`/`stop_service` with
pid files, `start_loan_node_b` (second loan node on 8094 via
`SERVER_PORT`), `wait_for_http`, `wait_for_consumer_group`. Generalise
rather than duplicate: a `start_service_instance <name> <port> <suffix>`
shape that `start_loan_node_b` becomes a caller of (or is replaced by) is
the expected direction. Second instances share service name, consumer
group, store, and lock namespace — only the HTTP port differs. Check for
per-service port config (management ports, etc.) that must also be
distinct.

**Requirements.**
- `E2E_CLUSTER=1 ./e2e/run-e2e.sh` starts 6 processes (distinct PIDs
  asserted, ports verified free beforehand) and runs the existing
  scenarios green.
- Default (no flag) behaviour byte-for-byte equivalent to today: 3
  processes, scenario 6 still starts/stops node B itself and passes
  unmodified.
- Teardown stops all instances, cluster or not, including on trap EXIT.
- Port allocations documented at the top of the script.

**Done when.** Cluster mode brings up 6 processes and the full scenario
set (1–6) passes in both modes; evidence logs (with embedded identity)
in the cycle evidence dir showing one passing run per mode.

## Task 2: Owner-kill → peer adoption (spec Phase 1 Task B)

**Goal.** New scenario: a workflow parks mid-flight on node A (awaiting a
signal — e.g. the underwriting decision, as scenario 5 does); prove node A
owns it (logs/lock inspection); `kill -9` node A; do NOT restart it;
assert node B adopts within one recovery-poll interval + lock TTL and
completes the workflow.

**Requirements.**
- Ownership proof BEFORE the kill: which node started/parked the workflow,
  via service logs (MDC `workflowId`) and/or lock key inspection
  (`maestro:lock:workflow:{workflowId}` in Valkey).
- After the kill: node A stays down; node B alone completes the workflow
  (deliver the decision + signatures through node B).
- Assert the event log for the instance has no sequence gaps below the
  terminal event and no duplicate sequences (query Postgres directly).
- Count side-effect executions across both nodes' logs (disbursement
  pattern, as scenario 6 does); at most the documented Issue 11
  duplication is tolerated — record the observed count in the evidence
  log.
- Record adoption latency (kill timestamp → node B first-progress
  timestamp) in the evidence log.
- New scenario wired into `run-e2e.sh` (cluster mode or its own node-B
  management), runnable standalone-ish via the script's existing scenario
  structure.

**Done when.** Scenario passes 3 consecutive runs on the default (Valkey)
backend with evidence logs; the Task 5 matrix will re-run it on Postgres
locks. Prove-it-can-fail evidence for at least the adoption assertion
(e.g. run with node B also killed → scenario fails as expected, or
equivalent).

## Task 3: Rolling restart (spec Phase 1 Task C)

**Goal.** With ≥3 workflows in-flight across distinct states (one mid
running-activity path, one parked on a signal, one parked on a timer),
gracefully stop node A (SIGTERM, real Spring shutdown); assert no
workflow is FAILED or compensated by the deploy (Issue 4/5 semantics,
cross-process); node B serves reads/signals throughout; after node A
restarts, everything completes.

**Context.** The loan workflow parks on signals (documents, underwriting,
signatures) and has timer waits (signal timeouts). Pick workflow shapes
from the existing sample paths; if no path parks on a pure timer long
enough, a signal-timeout path (awaitSignal with timeout) counts as
parked-on-timer state.

**Requirements.**
- Zero FAILED, zero compensation log lines attributable to the deploy
  window; all three (or more) workflows COMPLETED at the end.
- During node A's downtime, node B answers status reads and ingests at
  least one signal for one of the in-flight workflows.
- Graceful-shutdown evidence: node A's log shows the Spring shutdown and
  Maestro leaving parked workflows `WAITING_*` (not FAILED).
- Event-log density + no-duplicate assertions per instance, as Task 2.

**Done when.** Scenario green 3 consecutive runs (Valkey backend),
evidence logged with identity; wired into `run-e2e.sh`.

## Task 4: Leader failover + cross-node admin (spec Phase 1 Task D)

**Goal.** Two sub-scenarios.

(1) **Timer-poller leader failover:** identify the current timer-poller
leader (logs or `maestro:leader:timer-poller:{service}` key); kill it
(`kill -9`); assert a due timer still fires via the new leader within the
leader TTL + poll interval, and the owning workflow progresses.

(2) **Cross-node admin retry/terminate:** drive a workflow to FAILED whose
owner was node A; with only node B alive, issue the dashboard
retry/terminate command; assert the command executes (node-agnostic CAS)
and `WORKFLOW_RETRIED`/`WORKFLOW_TERMINATED` lifecycle events reach the
admin events topic durably. If the loan sample's compose does not run
`maestro-admin`, either add it to the E2E compose (preferred if cheap) or
publish the `$maestro:retry`/`$maestro:terminate` command exactly as
`AdminCommandService` does and assert the lifecycle events on the
admin-events topic — state which you chose and why in the report.

**Requirements.**
- Leader identity proven before the kill (not assumed from start order).
- For (2): the FAILED workflow's failure must be real (e.g. the
  withdrawal/saga path or an exhausted-retry activity), owner identity
  proven; remember Issue 16 — do not retry a workflow whose saga
  compensated if the assertion expects re-drive; pick a FAILED-without-
  compensation shape for the retry case (terminate may use either).
- Both sub-scenarios pass 3 consecutive runs; evidence with identity.

**Done when.** Both sub-scenarios green 3×, wired into `run-e2e.sh`.

## Task 5: Lock-backend matrix (spec Phase 1 Task E)

**Goal.** The multi-node scenarios from Tasks 2–4 run against BOTH
`maestro-lock-valkey` and `maestro-lock-postgres` profiles. Adoption
timing rides on TTL semantics; the two backends age locks differently.

**Requirements.**
- An env-driven lock-backend switch for the E2E run
  (`E2E_LOCK_BACKEND=valkey|postgres`) that reconfigures the loan services
  (Spring profile or env overrides — the sample must actually honour it;
  verify the effective backend at runtime from logs, don't trust config).
- Tasks 2–4 scenarios green 3 consecutive runs on EACH backend.
- Per-backend timing notes recorded (adoption latency, leader-failover
  latency) in a comparison file in the evidence dir — this feeds Task 8's
  docs.
- CI: the nightly workflow gains the multi-node scenario set on at least
  one backend (both if runtime budget allows — note the decision).

**Done when.** Matrix green 3× per backend with per-backend timing notes;
nightly CI wiring committed.

## Task 6: Chaos/soak harness design doc (spec Phase 2, architect)

**Goal.** A Fable-tier architect produces
`.superpowers/sdd/multi-instance/chaos-harness-design.md` covering ALL
spec §4 required sections:

- **Workload generator:** N workflows/minute mixing the loan sample's
  paths (happy, conditions-loop, saga-withdrawal, signal-timeout);
  parameterised duration (10 min PR-gate mode / hours-long nightly soak).
- **Chaos controller:** randomised, seeded (reproducible) actions —
  `kill -9`, SIGSTOP/SIGCONT (GC-pause simulation, the Issue 11
  split-brain trigger), `docker network disconnect/connect`
  (broker/store partitions), rolling restarts. Timestamped action log.
- **Invariant checker:** during and after: every started workflow terminal
  within SLA after chaos stops; none stuck `WAITING_*` with no pending
  timer/signal; event logs dense per instance below the terminal event and
  duplicate-free; zero unconsumed application signals for completed
  workflows; zero `$maestro:%` rows; side-effect duplicate counters
  reported (tolerated, counted).
- **Metrics capture for Issue 12:** recovery-query rate, lock
  probes/renewals per node, wake-subscription churn — vs node count and
  parked count. Must state HOW these are measured (Postgres
  `pg_stat_statements`? proxy? log parsing? engine instrumentation? — if
  engine instrumentation is proposed, it must be minimal and justified).
- **Packaging:** JUnit suites tagged `@Tag("e2e")` in
  `maestro-integration-tests` (making the vacuous `e2eTest` Gradle task
  real), Testcontainers-orchestrated or compose-driven (argue the choice),
  wired into nightly CI alongside the loan E2E.
- Seeding/reproducibility strategy, failure-dump format (instance row +
  full event log + chaos action log), and runtime budget per mode.

**Done when.** The design doc exists with all sections, open questions
explicitly listed, and the COORDINATOR has appended an approval ruling
section resolving every open question. Implementation (Task 7) must not
start before that ruling exists.

## Task 7: Chaos/soak harness implementation (spec Phase 2)

**Goal.** Implement the approved design from Task 6 exactly; deviations
require a coordinator ruling appended to the design doc first.

**Requirements.**
- 10-minute PR-gate mode runs green 3 consecutive times locally.
- Soak mode has produced at least one multi-hour clean run.
- Invariant violations fail the run with actionable dumps (instance row +
  full event log + chaos action log).
- `./gradlew :maestro-integration-tests:e2eTest` now actually executes the
  tagged suites; nightly CI wiring committed.
- Issue 12 metrics captured to files in the evidence dir (these are the
  §Issue 12 benchmark numbers), and Issue 11 duplicate-side-effect counts
  captured under chaos.
- Any engine defect found: library-bug protocol, fix in the owning module
  with failing-test-first, and record for Task 8's docs.

**Done when.** All of the above with evidence (identity-stamped logs).

## Task 8: Findings and evidence docs (spec Phase 3)

**Goal.** Truthful documentation of everything measured.

**Requirements.**
- Append the Issue 12 benchmark numbers to `docs/open-issues.md` §Issue 12.
- Append measured duplicate-side-effect data to §Issue 11 as the evidence
  base for the fencing decision.
- Document multi-instance deployment guarantees and measured bounds
  (adoption latency, terminate convergence, deploy safety) in
  `docs/maestro-architecture.md` or a new `docs/operations.md` — judge
  which is cleaner and say why.
- Remove the stale "`e2eTest` runs zero tests" note in
  `docs/open-issues.md` §3 (only if Task 7 made it untrue).
- Record any defects found en route in `docs/open-issues.md` per its
  format.
- No stale claims introduced or left behind (check `docs/test-plan.md`,
  `README`, release notes for statements this work invalidates).

**Done when.** Docs merged into the branch, cross-checked against the
actual evidence files (numbers in docs must match the logs).

## Task 9: Final QA gate

**Goal.** Independent verification with evidence — QA never patches; on
any failure it reopens the owning task.

**Requirements.**
- Prune nothing: verify every evidence log's EMBEDDED identity (pwd, git
  rev-parse, branch, timestamp) matches THIS cycle's worktree and commits.
  Reject any artifact whose embedded identity does not match.
- Re-run: full `./gradlew build` on the exact tree; the new e2e-tagged
  suites green 3× (`--rerun-tasks`); the extended loan E2E (cluster mode,
  new scenarios) at least once end-to-end with process-identity checks
  (PIDs vs pid files, deployed jars contain this branch's classes).
- Verify docs claims against evidence (spot-check every number).
- Verdict file in the SDD workspace: PASS with evidence index, or FAIL
  naming the owning task and the exact failing evidence.

**Done when.** PASS verdict with a complete evidence index.

## Task 10: Fix cross-node timer wake (library defect, Issue 17)

**Added by coordinator ruling after Task 1 exposed the defect** (see
`.superpowers/sdd/multi-instance/rulings.md` Ruling 1).

**Defect.** `TimerPoller` polls due timers only on the elected leader
(`TimerPoller.java:160`). `WorkflowExecutor.fireTimer` CASes the timer row
`PENDING → FIRED` in the shared store, then unparks via `ParkingLot` — a
per-JVM map, so the unpark is a no-op when the workflow's parked virtual
thread lives on a different node. `DefaultWorkflowOperations.sleep()` parks
indefinitely (`DefaultWorkflowOperations.java:209`) with no periodic
recheck — unlike `SignalManager.awaitSignal`, which parks in
`wakeRecheckInterval` chunks (`SignalManager.java:276-280`) exactly to
survive missed cross-process wakes. Consequence: in ANY multi-instance
deployment of a service whose workflows call `workflow.sleep()`, whenever
the timer-poller leader is not the node owning the parked thread, the
timer is durably marked FIRED (invisible to `getDueTimers` forever), no
thread wakes, and the Issue 2/13 self-heals never run (they require a
replay); the workflow wedges until its owning node restarts. Routine
operation — no failure injection needed. `cancelTimer`'s unpark is
local-only too (same gap for cross-node cancellation of a parked sleep).

**Reproduce first (library-bug protocol).**
1. A failing `maestro-core` unit test (in-memory SPIs): a workflow parks in
   a live `sleep()`; the test marks its timer FIRED directly through the
   store (simulating a remote leader's `fireTimer` — store write happened,
   local unpark didn't); assert the workflow completes within a short
   configured recheck interval. Today it hangs forever — assert the hang
   deterministically (bounded await that fails).
2. A failing multinode integration test in
   `maestro-integration-tests` `multinode/` (two `MaestroEngineHarness`
   instances over one real Postgres): node B holds timer-poller
   leadership, node A owns a sleeping workflow; assert the workflow
   completes. Arrange leadership deterministically (e.g. only node B runs
   a timer poller, or B wins election before A starts — study the harness;
   the existing multinode suites encode the fixture patterns).
Watch both fail for the predicted reason, then fix, then watch green.

**Fix (direction decided by ruling — mirror the signal-wake pattern).**
- `sleep()`'s live park becomes chunked `parkWithTimeout` at a recheck
  interval, mirroring `SignalManager.awaitSignal`; on each wake/timeout,
  consult `store.findTimer(...)`: `FIRED` → append the `TIMER_FIRED`
  event and continue (identical event/sequence semantics to the existing
  replay heal); `CANCELLED` → the Issue 13 outcome (append
  `TIMER_CANCELLED`, throw `TimerCancelledException`); `PENDING` → keep
  parking until the deadline logic says otherwise.
- Reuse the existing wake-recheck configuration seam
  (`maestro.signal.wake-recheck-interval` reaches the engine already) —
  a separate timer property only if reuse is genuinely awkward; document
  the choice in the report. Local unpark remains the instant fast path;
  behaviour when leader == owner must be unchanged.
- Respect `ExecutorShutdownException`/`WorkflowTerminatedException`
  semantics while parked (mirror how `awaitSignal`'s chunked park handles
  shutdown; `Error` before `Exception` at any unwrap site).
- No new messaging, no schema change, no SPI change expected. If you
  conclude an SPI change is needed, STOP and report.

**Also required.**
- Record as Issue 17 in `docs/open-issues.md` (follow the house format:
  What's wrong / Why it matters / Where / resolution callout with commits
  + pinning tests), and a `docs/release-notes.md` line (observable
  change: cross-node timer fires now take effect within the recheck
  interval; previously wedged forever).
- Full `./gradlew build` green; the new tests green 3× `--rerun-tasks`.
