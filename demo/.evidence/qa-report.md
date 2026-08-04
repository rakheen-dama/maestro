# Task 6 — QA gate report

**Identity.** `pwd=/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/demo`,
branch `worktree-demo`, HEAD at start of QA `1267564`. Host: Darwin 25.5.0 arm64,
Docker 28.5.1, OpenJDK 25.0.1. QA window 2026-08-04 17:12Z → 17:46Z.

Evidence under `demo/.evidence/task-6-*.log`; every file carries its own
pwd / `git rev-parse HEAD` / branch / timestamp header.

**Rule of this report.** Every figure below has been grepped back out of an archived
file in `demo/.evidence/`. Where something is reasoned rather than observed, it says so.

---

## GATE VERDICT: FAIL(Task 4)

One blocking defect and two factual errors, all three in Task 4's deliverables
(`demo/scripts/preflight.sh` and `demo/RUNBOOK.md`). Everything else — the stack,
the v2 source set, the deck, the memory budget, the untouched sample — passed, and
in most cases reproduced Task 4's published numbers to within a second.

The demo *works*. It does not work **cold, from the runbook, without an undocumented
step** — which is the exact condition this gate exists to test.

---

## The blocking finding

### F1 — a genuinely cold machine fails the runbook's very first command, deterministically, with no documented fix

**Owner: Task 4** (`demo/scripts/preflight.sh` readiness gate + RUNBOOK §0.1's
"Common failures and the fix" table).
Evidence: `task-6-runbook-00-preflight.log`,
`task-6-runbook-00-preflight-FAILURE-diagnosis.log`,
`task-6-F1-reproduction-cold-run-2.log`.

Starting from `docker compose -f demo/docker-compose.yml down -v` — which RUNBOOK §T
itself calls *"the **correct** way to get a truly clean start"* — `demo/scripts/preflight.sh`
exits **non-zero at step 7/8**:

```
PREFLIGHT FAILED: the throwaway loan did not complete.
PREFLIGHT_EXIT=1
```

**Root cause, measured.** Preflight declares the services ready on
`/actuator/health == 200` and immediately drives the throwaway loan. The sample's own
domain-topic listener has not joined its consumer group yet, and that listener runs
`auto.offset.reset = latest`, so the verification request is published into partitions
nobody is assigned to and is never delivered.

| | run 1 | run 2 |
|---|---|---|
| loan-application `Started workflow 'loan-preflight-…'` (publishes the request) | 19:27:16.078 | 19:43:18.673 |
| verification-gateway `verification-gateway: partitions assigned: [loans.verification.requests-0,-1,-2]` | 19:27:18.284 | 19:43:20.803 |
| **gap** | **2.21 s too late** | **2.13 s too late** |

Both runs park at exactly two events (`recordApplication`, `requestVerifications`) and
`verification_gateway` holds **0 workflow instances** — the request never arrived.
Attribution is pinned to the right consumer group by grep:

- `group.id = verification-gateway` (the sample's `@KafkaListener` on the domain topic) → `auto.offset.reset = latest` ← the one that dropped the record
- `group.id = maestro-verification-gateway` (Maestro's own signals topic) → `auto.offset.reset = earliest` ← safe

**Two for two. Not a flake.**

**Why no earlier rehearsal caught it.** Every previous run reused a warm Kafka whose
consumer group already had committed offsets, so `auto.offset.reset` never applied. It
only bites after `down -v`. This is precisely the case the QA brief demanded.

**Why it is blocking rather than cosmetic.** RUNBOOK §0.1 says *"Anything else is a hard
stop — it exits non-zero and tells you which step failed. Fix it now, not on stage."*
The "Common failures and the fix" table has **no row for this symptom**. The presenter
is stranded at T-30 with a hard stop and no documented remedy — while the remedy is
trivial and I confirmed it twice: **run preflight a second time** and it passes in
**35 s** and **34 s** respectively (`task-6-runbook-00-preflight-rerun.log`,
`task-6-F1-reproduction-cold-run-2.log`), because the group now has committed offsets.

**QA does not prescribe the fix**, but the options are: gate step 6→7 on consumer-group
partition assignment rather than on HTTP health; or set the sample's domain-topic
listeners to `earliest`; or — the documentation-only fix — add the row to the
common-failures table and tell the presenter the first cold preflight is expected to
fail once. Any of the three closes it.

---

## The other findings

### F2 — §D5 tells the presenter "there is no `SIDE_EFFECT` row to point at". The first event log the demo puts on screen has four.

**Owner: Task 4** (`demo/RUNBOOK.md` §D5). Evidence: `task-6-finding-F2-side-effect-rows.log`,
`task-6-runbook-02-scenario1-happy.log`.

RUNBOOK.md:710-711 states verbatim:

> There is no `SIDE_EFFECT` row to point at: these workflows never call
> `currentTime()` or `randomUUID()`, so none was ever recorded.

The §1 happy loan's live event log, read straight out of Postgres:

```
    6  SIDE_EFFECT                   $maestro:currentTime
    7  SIDE_EFFECT                   $maestro:currentTime
   13  SIDE_EFFECT                   $maestro:currentTime
   14  SIDE_EFFECT                   $maestro:currentTime
```

Four of them, in the very table §D2 invites the room to read. Every other scenario shows
them too (crash: 6/7/9/15/16/18; withdraw: the same).

**Mechanism.** The *author* indeed never writes `currentTime()` — but the *engine* does.
`DefaultWorkflowOperations.java:507` computes a timed await's deadline as
`currentTime().plus(timeout)`, and `currentTime()` appends the row
(`DefaultWorkflowOperations.java:674`). The loan workflow's two withdrawal gates are timed
awaits, which is exactly why the rows sit at 6/7 (before the awaits at 8/9) and 13/14
(before the awaits at 15/16).

The claim is false as written and falsifiable from the projector. The real explanation —
*the engine records its own non-determinism, not just yours* — is better material than the
disclaimer it would replace. The deck does **not** repeat the claim (grepped); this is
runbook-only. Same failure class as Task 5's C1 (the invented event-log table): a table
described from reasoning rather than read off a run.

### F3 — §D4 says "seven containers"; the command printed underneath shows six

**Owner: Task 4** (`demo/RUNBOOK.md` §D4), minor. Evidence: `task-6-runbook-08-D2-D4.log`.

RUNBOOK.md:686-687 tells the presenter to type
`docker compose -f demo/docker-compose.yml ps` and say *"seven containers"*.
Measured: that command prints **6** rows; `ps -a` prints **7**. `kafka-init` has run and
exited. The compose file does define seven services, so the number is defensible — the
command shown beneath it does not produce it, and the room counts six on the projector.

---

## What passed

### Constraint check — zero-diff vs `main` (`task-6-identity-and-constraints.log`)

`git diff main...HEAD --stat` is empty for **`maestro-core`**,
**`maestro-samples/sample-loan-origination/e2e`**, and **`maestro-integration-tests`**
(where the chaos harness lives — `ChaosCluster`, `ChaosSoakE2EIT`, `InvariantChecker`, …).
All three untouched.

### `./gradlew build` (`task-6-full-build.log`)

A first `./gradlew build` was green in 2 s but entirely incremental, and a `clean build`
was green in 3 s with **every test task `FROM-CACHE`** — no evidence at all. Re-run as
`./gradlew clean build --no-build-cache`:

```
BUILD SUCCESSFUL in 2m 8s
155 actionable tasks: 145 executed, 10 up-to-date
GRADLE_EXIT=0
```

25 `:test` tasks executed, **0 FROM-CACHE**. Tests genuinely ran.

### Loan E2E, untouched (`task-6-loan-e2e.log`)

`maestro-samples/sample-loan-origination/e2e/run-e2e.sh`, `E2E_EXIT=0`, **10/10 PASS**:

```
PASS 1. Happy path (co-borrower signs first)       13s
PASS 2. Out-of-order doc (orphan adoption)         15s
PASS 3. Conditions loop -> round-2 approval        15s
PASS 4. Withdrawal after rate lock (saga)          15s
PASS 5. Crash recovery (kill -9 + replay)          81s
PASS 6. Two-node loan-application (multi-node)     25s
PASS 7. Owner-kill -> peer adoption (multi-node)   78s
PASS 8. Rolling restart (graceful SIGTERM mid-flight) 39s
PASS 9. Timer-poller leader failover (verification-gateway) 105s
PASS 10. Cross-node admin retry/terminate          120s
```

0 FAIL lines. Ports clean after teardown. The demo has not disturbed the sample it is built on.

### The five top scenarios

| § | Result | Measured | Runbook says |
|---|---|---|---|
| §1 happy | PASS | 15 s; `3/3 verification.result signals recorded`; FUNDED with rateLockId + disbursementId; log ends `WORKFLOW_COMPLETED` | 14–20 s |
| §2 `kill -9` | PASS | phase 1 15 s / **12 rows** / restart **4 s** / finish **41 s**; `curl` → `000`; rate lock after **34 s**; `diff is empty`; `duplicate sequence numbers: 0` | 17 s / 12 rows / 5 s / 40 s / 33 s |
| §3 Jaeger | PASS | happy loan = **one** trace, **31 spans**, 3 distinct services; crashed loan = **two** traces, **10 + 23** spans | "roughly 20–35 (a rehearsal measured 31)"; "10 before the crash and 23 after" |
| §4 withdraw | PASS | 16 s; `COMPENSATION: released rate lock … for loan withdraw-…`; `is FAILED`; compensation at seq 22000/22001/23001/23002 | 18 s, budget 45 s |
| §5 Grafana | PASS | parked **peak 12 at t+6 s, back to 0 at t+18 s** | "peak 12 at t+6 s, back to 0 at t+18 s" |

§3's crashed-loan split (10 + 23) and §5's load curve reproduced the published figures
*exactly*. §5's supporting claims all hold: 3 Prometheus targets `up`; Grafana
`/d/maestro-demo` → 200; `:8080/actuator/prometheus` → **404** as documented; and every
metric name the dashboard queries resolves live (`maestro_workflow_started_total` 3
series, `_completed_total` 3, `_failed_total` 1, `maestro_workflows_parked` 3,
`maestro_activity_duration_seconds_bucket` 690, `maestro_recovery_adopted_total` 3) —
except `maestro_standdown_total` at 0 series, which is exactly the `vector(0)` case
RUNBOOK.md:470-477 discloses, and discloses honestly.

### §D1 — the v1→v2 deploy deep dive (`task-6-runbook-07-D1-v1-to-v2.log`)

`D1_EXIT=0` in **36 s** (runbook: 32–36 s). Every PIN asserted:

- PIN 0: distinct jar SHAs; `parallel-verification` count **1 in v2, 0 in v1**
- PIN 3: in-flight loan `COMPLETED`, `VERSION_MARKER rows: 0`, `sequence >= 1000: 0` — sequential path
- PIN 4: new loan `VERSION_MARKER rows: 1`, payload `{"version": 2, "changeId": "parallel-verification"}`, 20 events in branch bands
- **band arithmetic exact**: branch 0 from **5001**, branch 1 from **6001**, parent resumes **7001** — precisely RUNBOOK §D2's `p*1000 + (i+1)*1000`
- PIN 5: `'Parking key already occupied' errors: 0`; one `Version conflict writing status` line, retried successfully against a fresh read (neither runbook nor deck quotes a conflict count, so nothing is contradicted)
- PIN 6 undercount warning **verified**: the v2 loan reported **12 spans** at script exit and **28 spans** on re-query 30 s later — 28 is exactly the archived reference. The in-flight loan appears as **two** traces (19 + 14 spans), as §D1's point 4 predicts.
- `RESTORE_V1=1` restored v1 in **6 s** (runbook: 6 s); the §0.2 identity table correctly reverted to `loan-application-service-0.3.0-SNAPSHOT.jar`.

### The remaining deep dives, and every script the runbook invokes

- **§0.2** identity re-print (RUNBOOK.md:118-120) — works verbatim, resolves all four jars.
- **§0.3** `reset.sh` — `clean slate in 17s`, exactly the documented 17 s.
- **§D2** — `drive-loan.sh events <id>` works; the uniqueness guarantee read live out of Postgres: `idx_wf_event_replay :: CREATE UNIQUE INDEX … USING btree (workflow_instance_id, sequence_number)`, exactly as claimed.
- **§D3** — no live commands; its figures check out against `tasks/todo.md:26` (2376 workflows, 0 invariant violations, 0 duplicate side effects, run `20260801-214325--6973268155056049009`).
- **§D6** — PASS. Node B on 8094; the Prometheus file-SD 4th target appeared and, after `stop-services.sh`, was removed. Adoption **26.7 s** from `kill -9` to node B's `Resuming workflow` (runbook: 27 s); `finish` through node B **30 s** (runbook: 49 s, budget 60 s — inside it). The `LOAN_URL=` override behaves as documented.
- **§T** teardown — both forms run clean; the port check returns empty.
- **`drive-loan.sh conditions`** (listed in the script table, deliberately given no stage section) — exercised anyway: `CONDITIONS_EXIT=0` in 24 s, two underwriting rounds, FUNDED.
- Static counts all correct: **11** Kafka topics, **11** ports in preflight's array, **5** Grafana panels with exactly the titles §5's table lists, **7** compose services (see F3).

### The deck (`task-6-deck-offline-and-do-blocks.log`)

`demo/presentation/index.html`, sha256 `123fad82e153…`.

- **Offline: proven statically.** Zero external references of any kind — no `https://`, no
  protocol-relative URLs, no `@import`, no `fetch(`/`XMLHttpRequest`/`WebSocket`, no external
  stylesheet or script `src`, no `url()` in CSS. All four font stacks are local
  (`ui-monospace`, `ui-serif`, `ui-sans-serif`, …). The only absolute URLs in the file are
  three `http://localhost:*` links the presenter clicks deliberately.
- **Renders and navigates.** 20 slides in `#stage` (21 `.slide` elements — the 21st is the
  presenter-preview clone, as Task 5 documented). Walked by its own keyboard navigation at
  1440×900: 20/20 unique slugs reached (`#title` … `#authoring`), `bodyOverflowX=false` and
  no vertical document overflow on every one. With the presenter panel on: **0/20 clipped**;
  the only non-unit fit scales are `#d1-code` 0.84, `#d2` 0.93, `#authoring` 0.74 — consistent
  with, and less severe than, the 68%/62% RUNBOOK.md:44-48 discloses for 1280×720.
  *(Served from a throwaway local http server only because the automation harness blocks
  `file://`; the sole console message in the whole walk was that server's own favicon 404,
  which cannot occur under `file://`. Task 5's archived real-Chrome `file://` run stands.)*
- **`DO:` blocks resolve.** 20 `DO:` blocks reference §0, §1, §2, §3, §4, §5, §D1, §D2, §D3,
  §D4, §D5, §D6 — **12 of 12 exist as headings in `demo/RUNBOOK.md` with those numbers**.

### Peak memory (`task-6-peak-memory.log`)

1213 samples over the whole run (preflight cold + warm, reset, §1–§5, D1 + restore, D2/D4,
D6 in TWO_NODE, conditions), `docker stats` for containers + `ps -o rss` for host JVMs, every 5 s.

| Figure | Measured | Runbook (§D4) |
|---|---|---|
| **True simultaneous peak** (max of the instantaneous sum, 11 components) | **2480.7 MiB = 2.42 GiB** | — |
| Sum of per-component peaks, four-JVM config | 2347.1 MiB = 2.29 GiB | 2329 MiB = 2.27 GiB |
| Sum of per-component peaks, five-JVM (TWO_NODE) | 2659.3 MiB = 2.60 GiB | — |
| Largest single consumer | kafka 627.6 MiB | 656 MiB |
| Cost of the fifth JVM | 312.2 MiB | ~360 MiB |

**Fits the ~4 GB budget with ~1.5 GB of headroom even in TWO_NODE mode.** Every published
figure lands within 10% and on the conservative side. §D4's memory paragraph needs no change.

---

## Triage of the deferred minors

`progress.md` parks **four** items matching "deferred" (three from Task 4, one from Task 5),
though the brief says three. All four triaged. **None is fix-before-demo; three are now
closed by measurement rather than merely recorded.**

**1. Task 4 — RUNBOOK.md:301-311's Kafka join timestamps are in no archived file.**
→ **RECORD AND SHIP — and the evidence gap is now closed.** I measured it independently
(`task-6-kafka-rejoin-measured.log`): after `kill -9`, `Request joining group` 19:33:26.782 →
`Successfully joined group` 19:34:04.011 = **37.2 s**, with `loans.underwriting.decisions`
assigned at 19:34:04.031. After a *clean* restart: 19:31:27.950 → 19:31:30.954 = **3.0 s**.
The runbook claims 36 s and 3 s. Reproduced within a second, both. The claim was always
correct; it simply had no archive. It has one now. Optional one-line docs change: point
RUNBOOK.md:311 at `demo/.evidence/task-6-kafka-rejoin-measured.log`.

**2. Task 4 — the D6 ownership check was "reasoned, not observed".**
→ **RECORD AND SHIP — now observed.** In my D6 run
(`task-6-runbook-09-D6-two-node.log`), the shipped check
`grep -l "Started workflow 'loan-<id>'" …` matched **node A's log only**, while the old
bare-id check matched **both** (4 hits in the owner's log, 3 in the peer's). Node B's line
is `Resuming workflow`, never `Started workflow`, exactly as reasoned — and
`WorkflowExecutor.java:449/487` confirms the two log sites are distinct. The wording can
drop its hedge.

**3. Task 4 — D1's quoted 32-36 s predates the 5 s poll flag.**
→ **RECORD AND SHIP — figure re-validated.** My D1 run against the current
`lib/jvm-env.sh` (poll flag included) measured **36 s**, inside the quoted 32-36 s range.
The figure is valid for the config that actually ships. No change needed.

**4. Task 5 — `fitSlide` has no floor, so a future overfull slide shrinks silently.**
→ **RECORD AND SHIP.** Confirmed hypothetical, not present: 0/20 slides clip at 1440×900
with the presenter panel on, and the deepest scale in the whole deck is 0.74. The stated
mitigation is real and external (the harness prints per-slide scale; RUNBOOK.md:44-48
discloses the 720p figures and recommends ≥1440×900). It is a maintenance hazard for
whoever adds slide 21, not a demo risk. Worth a comment in `fitSlide` naming the trade-off.

---

## GATE VERDICT: FAIL(Task 4)

Reopen Task 4 for three items in its own deliverables:

1. **F1 (blocking)** — the first cold preflight fails deterministically at step 7/8 and the
   runbook documents no remedy. Fix the readiness gate, or the listener's offset reset, or —
   at minimum — the common-failures table.
2. **F2** — §D5's "there is no `SIDE_EFFECT` row to point at" is false against every event
   log the demo shows.
3. **F3 (minor)** — §D4's "seven containers" against a command that prints six.

Nothing else needs to change. Tasks 1, 2, 3 and 5 pass this gate: the stack, the versioned
redeploy, the deck, the memory budget and the untouched loan sample all held, and Task 4's
own published timings and span counts were reproduced with unusual fidelity — which is why
these three stand out rather than blend in.
