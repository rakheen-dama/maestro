# Maestro — Multi-Instance Operations Guide

**Audience:** an operator running more than one instance of a service that
embeds Maestro — the normal production topology, not an edge case.
**Status date:** 2026-08-01, after the multi-instance verification cycle
(`.superpowers/sdd/multi-instance/`).

## Why this document exists, and why it's separate from the architecture doc

`docs/maestro-architecture.md` describes what the engine is *designed* to
guarantee — the mermaid diagrams, the state machine, the SPI contracts. This
document is narrower and more operational: what happens, **with measured
numbers**, when you actually run two-or-more instances of a service and kill,
restart, or partition them. Every bound below is traceable to a specific
evidence file, not derived from reading the source. Growing
`maestro-architecture.md` with a wall of "observed 43-50s" tables would bury
its design-reference purpose under operational detail that changes as the
engine changes; keeping it here means an operator tuning a Kubernetes
`terminationGracePeriodSeconds` or deciding whether to page on-call for a
`PAUSE_RESUME`-shaped incident has one place to look, and a contributor
reading the architecture doc isn't wading through timing tables to understand
the design. Cross-links run both ways (see `maestro-architecture.md` §14
"Failure Modes").

**Read this as "what we observed under CI conditions," not as an SLA.**
Every number here comes from a specific machine, a specific run, a specific
workload (the `sample-loan-origination` demo). They are useful as *shape* —
which term dominates a bound, whether two backends behave equivalently — not
as guarantees for your production hardware, network, or workload.

---

## 1. Owner-kill → peer adoption (`kill -9`, no restart)

**Scenario:** a node holding a workflow's instance lock is killed outright
(`kill -9`, no graceful shutdown) and never restarted. A peer node — same
`maestro.service-name`, same store, same lock namespace — must adopt the
workflow via the recovery poller once the lock's TTL lapses, and complete it
alone.

| Lock backend | Observed adoption latency | Bound | Source |
|---|---|---|---|
| Valkey (default) | 84s, 29s, 35s (3 consecutive runs); 48s (tightened re-run); 46s (Task 5 smoke) | 150s (30s lock TTL + 60s recovery poll-interval + slack) | `.superpowers/sdd/task-2-report.md` §"Scenario 7, 3 consecutive runs"; `evidence/task2/run{1,2,3}-scenario7.log` |
| Postgres | 45s, 43s, 50s (3 consecutive runs) | 150s (unchanged — no wait-bound change was needed for Postgres) | `.superpowers/sdd/task-5-report.md` §"3× consecutive green runs on postgres"; `evidence/task5/postgres-run{1,2,3}-s7-8.log`, `backend-timings.md` |

**What dominates the bound.** On both backends, adoption latency is
**recovery-poll-interval-dominated, not lock-aging-dominated**. Valkey ages
the instance lock by key TTL expiry; Postgres ages it by the `expires_at <
now()` predicate in `PostgresDistributedLock`'s `ON CONFLICT` clause — both
amount to "the lock becomes takeable once its TTL has lapsed," so the spread
(29-84s valkey, 43-50s postgres) reflects where the kill happened to land in
the peer's 60s `maestro.recovery.poll-interval` cycle, not a backend
difference. The two backends are statistically indistinguishable at this
sample size (`backend-timings.md`'s own conclusion; Postgres showed the
tighter spread in this data, which is noise at n=3, not a claim that
Postgres is faster).

**Operational takeaway.** If you need faster adoption after a hard node
death, lower `maestro.recovery.poll-interval` (default 60s) — that is the
term that dominates, not the lock TTL.

---

## 2. Rolling restart (graceful deploy)

**Scenario:** a node is sent `SIGTERM` (not killed) while it owns workflows
parked in `WAITING_SIGNAL`, with a peer node up throughout. Graceful shutdown
(Issues 4/5's semantics: `ExecutorShutdownException` extends `Error`, so an
ordinary `catch (Exception)` around `awaitSignal()`/`sleep()` can't swallow
it) must leave every parked workflow `WAITING_SIGNAL` — never `FAILED`, never
compensated — and the peer must be able to read/signal them while the node is
down.

| Lock backend | Observed scenario duration (3 runs) | Zero FAILED? | Zero compensation? |
|---|---|---|---|
| Valkey (default) | 29s, 30s, 32s | Yes, all 3 runs | Yes, all 3 runs |
| Postgres | 38s, 37s, 41s | Yes, all 3 runs | Yes, all 3 runs |

Source: `.superpowers/sdd/task-3-report.md` §"Scenario 8, 3 consecutive
runs"; `.superpowers/sdd/task-5-report.md` §"3× consecutive green runs on
postgres"; `evidence/task3/run{1,2,3}-scenario8.log`,
`evidence/task5/postgres-run{1,2,3}-s7-8.log`.

**Why deploy is faster than owner-kill.** Graceful shutdown's `finally`
block releases the instance lock immediately
(`WorkflowExecutor.executeWorkflow`, per-workflow, on
`ExecutorShutdownException`) instead of leaving it to expire via TTL — so the
restarted node's own `StartupRecoveryRunner` (which runs synchronously on
boot, not gated on the 60s poll cadence) resumes all three workflows in
roughly **2 seconds**, not the TTL/poll-interval-bound tens of seconds a hard
kill requires. This is the practical argument for graceful shutdown over
`kill -9` wherever your platform gives you the choice (`.superpowers/sdd/
task-3-report.md` §"Decisions and justifications").

**Coverage boundary, stated honestly.** Scenario 8 drives three workflows
into three distinct `WAITING_SIGNAL` states (fan-in await, decision await,
signature await) — it does **not** achieve a `WAITING_TIMER` state on the
node being restarted. The coordinator ratified this explicitly during the
cycle (`.superpowers/sdd/progress.md`, Task 3 entry): *"`awaitSignal(timeout)`
does NOT persist a durable timer row... the brief was wrong; scenario covers
3 distinct WAITING_SIGNAL states; WAITING_TIMER deploy-safety is covered by
engine ITs + the Task 7 chaos harness ROLLING_RESTART action on verification
nodes."* If you need evidence specifically for "a workflow parked in
`sleep()` survives a graceful restart," look at
`WorkflowExecutorShutdownTest`/`ShutdownContractIT` (engine-level) and the
chaos harness's `ROLLING_RESTART` action against `verification-gateway`
nodes (which do run genuine `sleep()`-backed workflows), not scenario 8.

---

## 3. Timer-leader failover

**Scenario:** two instances of a service run `workflow.sleep()`-backed
workflows; only one is the elected `TimerPoller` leader
(`maestro:leader:timer-poller:{service}`, 15s TTL). The elected leader is
`kill -9`'d while a workflow *not owned by the leader* is mid-sleep on the
surviving node. The durable timer must still fire and the workflow must
progress, bounded by leader-TTL + poll-interval + wake-recheck.

| Lock backend | Observed failover latency | Bound |
|---|---|---|
| Valkey (default) | 12s, 12s, 19s (3 runs); 18s (smoke) | 60s (15s leader TTL + 5s poll + 5s wake-recheck + slack) |
| Postgres | 14s, 16s, 15s (3 runs) | 60s (unchanged) |

Source: `.superpowers/sdd/task-4-report.md` §"3× consecutive green runs";
`.superpowers/sdd/task-5-report.md` §"3× consecutive green runs on
postgres"; `backend-timings.md`. Same 15s leader TTL governs both backends
(`TimerPoller.LEADER_TTL` is backend-agnostic); equivalent within noise.

This scenario is the end-to-end proof of Issue 17's fix (§6 below):
`sleep()` no longer parks indefinitely on a node that isn't the timer-poller
leader — it rechecks the durable timer row every wake-recheck interval, so a
remote leader's fire is noticed within one interval instead of never.

---

## 4. Cross-node admin retry/terminate

**Scenario:** a workflow is driven to `FAILED` (no saga compensation) or left
`WAITING_SIGNAL` while owned by node A; node A is `kill -9`'d and never
restarted; `$maestro:retry` / `$maestro:terminate` are published (raw Kafka,
wire-identical to `AdminCommandService`) with only node B alive.

**Node-agnostic by design.** Both commands are ordinary signals routed
through the shared store — any live node consuming the service's signal
topic can execute them; nothing in the protocol depends on which node
originally owned the workflow. `WORKFLOW_RETRIED`/`WORKFLOW_TERMINATED`
lifecycle events land on the admin-events topic in every run.

| Lock backend | Observed admin-command convergence (3 runs) | Kill→first-command latency (worst case) |
|---|---|---|
| Valkey (default) | 44s, 44s, 44s | ~46s observed (`.superpowers/sdd/task-4-report.md` pitfall #5, kill `20:48:42` → retry executed `20:49:28`) |
| Postgres | 43s, 0s, 42s | Not separately measured; consistent with the ~43s worst case above |

Bound: `WAIT_ADMIN_COMMAND_SECS=90`. Source:
`.superpowers/sdd/task-4-report.md`, `.superpowers/sdd/task-5-report.md`
§"Admin retry convergence"; `backend-timings.md`.

**What actually dominates this bound — not the lock backend.** The first
command published to a partition the dead node owned isn't delivered to the
survivor until the Kafka consumer-group coordinator evicts the dead node's
session and reassigns its partitions — bounded by Kafka's default
`session.timeout.ms` (~45s, unconfigured by this sample). Every command
*after* that first rebalance is near-instant (Postgres run 2's `0s` is the
already-rebalanced case). The identical ~43-46s worst case on both lock
backends confirms the lock backend is not in this path at all — if you need
faster admin-command delivery after a hard node death, tune Kafka's
`session.timeout.ms`, not anything in Maestro.

---

## 5. Cross-node timer/signal wake bounds (Issue 17)

Before this cycle, a timer fired by a remote `TimerPoller` leader **never**
woke a `sleep()`-parked workflow on a different node — a routine-operation,
silent, permanent stall in any multi-instance deployment (`docs/open-
issues.md` Issue 17). Fixed: `sleep()` now parks in
`maestro.signal.wake-recheck-interval` chunks (default 30s at the engine
level — same property `SignalManager.awaitSignal` already used for signals)
and re-reads the durable timer row on every chunk expiry. A remote fire,
cancel, or terminate is noticed within one interval; a local fire still
unparks instantly (the fast path is unchanged).

**Sample configuration note.** `sample-loan-origination`'s three services set
`maestro.signal.wake-recheck-interval: 5s` (the engine default stays 30s) —
tightened specifically so the sample's multi-instance timing doesn't look
artificially slower than single-node for its short (2-8s) simulated
verification latencies. This is a sample choice, not an engine default;
choose your own interval as a latency/overhead trade-off (a shorter interval
means more parked workflows re-reading one indexed row more often).

**Lock-backend interaction.** Valkey's `ValkeyLockAutoConfiguration` provides
both the `DistributedLock` *and* the `SignalNotifier` (instant pub/sub wake).
With `maestro.lock.type: postgres`, the whole Valkey auto-config is disabled
— **Postgres-lock deployments have no `SignalNotifier`**, so cross-node
signal wake rides the wake-recheck interval alone (bounded ≤ the configured
interval, by design — the recheck exists precisely so pub/sub is an
optimisation, never a correctness dependency). See §7 below for the
lock-backend matrix in full.

---

## 6. Split-brain behaviour (Issue 11 + Issue 18)

**The stance, unchanged by this cycle:** Maestro does not implement lock
fencing (`docs/open-issues.md` Issue 11, open by design). A node that loses
its instance lock — a GC pause longer than the TTL, `docker pause`, a
partition — keeps running. The unique `(workflow_instance_id,
sequence_number)` event index is the store-correctness backstop: two nodes
racing on the same workflow cannot both persist a step's result, so **state
correctness does not depend on fencing.** Duplicate *external side effects*
(a payment API called twice) are not prevented — activities must be
idempotent.

**What changed this cycle: the loser now loses fast and clean.** Before
Issue 18 was fixed, a stale node's post-thaw event-append collision
(`DuplicateEventException`) was misrecorded as *the workflow failing* — a
workflow that had **succeeded** on the winner was durably marked `FAILED`
and its saga compensations ran, reversing completed work. That's fixed: the
same collision now stands the stale run down immediately (no write, no
compensation, the winner's outcome governs), mirroring the shutdown/
termination control-flow pattern. See `docs/open-issues.md` Issue 18 for the
full defect and fix.

**Measured consequence.** Under the chaos harness's mandated split-brain
trigger (≥2 loan-node `PAUSE_RESUME` actions — freeze a real container past
the 30s lock TTL, then resume it — per 10-minute PR-gate run), the three
consecutive PR-gate streak runs that are the gate of record measured **0
duplicate side effects across 211 workflows** (74 + 75 + 62). Full detail,
the "why zero" explanation, and the honest caveats (short windows, one
workload) live in `docs/open-issues.md` Issue 11's "Measured evidence"
subsection — this section is a pointer, not a duplicate, so the numbers stay
in one place. The multi-hour soak data point has since landed: **0 duplicate
side effects across a further 2,376 workflows** in a 120-minute soak chaos
window (run `20260801-214325--6973268155056049009`), taking the measured
total to 0/2,587. Full numbers and the run's provenance caveats (a pre-fix
PR-gate `@Timeout` collision in the same JVM, leaked-checker console noise,
binary-vs-stamp commit skew) live in that same Issue 11 subsection.

**Practical guidance, unchanged:** activities must still be idempotent. This
cycle's evidence says duplicate side effects are *rare* under the measured
conditions, not that they are impossible — do not remove idempotency
handling on the strength of a 0/2,587 sample.

---

## 7. Lock-backend matrix: Valkey vs Postgres

Both `maestro-lock-valkey` (default) and `maestro-lock-postgres` are
verified across the full multi-node scenario set (owner-kill adoption,
rolling restart, timer-leader failover, cross-node admin commands), 3
consecutive green runs each, via `E2E_LOCK_BACKEND=postgres` on
`sample-loan-origination`'s E2E script. Full comparison:
`.superpowers/sdd/multi-instance/evidence/task5/backend-timings.md`.

| Dimension | Valkey (default) | Postgres |
|---|---|---|
| Adoption latency (§1) | 29-84s | 43-50s |
| Rolling-restart total (§2) | 29-32s | 37-41s |
| Timer-leader failover (§3) | 12-19s | 14-16s |
| Admin-command convergence (§4) | 44s | 0-43s |
| Cross-node signal wake | Instant (pub/sub `SignalNotifier`) | ≤ wake-recheck interval only (no `SignalNotifier`) |
| Extra infra dependency | Valkey/Redis | None (reuses the service's Postgres) |

**Conclusion (backend-timings.md):** leader failover and adoption are
equivalent within noise on both backends — both age locks by the same
mechanism (TTL expiry vs. an `expires_at < now()` predicate) and are
dominated by poll intervals, not backend-specific latency. Admin-command
convergence is Kafka-session-timeout-bound, not lock-backend-related on
either side. The one durable behavioural difference is signal-wake latency
with no Valkey pub/sub present — bounded, by design, never a correctness
dependency.

**Schema footprint note.** Because the sample now depends on both lock
modules to make the `E2E_LOCK_BACKEND` switch possible, Flyway's classpath
scan picks up the Postgres-lock migration regardless of which backend is
configured — a **default (Valkey) boot now also creates the (unused, inert)
`maestro_distributed_lock`/`maestro_leader_election` tables** in the sample's
databases. See `maestro-samples/sample-loan-origination/README.md` and
`.superpowers/sdd/task-5-report.md`'s coordinator note for the full
explanation. This is specific to how the *sample* wires its dependencies
(both lock modules on the classpath) — it is not something `maestro-lock-valkey`
or `maestro-lock-postgres` do on their own; a production service that only
depends on one lock module only gets that module's migrations.

---

## 8. The multi-instance chaos/soak harness

Beyond the loan-origination E2E's scripted scenarios (§1-4 above, deliberate
single-fault scenarios), a second, complementary harness drives a real
six-node cluster (2 instances each of loan-application, verification-gateway,
underwriting) under continuous scripted chaos — pause/resume, partition,
backend outages, rolling restarts — while a seeded workload runs and store-
level invariants are checked continuously.

**What it checks (I1-I5, `chaos-harness-design.md` §5):** every workflow
reaches the terminal state its path script declared; terminal event logs are
well-formed (no duplicate sequence numbers, no unaccounted gaps below the
terminal event); no signal is permanently lost; duplicate side effects are
counted and correlated against the chaos action log (§6 above); recovery/lock
metrics are sampled for the Issue 12 benchmark (`docs/open-issues.md` Issue
12).

**What it found and fixed, this cycle:** Issue 18 (split-brain duplicate
append misrecorded as workflow failure), Issue 19 (timed-out `awaitSignal`
replaying nondeterministically after a routine rolling restart), and Issue
20 (a transient store outage during a parked workflow's wake-recheck probe
durably failing a healthy workflow — surfaced by the PR-gate re-proof run
for Issue 19's own fix, when a 39s partition outlived the connection pool's
30s timeout) — all real `maestro-core` defects, all fixed RED-first via the
library-bug protocol. None required deliberate failure injection beyond the
harness's normal operation; Issue 19 in particular was triggered by a
routine graceful rolling restart racing a late signal.

**How to run it:**

```bash
# PR-gate mode (default): ~10-minute chaos window. This is the gate of record —
# 3 consecutive fresh-seed runs, all green, is what "the harness passes" means.
./gradlew :maestro-integration-tests:e2eTest --rerun-tasks

# Soak mode: multi-hour window + the vs-node-count benchmark tail
# (chaos off, steady low rate, ~5 min at 6 nodes, graceful stop of one node
# per service, ~5 min at 3 nodes — the Issue 12 benchmark of record).
./gradlew :maestro-integration-tests:e2eTest --rerun-tasks \
    -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120
```

**Suite-selection note (`d4720ca`):** each dedicated invocation selects
*only* its dedicated test class — `ChaosPrGateE2EIT` runs on the default
invocation only, and is guard-disabled under the soak/golden/smoke/mode
flags. Before this fix, `-Dmaestro.chaos.soak=true` also selected the
PR-gate class, which picked up the soak duration and aborted at its own
25-minute `@Timeout` — the single root cause of every failed soak attempt in
this cycle. The CI weekly `chaos-soak` job uses the identical invocation and
self-heals through the same class-level guards; no workflow change was
needed.

Needs Docker; `e2eTest` pulls in the three sample services' boot jars via
`dependsOn`, so it is never wired into `build`/`check`. CI runs PR-gate mode
nightly (3× consecutive, `.github/workflows/e2e-nightly.yml` job
`chaos-pr-gate`) and soak mode weekly plus on-demand (`chaos-soak`). Evidence
(JSONL/CSV/JSON, identity-stamped) lands in `maestro-integration-tests/
build/chaos-evidence/<runId>/`; the SDD evidence mirror is
`.superpowers/sdd/multi-instance/evidence/task7/` (see its `INDEX.md`).
Design doc: `.superpowers/sdd/multi-instance/chaos-harness-design.md`.

---

## 9. Coverage boundaries — stated honestly

This section exists so nothing above is read as broader than it was
measured:

- **WAITING_TIMER under a rolling restart** is not covered by loan-E2E
  scenario 8 (§2's coverage-boundary note) — it's covered by engine
  integration tests and the chaos harness's `ROLLING_RESTART` action against
  `verification-gateway` nodes instead.
- **The `underwriting-service` pending-review queue
  (`PendingReviewRegistry`) is a documented best-effort, node-local view**
  (its own Javadoc says so). The loan-E2E driver works around this by
  polling *all* underwriting instances for pending reviews in cluster mode —
  a harness accommodation to a documented sample-application design choice,
  not an engine guarantee that pending-review state is cluster-visible.
  Decision endpoints themselves are unaffected (store-backed signals, routed
  cross-node by the engine regardless of which node receives the HTTP call).
- **Every §1-4 latency number is one workload** (`sample-loan-origination`)
  **on one set of machines.** They show which term dominates a bound (poll
  interval vs. lock TTL vs. Kafka session timeout), not a portable SLA.
- **The chaos harness's split-brain evidence (§6) is three 10-minute
  PR-gate runs plus one 120-minute soak run, one workload** — see
  `docs/open-issues.md` Issue 11 for the full caveats, including the soak
  run's provenance caveats (pre-fix PR-gate collision in the same JVM,
  leaked-checker console noise, binary-vs-stamp commit skew).
- **No lock fencing exists** (`docs/open-issues.md` Issue 11, open by
  design). Everything in §6 describes measured behaviour *given* that
  design decision, not a claim that split-brain is prevented.
- **Recovery-polling scale (`docs/open-issues.md` Issue 12)** now has its
  vs-node-count benchmark of record from the soak run's chaos-free tail:
  cluster recovery-query rate is proportional to node count — consistent
  with linear at both measured node counts (a constant
  ≈0.0167 calls/s per node at 6 and at 3 nodes), while lock probe/renew
  traffic tracks the parked-workflow backlog rather than node count. One
  workload, modest absolute load (6/min tail rate) — the *trend* is the
  result, not an SLA. See Issue 12's section for the table.

---

## 10. Versioning and mixed-version deploys

*Added in the release-hardening cycle. Unlike §§1–8, this section carries no
measured numbers — it is a behaviour playbook, and every statement in it is
traceable to engine code and its pinning tests rather than to a timing run.*

### 10.1 The deploy rule has not changed: upgrade all nodes together

**Upgrade every node of a service together, or drain the service first.** The
engine now stands down rather than failing when it meets history it cannot read
(§10.3), but that is a **safety net for the rolling window**, not a licence to
run a mixed fleet. A workflow whose next step only an upgraded node can read
makes no progress on the old nodes; it simply waits, safely, to be adopted by a
new one. Leave a fleet half-upgraded and those workflows wait indefinitely.

New event types are rare, so this is insurance, not a hot path.

### 10.2 `workflow.version()` — changing workflow code with instances in flight

Recovery re-runs the workflow method against the **current** code. If you edit a
workflow while long-lived instances are in flight, a replaying instance takes
the new path from wherever it resumed — half its work done the old way, the rest
the new way.

`workflow.version(changeId, minSupported, maxSupported)` makes the choice of
path a durable, memoized decision: the first live evaluation records
`maxSupported` as a `VERSION_MARKER` event and returns it; every replay returns
the **recorded** value forever, regardless of what the code's `maxSupported` has
moved on to. See [`docs/concepts.md` → Versioning Workflow Code](concepts.md#versioning-workflow-code)
for the two-step ship-then-raise-the-floor pattern and the rules.

Operationally, what matters:

- **`VERSION_MARKER` is a new event type in this release.** A node from the
  previous version that adopts a workflow whose history contains one cannot
  interpret it, and stands down (§10.3). This is exactly the case §10.1's rule
  covers.
- Raising `minSupported` before every pre-change instance has drained fails
  those instances with `UnsupportedWorkflowVersionException` — a genuine,
  deterministic workflow failure: it ends `FAILED` and saga compensation runs.
  Recovery: restore code carrying the old branch, then use the admin **Retry**
  action. Retry clears the failure memos but never the version marker, so the
  retried run replays the same recorded version against the restored branch.
- `maxSupported` must be a **code constant** — see the rules in
  `docs/concepts.md`.

### 10.3 Stand-down: what it is, and what it is not

When a node reads persisted history it cannot interpret, the run **stands down**
instead of failing:

- Nothing is written. **No compensation runs.**
- The instance keeps whatever recoverable status it already had.
- The instance lock is released as the thread unwinds.
- `EngineObserver.standDown(reason, workflowId, detail)` fires, incrementing
  `maestro.standdown{reason=...}`.
- A `WARN` is logged, of the form:

  ```
  Workflow '<id>' stood down at sequence <n>: <why> (instance status still
  <status>) — no failure recorded, no compensation run; an upgraded node will
  adopt it via recovery
  ```

An upgraded node then adopts and finishes the workflow through the ordinary
lock-TTL / recovery-poller machinery, unchanged. Nothing special is needed.

There are two unknown-history reasons and **they mean different things**:

| `reason` tag | What it means |
|---|---|
| `unknown_event_type` | An `event_type` string this build's enum does not define. Only a **newer** build can have written it. |
| `unknown_event_payload` | A stored payload this build could not deserialize while replaying. A newer build *or* an incompatible payload change. |

(A third value, `stale_run`, is unrelated to versioning — it is Issue 18's
duplicate-append convergence.)

### 10.4 The alarm that matters: `unknown_event_payload` on a homogeneous fleet

**A replay `SerializationException` is now a permanent re-adopt/stand-down loop
with no `FAILED` status.** On a genuinely mixed fleet that is correct and
self-healing — the workflow waits for an upgraded node. On a **homogeneous**
fleet it is not a version skew at all: it is an author's incompatible payload
change, and the new behaviour converts what used to be a visible failure into a
**silent zombie** — the workflow never completes, never fails, and re-adopts
forever.

So, on a fleet you know to be homogeneous (deploy finished, one version
running), a rising

```
maestro.standdown{reason="unknown_event_payload"}
```

means **"an incompatible payload change needs `workflow.version()`"** — not
"wait for the deploy to finish". Find the changed activity return type,
parameter type, or workflow input shape; gate the change behind
`workflow.version()` so in-flight instances keep the old shape; redeploy.

`unknown_event_type` keeps the "wait for the deploy" reading, because a type
this build does not define really can only come from a newer build. That is
precisely why the two are distinct enum constants and distinct tag values rather
than one.

**Suggested alerts:**

| Signal | Reading |
|---|---|
| `rate(maestro.standdown{reason="unknown_event_type"})` > 0 **during** a rolling deploy | Expected. Should return to zero once every node is upgraded. |
| the same, **after** the deploy completes | Not expected — a node was missed, or a stale replica is still running. |
| `rate(maestro.standdown{reason="unknown_event_payload"})` > 0 on a homogeneous fleet | **An incompatible payload change. Use `workflow.version()`.** |
| `maestro.workflows.running` / `maestro.workflows.parked` flat at zero on a node with traffic | That node is adopting nothing — check the lock backend and the recovery poller. |

Remember that both gauges are **node-local, in-JVM** values: dashboard them per
pod and sum for a cluster total (see `docs/observability.md`).

### 10.5 A skipped instance row reports "not found"

An instance row whose `status` column this build cannot map — again, written by
a newer node — is **skipped** by the store's row mapper rather than throwing.
`WorkflowStatus.valueOf` used to throw an `IllegalArgumentException`, and
because `WorkflowExecutor.recoverWorkflows` has no per-instance try/catch, one
such row aborted the **entire recovery pass** for every workflow on that node.
Skipping keeps the pass running, and an upgraded node — which can read the
status — owns the instance.

**The deliberate trade:** while that node cannot map the row, `getInstance`
returns empty, so an operator API asking this node about an existing workflow
reports it **"not found"**. Every caller already has a defined, non-destructive
answer for an absent instance — log and return; treat a signal as pre-delivery
so it is stored and adopted later, never discarded. The trade is documented in
the mapper's Javadoc (`AbstractJdbcWorkflowStore.mapInstance`) and it is chosen
deliberately: a misleading "not found" on one node during a deploy window is
cheaper than a recovery pass that stops for every other workflow on that node.

If an operator reports "the dashboard says the workflow doesn't exist" during a
rolling deploy, check for the WARN:

```
Unknown workflow status '<raw>' on instance (workflowId=..., id=...) — written
by a newer node; skipping this instance so the rest of this query, and any
recovery pass it feeds, still completes
```

---

## See also

- `docs/observability.md` — the full meter catalog (including
  `maestro.standdown`), span topology, and the Kafka trace-propagation
  contract.
- `docs/concepts.md` — `workflow.version()` and the determinism rules.
- `docs/open-issues.md` — Issues 11, 12, 17, 18, 19, 20 in full: what was
  wrong (or measured), where, and every pinning test.
- `docs/maestro-architecture.md` §14 "Failure Modes" — the design-level
  guarantee table this document adds measured numbers to.
- `docs/configuration.md` — `maestro.recovery.poll-interval`,
  `maestro.lock.ttl`, `maestro.signal.wake-recheck-interval`,
  `maestro.lock.type`, and every other property named above.
- `.superpowers/sdd/multi-instance/chaos-harness-design.md` — the chaos/soak
  harness's full design and implementation changelog.
- `maestro-samples/sample-loan-origination/README.md` and its `e2e/
  run-e2e.sh` header comment — the ten E2E scenarios these numbers come from.
