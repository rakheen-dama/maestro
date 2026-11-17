# WP12 Finding-Linked Load Scenarios Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Four k6 scenarios, each tied to one finding and run against the engine **before** its fix and **after** it, with both results recorded in this plan's Review section:

| Scenario | Finding | Fix | State on 2026-11-17 |
|---|---|---|---|
| `s2-recovery-drain`: a 5,000-run recovery drain, 1 node vs 2 nodes | 3.1 / Issue 30 | WP6 | in progress on `fix/recovery-capacity`, not merged |
| `s4-signals`: signal throughput across N nodes, wake-permit growth | 3.2 | WP2 | merged, PR #54 (`c925661b`) |
| `s5-pool-health`: connection-pool exhaustion vs the health endpoint | 3.5a | WP4 | merged, PR #59 (`0ca95927`) |
| `s6-retention`: retention sweeper and signal janitor vs foreground load | 3.11a, 3.11b | WP8 | not started |

**Architecture:** WP12 adds only harness files: `perf/`, `maestro-samples/sample-perf/` and `.github/workflows/perf.yml`, plus `docs/test-plan.md`. It adds no meter, no engine code and no library surface. Two kinds of change make up the work:
- **Shared harness additions (Task 1).** Planned node stops and starts ("topology events"), common checks that know about those events, an in-database activity sampler, and a node override for k6. S2 and S6 need them, and S4 and S5 reuse parts of them.
- **One scenario per task.** Each task adds a `perf/scenarios/<name>.sh`, a `perf/eval/<name>.jq`, its SQL and k6 files, and self-test cases. Each evaluator is built from failing self-test cases, against the scenario's first real run.

Each "before" runs the WP12 harness on the older engine (MD-1). Each fix-linked check is a **gate** that the before run is expected to FAIL (MD-3). Every PASS/FAIL comes from statement or row counts, or from a comparison inside one run. Latency is always advisory (spec §4 Lane C, D4).

**Tech Stack:** As WP11: k6 2.3.0 (built-in modules only), Docker Compose v2, Postgres 16 + `pg_stat_statements`, Kafka 3.9.0, Prometheus v3.5.0, bash + jq, Java 25 / Spring Boot 4.0.5 for `sample-perf`.

**Spec:** `00-spec.md`. The WP12 row; the paragraph under Lane C (pass/fail rests on statement counts per workflow and within-run comparisons, latency advisory, no Kafka-delivered signals); C4. Grounding:
- `evidence/research-D-k6-load-tests.md` §3 (S2, S4, S5, S6);
- `tasks/handover-2026-11-12-after-host-safety-cycle.md` 3.1, 3.2, 3.5, 3.11;
- `wp06-recovery-capacity.md` §"WP12 scenario 2" (lines 1065–1079), D-2 and D-4;
- the WP11 plan (`wp11-k6-foundation.md`), whose conventions this plan follows.

---

## Added surface

**Library (`maestro-*` modules): none.** No meter, config property, SPI method, migration, public type or engine behaviour.

**Unpublished `sample-perf`:**
- **Start types.** `POST /perf/workflows` takes two new `type`s, `hold` and `signals` (Task 2).
- **Health group.** A management-only health group `perf` (`include: maestro`, `show-details: always`) serves `/actuator/health/perf`. Root `/actuator/health` is unchanged.
- **Still true defaults.** No `maestro.*` key and no Hikari setting is added.

**Harness:**
- **Scenarios:** `s2-recovery-drain`, `s4-signals`, `s5-pool-health`, `s6-retention`.
- **New files:** `perf/lib/topology.sh`, the activity sampler (`perf/sql/activity-sampler*.sql`), new SQL, `perf/k6/signal-rate.js`, `perf/k6/health-probe.js`.
- **Self-tests:** `perf/eval/selftest-wp12.sh`.
- **Evidence:** `perf/.evidence/wp12/`.
- **CI:** `perf.yml` choices and schedule.

---

## 1. How a scenario is added today (researched at `origin/main` = `5b170009`)

| Concern | Where | What it does |
|---|---|---|
| Registration | `perf/run.sh:4` (usage), `:223` (`case "$SCENARIO" in smoke \| s1-throughput \| s3-parked`), `:295-296` | A scenario is a name in that `case`, plus `perf/scenarios/<name>.sh` and `perf/eval/<name>.jq`, both checked to exist. `docs/test-plan.md:357-361` states this rule. |
| Stack and replicas | `run.sh:349` `dc up -d --scale "node=${REPLICAS}" …`; `compose.yml:101-121` | `--replicas N` sets the replica count. Containers are `maestro-perf-node-<i>` (`lib/common.sh:45-47` `node_url`). Each mounts the stamped jar read-only. Nothing binds a host port except Prometheus. |
| Startup wait | `run.sh:371-382` | Every replica must answer `/actuator/health` with `"status":"UP"` (a grep) within 180 s. Boot 4 enables probes by default (`AvailabilityProbesAutoConfiguration`, `matchIfMissing = true`), so the root aggregate includes `readinessState` and the wait already covers readiness. |
| Identity | `run.sh:240-279` (identity.json), `:405-406` (`collect_nodes`), `:418-419` (`nodesAfter`), `common.jq:421-454` | `validity.identity`: every node reports the baked commit, and there are exactly `REPLICAS` distinct hostnames. `validity.noRestart`: the same hostnames and `jvmStartMillis` before and after the run. **A planned restart is INVALID today.** |
| Scenario contract | `run.sh:411-414` | `scenario_run` is sourced and must write `${RESULTS}/measurements.json`. It has `RUN_ID`, `RESULTS`, `REPLICAS`, `PROFILE` and every helper from `lib/common.sh`. |
| Helpers | `lib/common.sh` | `dc` (:27), `http_get` through the `curl` tool container (:59), `psql_json <sql>` as role `postgres` (:69; never counted as engine load), `collect_nodes` (:77), `k6_run` (:92; `PERF_NODES` is always every replica, :95), `wait_terminal` (:150), `warm_up` (:305), `measure_background` (:337), `host_load` / `container_stats` (:365, :378), `prom_instant[_checked]` (:394, :408). |
| Prometheus | `perf/prometheus/prometheus.yml` | 5 s scrape. `dns_sd_configs` on the `node` A records (refresh 5 s), so restarted or added containers are picked up. `run.sh:454-476` collects `queries.txt` (12 queries) over the whole run into `prometheus.json`, and lists empty series under `emptyQueries`. |
| Evaluation | `lib/evaluate.sh:44` | `jq -n -L perf/eval … -f eval/<scenario>.jq` over identity, measurements, baseline and prometheus. The summary renders `levels` (:74) and `plateauTable` (:82) tables when present. |
| Evaluator shape | `eval/smoke.jq` (64 lines) is the minimal model | `include "common"`, build `[check(id; class; status; value; limit; detail), …]`, return `result($id; $m; $checks)` (`common.jq:710`). `result` prepends `common_checks` (`:704`): identity, noRestart, adminEvents, pgssComplete, k6, droppedIterations, warmup, dirtyTree, sweeps. The verdict precedence is `common.jq:9-26`. Advisories go through `advisory_guarded` (`:521`). |
| Mandatory parts of any run | `common.jq:396` (`warmup_check`), `:469` (`pgss_complete_check`), `:669` (`sweeps_check`) | Every scenario needs a warm-up record and at least one `pg_stat_statements` window, or its common checks are INVALID. |
| Periodic sweeps | `common.jq:560-686`, `sql/pgss-window.sql` (`sweepCalls`) | Any signal-janitor or retention statement inside a measured window, or any such pass predicted inside it, is **INVALID**. Passes are predicted from each node's `jvmStartMillis`, `reportedAtMillis` and reported intervals. This model rejects S6 outright, and also S2, whose restarted nodes each run a startup sweep. |
| Statement classification | `sql/s3-components.sql` | It already tells apart `lockAcquire`, `instanceRead`, `recoveryPage`, `heldKeys`, `lockRenewAll`, `leaderElection` and `lockCleaner` by full statement text. S2 reuses it. |
| Self-tests | `eval/selftest.sh` (1,245 lines, 474 cases, run 2026-11-17: all hold, 35 s) | It doctors a real result's measurements one fault at a time and asserts the verdict (`evaluate_with` :34, `expect` :52). `eval/selftest-evidence.sh` rebuilds its three source runs from `perf/.evidence/wp11/`. `lib/evidence-selftest.sh` rejects any path or host name in committed evidence. CI runs all three on PRs (`perf.yml:94-112`). |
| Baselines | `lib/baseline.sh` | `--record-baseline` refuses a scenario without a baseline gate. That is the intended outcome for all four new scenarios (MD-10). |
| CI | `perf.yml:58` (choices), `:122` (the schedule matrix is `s1-throughput`, `s3-parked`), `:153-160` (a scheduled INVALID is a warning) | — |
| Older engine | `docs/test-plan.md:391-402` | The recipe cherry-picks every `^test(perf)` commit. It was proven once onto `cbf5a2a` (`r20261117t002705z` smoke PASS, `test-plan.md:383`). |

## 2. What each scenario needs that the harness lacks

| Need | S2 | S4 | S5 | S6 | Provided by |
|---|---|---|---|---|---|
| Stop and start one named node mid-run, with its new JVM recorded and judged | ✔ | | | ✔ | Task 1: `perf/lib/topology.sh`, plus topology-aware `validity.identity` / `validity.noRestart` |
| Sweeps model that allows the startup sweep of a planned start, and a declared sweep window | ✔ | | | ✔ | Task 1: `sweeps_check` extension |
| Send k6 load to a subset of nodes | ✔ | ✔ | | ✔ | Task 1: `K6_NODES` override in `k6_run` |
| Sample `pg_stat_activity` at 10 Hz from inside Postgres (the harness's `dc exec` costs ≈ 100 ms per call) | | | ✔ | ✔ | Task 1: `perf_harness.sample_activity` procedure |
| A generic per-phase table in `summary.md` | ✔ | ✔ | ✔ | ✔ | Task 1: `measurements.phaseTable` rendering |
| 5,000 orphaned `RUNNING` rows with valid history | ✔ | | | | Task 3: clone a consistent mid-run snapshot by SQL (MD-4) |
| Readiness timing per node start | ✔ | | | | Task 3: poll `/actuator/health/readiness` (exists by default; above) |
| A workflow that awaits M signals | | ✔ | | | Task 2: `type: signals` |
| Read the wake-permit count per node, exactly | | ✔ | | | `maestro_wake_permits` from each node's `/actuator/prometheus` (WP2 gauge; §3) |
| A pool exhausted by the host's own DB use, with the database healthy | | | ✔ | | Task 2: `type: hold`, whose activity runs `SELECT pg_sleep(h)` on the shared `DataSource` (MD-6) |
| Maestro's health component alone | | | ✔ | | Task 2: health group `perf` (MD-7). Boot answers `/actuator/health/maestro` only with `show-components` (`HealthEndpointSupport.java:101`, Boot 4.0.5 sources), which is "never" by default. |
| A large terminal backlog with old `completed_at`, children included | | | | ✔ | Task 6: clone a released park run (instance, events, timers, signal) by SQL |
| A way to trigger the sweeper | | | | ✔ | Task 6: a second node joins after seeding. The sweeper and janitor run once at node start (`RetentionSweeper` `start()` sweeps immediately; run.sh header limit 2), at true defaults (MD-8). |

**Why a pool-hold injector is needed (S5).** WP11's S1 gate re-run `r20261116t232948z` reached 80 starts/s, past the 20-slot saturation point, with `hikari.pendingMax = 0` at every level. Engine statements alone never exhaust the pool. Only host work that holds connections does.

## 3. "Before" feasibility per fix (C4)

Merge commits and bases (`git log --merges --first-parent origin/main`):

| Fix | Merge | Merge base (`^1`) | Engine for the "before" | Engine for the "after" |
|---|---|---|---|---|
| WP2 (3.2) | `c925661b` (PR #54) | `834a9da8` | **`0c710a1e`** (MD-2). It is WP2's own first commit, whose parent is exactly `834a9da8`, and it adds only the `maestro.wake.permits` gauge and `WorkflowExecutor.wakePermitCount()` (`git show --stat 0c710a1e`: 5 files, +69/−13, no behaviour change). The 3.2 fix is the next commit, `3a218eae`. | the WP12 branch (= `main` + WP12) |
| WP4 (3.5a) | `0ca95927` (PR #59) | `bdcc074d` | `bdcc074d` | the WP12 branch |
| WP6 (3.1) | not merged | WP6's branch base is `daf36d34` (the WP5 tip); WP5 is not on `main` either | Before WP6 merges: the WP12 branch merged with the latest `origin/main` (no overlay needed). After it merges: an overlay at `git merge-base <merge>^1 <merge>^2`. | Pre-merge preview: overlay on the `fix/recovery-capacity` tip. Of record: `main` after the merge. |
| WP8 (3.11a/b) | not started | — | The WP12 branch (`main` has no WP8) | Pre-merge preview: overlay on the WP8 branch tip. Of record: `main` after the merge. |

**Proven at planning time (2026-11-17).** In a scratch worktree at `0c710a1e`, the harness tree from `origin/main` was overlaid (`git checkout origin/main -- perf maestro-samples/sample-perf settings.gradle.kts`). Then `./gradlew --no-watch-fs :maestro-samples:sample-perf:compileJava :maestro-samples:sample-perf:bootJar -PperfGitCommit=probe` built a 53 MB jar whose `build-info.properties` carries `build.gitCommit=probe`.

`sample-perf`'s main code reads only these engine APIs: `MaestroProperties` getters for timer, lock TTL, cleanup interval, janitor interval, retention and worker, plus `MaestroClient`, `WorkflowOptions` and core annotations. All of them exist unchanged at `834a9da8` and `bdcc074d` (checked with `git show`). The build configuration differs from `main` only by the `sample-perf` include in `settings.gradle.kts` (`git diff --stat 0c710a1e origin/main -- gradle/ settings.gradle.kts build.gradle.kts`). The migration set is identical from `834a9da8` to `main` (V1–V9, V100, V101), so SQL seeding written against `main`'s columns works on every "before".

**Engine-side additions the scenarios rely on:**

| Addition | Origin | Needed by | On the "before" engine |
|---|---|---|---|
| `maestro.wake.permits` gauge | WP2, `0c710a1e` | S4's gate | present at `0c710a1e`; **absent at `834a9da8`** (hence MD-2) |
| `maestro.taskqueue.slots.used` gauge | WP11 | S2's hoard gate | present: S2's before and after both postdate WP11 |
| `EngineObserver.workflowFinished` / `maestro.workflow.duration` | WP11 | nothing gated; latency comes from instance rows | absent at `0c710a1e` / `bdcc074d`: the advisory reads "not available" (WP11's convention) |
| `maestro.loop.errors` | WP4 | nothing | absent at `bdcc074d` |
| `maestro.run.abandoned` | WP11 | nothing gated; S5 uses Hikari's `hikaricp_connections_timeout_total` instead | absent at `bdcc074d` |
| Due-timer query text | 3.7 (WP2) changed it | the background model | `common.jq:135` already matches both forms |

**So no shim code is needed.** The smallest honest alternative to MD-2 is to run S4's "before" at `834a9da8` and label the permit count **not measured**; heap growth of about 0.6 MB for 4,600 permits is below heap noise.

---

## 4. The scenarios

Common to all four scenarios:
- every run is cold, warms up (`warm_up`), measures an idle background window (`measure_background`) and has at least one pgss window;
- ids are `${RUN_ID}-<phase>-…`, and every SQL read is scoped to `${RUN_ID}-`;
- each run ends with every replica up, so `collect_nodes` works;
- every check named below is added to `result(...)`'s scenario list.

The predictions are **arithmetic from the code, not measurements**. Each is labelled with the build it applies to.

### 4.1 `s2-recovery-drain` (3.1, WP6)

**Topology.** `--replicas 2`, which `run.sh` requires exactly. C = `workerDefaultConcurrency` from `/perf/info` (20).

**Workload.** `chain`, `activities 2`, `activityMillis d`, with d = 2000 ms in both profiles (MD-11). N = 5,000 per phase. Probes are `chain(1 × d)` at 0.2 starts/s.

**Seeding (MD-4): a consistent mid-run snapshot, cloned by SQL.**
1. Start one template run `${RUN_ID}-tmpl-0` on node 1.
2. Poll every 200 ms (as postgres) until its events hold exactly one `ACTIVITY_COMPLETED` while its status is `RUNNING`.
3. Copy the instance row and all its events into `perf_harness.s2_tmpl_instance` / `s2_tmpl_event` in one `REPEATABLE READ` transaction.
4. Let the template finish.
5. Stop both nodes gracefully.
6. Insert N clones per phase with `jsonb_populate_record(NULL::maestro_workflow_instance, to_jsonb(t) || jsonb_build_object(…))`, which copies every column the schema has and overrides only these:
   - `id` and `run_id` (`gen_random_uuid()`);
   - `workflow_id` (`${RUN_ID}-s2<phase>-<i>`);
   - `started_at` / `updated_at` = template `started_at` + i ms, which keeps keyset order;
   - `completed_at`, `output` = NULL;
   - `status` `RUNNING`.

   Events are cloned the same way with a new `id` and the clone's `workflow_instance_id`.

Each clone is then exactly a run whose node died after step 0 completed and before step 1's outcome: recovery replays step 0 from the memo, with its `args_hash`, and runs step 1 live (d).

**Fidelity limits** (stated in the scenario header and in `docs/test-plan.md`):
- no instance-lock rows: the crashed node's locks have expired, which a real backlog reaches 30 s after the crash;
- one history shape, every run identical;
- no `signals` / `timers` rows;
- the clones never passed through `startWorkflow` (no orphaned-signal adoption).

**Phases:**
1. **Setup.** Stack up with 2 replicas; warm-up on both; idle background (2 nodes); template and snapshot. Then `node_stop 1`, `node_stop 2` (graceful, `docker stop -t 40`).
2. **Phase A (one node).** Seed prefix `s2a` (N rows); `validity.seeded`; pgss reset; record DB `now()` as `t0A`; `node_start 1`. Poll readiness every 1 s (`/actuator/health/readiness`). Probes go to node 1 only (`K6_NODES`), from the node's first HTTP answer until the drain ends. Wait until every `s2a` row is terminal (timeout 1,800 s). Close the window (`pgss-window` + `s3-components` + `s2-recovery.sql`), sample Prometheus, then `node_stop 1`.
3. **Phase B (rolling start).** Seed `s2b`; pgss reset; `t0B`; `node_start 1`. When node 1's readiness reads `UP`, wait 5 s, then `node_start 2` (MD-5). Probes go round-robin over the nodes that are up. Drain, then close the window.
4. **Phase C (`full` only, C2).** `node_stop 2`. Seed 600 rows of `s2c`. Start node 1, and during the drain offer fresh `chain(1 × d)` starts at 1.5 × C/d = 15/s for 120 s. Record recovered completions per minute. End with `node_start 2`, so every replica is up.

**Measurements.**
- Per phase:
  - drain time on the DB clock (`max(completed_at)` of the phase rows − `t0`);
  - per node: `jvmStartMillis`, readiness-UP time and the readiness delay;
  - per node: adopted (`increase(maestro_recovery_adopted_total)`) and completions;
  - per node, every 5 s: hoard = `maestro_workflows_running` − `maestro_workflows_parked` − `maestro_taskqueue_slots_used{kind="workflow"}` (max);
  - probe start→complete p50/p95 (instance rows);
  - component calls: `lockAcquire`, `recoveryPage`, `heldKeys` from `s3-components.sql`, plus `adoptCas` (the CAS `UPDATE maestro_workflow_instance … lease_epoch` text, in the new `s2-recovery.sql`).
- The initial empty-database readiness delay of both nodes, for comparison.

**Checks** (`eval/s2-recovery-drain.jq`):

| id | class | rule |
|---|---|---|
| `validity.template` | validity | The snapshot was `RUNNING` with exactly one `ACTIVITY_COMPLETED` and k ≥ 1 events. The template run ended `COMPLETED`. |
| `validity.seeded` | validity | Per phase, right before the node starts: exactly N phase rows, all `RUNNING`; exactly N × k event rows; 0 lock rows for them. |
| `validity.rollingStart` | validity | Phase B: node 2 started after node 1's readiness `UP` (both recorded) and within 15 s of it. |
| `validity.recoveryClassifier` | validity | Recovery statements (below) ≥ 1.5 per seeded run in every phase. Below that, the statement texts did not match: INVALID, never a vacuous PASS. |
| `validity.hoardSeries` | validity | The running, parked and slot series exist for every node during every drain. Without them the hoard gate is INVALID, never PASS. |
| `correctness.allRecovered` | correctness | Every phase row is `COMPLETED` with output `2`, none `FAILED`, none open, within the timeout. Every probe is `COMPLETED`. |
| `gate.twoNodeDrain` (3.1) | gate | drainB / drainA ≤ 0.65 |
| `gate.noHoard` (3.1) | gate | Max hoard per node in phases A and B ≤ C + 2 (2 = sampling slack between the three gauges) |
| `gate.freshStartsNotStarved` (3.1) | gate | Phase A probe p95 ≤ 0.25 × drainA (a within-run ratio with a wide bound) |
| `gate.recoveryStatements` | gate | (`lockAcquire` − probe starts + `adoptCas` + `recoveryPage` + `heldKeys`) / N ≤ 2.5 in phases A and B. This is WP6's ≤ 3.5 without `getInstance`, whose text the run's own reads share. |
| `advisory.readiness` | advisory | Delay over the empty-DB start, per phase start |
| `advisory.adoptionShare` | advisory | Per-node adoption share in B |
| `advisory.decomposition` | advisory | The component calls per seeded run |
| `advisory.c2Stream` | advisory | `full`: recovered completions in every minute of phase C > 0 → PASS, else WARN. `local`: "not measured". |

**Predictions** (N = 5,000, d = 2 s, C = 20, JVM boot ≈ 15 s):

| Figure | Before (WP6 merge base) | After (WP6) |
|---|---|---|
| drainA | ≈ 15 + 500 = **515 s** (+ the startup pass, which overlaps the drain) | ≈ **515 s** (± 3 %: 20 queued tickets keep the slots busy across 1 s capacity passes) |
| drainB | ≈ **515–540 s**: node 1 adopts all 5,000 before readiness, node 2 adopts 0 | ≈ **275–290 s**: node 1 alone ≈ 20 s at 10 runs/s, then 20 runs/s |
| `gate.twoNodeDrain` | ≈ 1.0 → **FAIL** | ≈ 0.53–0.57 → **PASS** |
| Adoption share in B (node 1 / node 2) | 5,000 / 0 | ≈ 2,600 / 2,400 |
| Max hoard per node | ≈ **4,980** (node 1) → **FAIL** | ≤ **20** → **PASS** |
| Probe p95 / drainA | ≈ 0.85–0.95: a fresh start queues behind the hoard → **FAIL** | ≈ 2–3 d / 515 ≈ **0.01** → **PASS** |
| Recovery statements per run | ≈ 2.0 + (startup 25 pages + 25 heldKeys + ≈ 8 full passes × 25 pages) / 5,000 ≈ **2.05** (B ≈ 2.1) → PASS | ≈ 2.0 + ≤ 2 statements/s/node of capacity passes ≈ **2.1–2.25** → PASS |
| Readiness delay over empty start | ≈ **+8–25 s** (5,000 adoptions on the runner thread; WP6's probe measured 4.7 ms per adoption) | ≈ **0 ± 3 s** |

**INVALID when** any of the following holds:
- a validity check above fails;
- a common check fails: an unplanned restart, a node not running the stamped jar, or a sweep outside the planned-start allowance;
- k6 dropped probe iterations.

### 4.2 `s4-signals` (3.2, WP2)

**Topology.** `--replicas N` with N ≥ 2 (`local` 2, `full` 3). `run.sh` refuses 1: no cross-node phase is possible.

**Workload.** The new `signals` type: one step, then `awaitSignal("perf.s<k>", String.class, parkSeconds)` for k = 0..M−1, then one step; it returns M. The signal names are distinct per k, so each delivery is a distinct `(workflowId, signalName)` key. Sleep runs are `park` / `sleep`, `parkSeconds 20`.

**Phases** (permit counts are read from every node's `/actuator/prometheus`, `maestro_wake_permits`, at P0 to P3; Prometheus lags by a scrape):
1. **Setup.** Warm-up; idle background; read P0.
2. **Local phase.** Start W runs with `start-rate.js` (run n on `nodeFor(n)`). Wait until all are `WAITING_SIGNAL`. pgss reset. `signal-rate.js` sends signal i to workflow `i mod W`, name `perf.s<⌊i/W⌋>`, **on the run's own node**, at the profile's levels. Wait until every run is `COMPLETED`. Close the window. Read P1.
3. **Cross phase.** The same, with a fresh prefix, and each signal sent to `nodeFor(n + 1)`: never the owner. Read P2.
4. **Timer phase.** Start W_s sleep runs round-robin, and wait until all are `COMPLETED`: their timers fire on the timer-poller leader, and the other owners wake by re-check. Read P3.

**Checks** (`eval/s4-signals.jq`):

| id | class | rule |
|---|---|---|
| `validity.replicas` | validity | ≥ 2 distinct nodes answered |
| `validity.permitGauge` | validity | `maestro_wake_permits` present on every node at P0–P3. Absent is INVALID, never 0. |
| `correctness.allSignalsConsumed` | correctness | Every accepted signal has exactly one row, consumed. Every `signals` run is `COMPLETED` with output M; every sleep run `COMPLETED` `"slept"`. |
| `gate.noStrayPermits` (3.2) | gate | Σ over nodes of P1−P0, P2−P1 and P3−P2 are each 0. |
| `gate.crossNodeStatementParity` | gate | Net engine statements per consumed signal: \|cross − local\| ≤ the net tolerance + 0.05 × local. A guard that the cross-node wake adds no per-signal store work. |
| `advisory.permitArithmetic` | advisory | Observed deltas next to the prediction: cross = accepted cross signals; timer = W_s × (N−1)/N. |
| `advisory.consumeLatency` | advisory | p50/p95 of signal `received_at` → its `SIGNAL_RECEIVED` event `created_at`, per phase and level. Cross/local p95 ≤ 1.5 → PASS, else WARN. (Task 5 Step 1 confirms which event column names the signal; if none does, the figure is per run: last signal → `completed_at`.) |
| `advisory.throughput` | advisory | Consumed per second against offered, per level |

**Profiles.**
- `local`: W = 450, M = 10, levels 50/s and 100/s × 30 s (4,500 signals per phase), W_s = 200.
- `full`: N = 3, W = 1,500, M = 10, levels 50, 100, 200 and 400/s × 30 s (W × M = 22,500 ≥ 750 × 30 = 22,500), W_s = 600.

**Predictions** (`local`, N = 2):

| Figure | Before (`0c710a1e`) | After (WP12 branch) |
|---|---|---|
| P1 − P0 (local delivery) | 0 | 0 |
| P2 − P1 (cross delivery) | **4,500**, ≈ 2,250 per node: the non-owner stores a permit for every key; `PostgresWakeListener.dispatch` drops notifications with no local subscriber (Audit B 3.2(a)), so the owner stores none | **0** |
| P3 − P2 (timer leader) | **100** (= 200 × 1/2) | **0** |
| `gate.noStrayPermits` | **FAIL** (4,600) | **PASS** |
| Statements per signal, cross − local | ≈ 0 → PASS | ≈ 0 → PASS |
| Statements per signal, before − after (advisory) | +0.4: the activity lock, 3.10, removed by the same WP2 = 2 activities × 2 statements / M | — |
| Cross/local consume p95 | ≈ 1.0–1.2 | ≈ 1.0–1.2 |

### 4.3 `s5-pool-health` (3.5a, WP4)

**Topology.** `--replicas 1` (default).

**Workload.** The new `hold` type: K activities. Each runs `SELECT pg_sleep(h)` through the application's own `DataSource` (Hikari, maximum 10). The wake listener holds one connection for the app's lifetime (`PostgresWakeListener.java:38`), so 9 remain.

**Phases:**
1. **Setup.** Warm-up; idle background.
2. **Idle health.** 30 s with `health-probe.js` at 1/s. Each iteration GETs `/actuator/health/perf` (5 s client timeout) and `/actuator/health` (5 s). Counters record maestro status (`UP`/`UNKNOWN`/`DOWN`/no answer), the `store` detail and the root status.
3. **Exhaust.**
   - Start the activity sampler (`pattern 'pg_sleep'`, 10 Hz).
   - pgss reset.
   - Start H = 10 `hold` runs (`start-batch.js`).
   - Wait until the sampler shows ≥ `hikariMaximumPoolSize − 1` maestro backends in `pg_sleep` (≤ 30 s, else INVALID).
   - Run `health-probe.js` for the exhausted duration.
   - Wait until all hold runs are terminal; close the window.
4. **Recovered health.** 30 s.

Hikari's `hikaricp_connections_timeout_total` and `_creation_seconds_count` are read from the node scrape at the start and the end.

**Why waits stay below Hikari's 30 s `connectionTimeout`.** Hikari hands a returned connection to a waiting thread. With 9 connections, each held for h = 8 s, and about 12 waiters (the 10th holder, outcome writes, pollers, the renewer and the probe), the worst wait is ≈ ⌈12/9⌉ × 8 ≈ 16 s.

Three consequences:
- no `SQLTransientConnectionException`;
- every instance-lock renewal completes within its 30 s TTL (one renewal delayed ≤ 16 s);
- the renewer's failure streak stays 0, so the post-WP4 health cannot read `DOWN` from the renewer.

`validity.noPoolTimeouts` proves this design held.

**Checks** (`eval/s5-pool-health.jq`):

| id | class | rule |
|---|---|---|
| `validity.poolExhausted` | validity | In ≥ 80 % of the exhausted-phase samples, ≥ `hikariMax − 1` maestro backends are active in `pg_sleep`, and Prometheus `max_over_time(hikaricp_connections_pending)` ≥ 1. Otherwise the run never exhausted the pool, and a PASS would be vacuous. |
| `validity.storeReachable` | validity | The sampler (role `postgres`, its own session) shows no gap > 1 s across all phases: the database itself stayed reachable. |
| `validity.noPoolTimeouts` | validity | Hikari timeout counter increase = 0: the pool was busy, not failing. |
| `validity.healthSamples` | validity | Each phase has ≥ 0.8 × its planned samples. |
| `correctness.upWhenIdle` | correctness | Idle and recovered phases: 100 % maestro `UP` |
| `correctness.holdsCompleted` | correctness | All H hold runs `COMPLETED`, none `FAILED` |
| `correctness.noLoopFault` | correctness | No `DOWN` answer carries a loop or renewer fault in its details |
| `gate.notDownWhenBusy` (3.5a) | gate | Exhausted phase: 0 maestro `DOWN` answers, and 0 with no answer within 5 s |
| `advisory.rootHealth` | advisory | Root aggregate outcome distribution in the exhausted phase (see MD-7) |
| `advisory.answerTime` | advisory | Maestro group answer time p50/max per phase |
| `advisory.connectionChurn` | advisory | Hikari connection-creation increase (Issue 66: an interrupted probe can close a pooled connection) |

**Profiles.**
- `local`: H = 10, K = 5, h = 8 s (≈ 10 × 5 × 8 / 9 ≈ 44 s exhausted), exhausted probe window 35 s.
- `full`: K = 10 (≈ 89 s), window 75 s.

**Predictions:**

| Figure | Before (`bdcc074d`) | After (WP12 branch) |
|---|---|---|
| Maestro answers in the exhausted window | ≈ **100 % `DOWN`** (`store: "timed out"`, HTTP 503; `MaestroHealthIndicator.java:143`, `:190-191` at `bdcc074d`), each ≈ 2.0 s → **FAIL** | **0 `DOWN`**, ≈ 100 % `UNKNOWN` (HTTP 200; `MaestroHealthIndicator.java:241-243` on `main`). The first answer takes ≈ 2 s; while the single in-flight probe stays timed out, later answers are immediate → **PASS** |
| Idle / recovered | 100 % `UP` | 100 % `UP` |
| Root aggregate, exhausted | Mostly **no answer within 5 s**, on both builds: Boot's `db` indicator (`DataSourceHealthIndicator`, through `JdbcTemplate` on the same pool) waits up to the ≈ 8–16 s pool wait | same |
| Hikari timeouts | 0 | 0 |

### 4.4 `s6-retention` (3.11a, 3.11b, WP8)

**Topology.** `--replicas 2`, required. Node 2 is stopped right after the stack is up and identified. It is started again after seeding, and its startup sweep is the one measured (MD-8). Foreground probes run on node 1 only, which stays warm throughout.

**Template.** One `park` run (`signal` mode, `parkSeconds 7200`), released by `perf.release`, and so `COMPLETED`. It has events, its cancelled await-timeout timer row, and one consumed signal row. Instance, events, timers and signal are cloned by `jsonb_populate_record`, as in S2.

**Seed sets** (prefix `${RUN_ID}-s6-`):

| Set | Status, age (`completed_at` before now) | Service | Signals | Expected outcome |
|---|---|---|---|---|
| R-C | `COMPLETED`, 40 d | `perf` | consumed | purged (up to the 100-batch cap) |
| R-T | `TERMINATED`, 40 d | `perf` | consumed | purged |
| R-F | `FAILED`, 100 d | `perf` | consumed | purged |
| Young | `COMPLETED`, 1 d (1,000) | `perf` | consumed | kept |
| J-own | `COMPLETED`, 10 d | `perf` | 1 unconsumed each | instance kept; signal deleted by the janitor |
| J-failed | `FAILED`, 10 d (1,000) | `perf` | 1 unconsumed each | kept (the janitor takes only `COMPLETED` / `TERMINATED`) |
| J-foreign | `COMPLETED`, 10 d | `perf-foreign` | 1 unconsumed each | before: deleted; after WP8: kept (3.11b) |
| X-foreign | `COMPLETED`, 40 d (1,000) | `perf-foreign` | consumed | **observation**: purged on both builds; retention is not service-scoped (`AbstractJdbcWorkflowStore.java:324-329`) |

Profiles:
- `local`: R-C 51,000, R-T 5,000, R-F 5,000, J-own and J-foreign 10,000 each;
- `full`: R-C, R-T and R-F 50,000 each (300 batches, 3.11a's worst case), J-own and J-foreign 50,000 each.

**Phases:**
1. **Setup.** Warm-up and idle background on both nodes, then `node_stop 2`.
2. **Seed.** Seed the sets; `validity.seeded`.
3. **Foreground.** Start `start-rate.js` on node 1: `chain(3 × 20 ms)` at 5/s for `pre + sweepAllowance + post` (local 60 + 180 + 60 s).
4. **Trigger.** At pre's end:
   - start the sampler (patterns: the purge candidates `SELECT id FROM maestro_workflow_instance WHERE status = $1 AND completed_at < `, and the four batched `DELETE … WHERE workflow_instance_id IN (` / `WHERE id IN (` statements);
   - pgss reset;
   - `node_start 2`.
5. **Sweep end.** Detected when the R sets reach their expected remainder (polled every 2 s) and node 2's `maestro_retention_sweeps_total` has moved.
6. **Close.** Close the sweep window (`sweepWindow: true`). The post window runs to the k6 end. Read the counts.

The pre, during (sampler's first to last active sample) and post windows are assigned after the fact by each probe's `started_at`.

**Checks** (`eval/s6-retention.jq`):

| id | class | rule |
|---|---|---|
| `validity.seeded` | validity | Exact counts per set, and children per instance = the template's |
| `validity.sweepObserved` | validity | Node 2's `maestro_retention_sweeps_total` +1, node 1's unchanged, and ≥ 20 active sampler samples. Otherwise there was no sweep, or it ran on node 1, and the duty-cycle gate would be meaningless. |
| `validity.windows` | validity | Pre and post windows ≥ 30 s each; the sweep ended within the allowance |
| `correctness.retentionExact` | correctness | R-C purged = min(seeded, 100 × 500), and the rest of R-C remains; R-T and R-F fully purged; Young, J-* and foreground rows untouched; zero child rows without an instance |
| `correctness.janitorExact` | correctness | J-own's unconsumed signals deleted; J-failed's and every consumed signal kept |
| `gate.retentionPaced` (3.11a) | gate | Busy fraction (samples with a purge statement active / samples in the sweep span) ≤ 0.65 |
| `gate.janitorServiceScoped` (3.11b) | gate | J-foreign's unconsumed signals all kept |
| `gate.janitorBatched` (3.11b) | gate | Janitor `DELETE` calls ≥ 2 and rows per call (pgss `rows / calls`) ≤ J-own / 2. This assumes WP8's batch is < 5,000 (local); Task 9 re-checks it against WP8's constant. |
| `advisory.foreground` | advisory | Probe p50/p95 during/pre and post/pre (≤ 1.5 → PASS, else WARN) |
| `advisory.dutyCrossCheck` | advisory | pgss Σ `total_exec_time` of purge statements / sweep span, next to the sampler fraction |
| `advisory.foreignRetention` | advisory | X-foreign purged count, with the prediction |
| `advisory.walBytes` | advisory | `pg_stat_wal.wal_bytes` delta over the sweep |

**Predictions** (`local`):

| Figure | Before (WP12 branch, no WP8) | After (WP8) |
|---|---|---|
| Purge batches | 100 (R-C, capped: 1,000 remain) + 10 + 1 short (R-T) + 10 + 1 short (R-F). 6 statements per batch: leader upsert, candidates, 4 deletes (`RetentionSweeper.java:222-259`) | same rows and statements; plus WP8's pause |
| Busy fraction | ≈ **0.85–0.95**: batches back to back, only commit and leader-upsert gaps → **FAIL** | ≈ **0.45–0.55**: pause = last batch's time → **PASS** |
| Janitor | **1** `DELETE` removing **20,000** rows (J-own + J-foreign; `AbstractJdbcWorkflowStore.java:1650-1660`, no service filter, no limit) → both janitor gates **FAIL** | J-foreign kept (**PASS**); J-own in ⌈10,000 / B⌉ ≥ 2 calls (**PASS**) |
| X-foreign | purged (1,000) | purged (1,000): WP8 does not scope retention |
| Foreground during/pre p95 | ≈ 1.0–1.5. One extra busy backend on an unconstrained laptop Postgres. The 3.11a harm (a connection and the database saturated for the whole sweep) shows as the busy fraction, not as foreground latency, on this harness. | ≈ 1.0–1.3 |

---

## 5. Run time, profiles and CI

The figures below include the fixed overhead, ≈ 1.5 min of cached build, compose up, warm-up and idle window: WP11 smoke `r20261116t230707z` took 14 s to the JVM start and 11 s to UP. Start every local or full run with `nohup caffeinate -i -m -s perf/run.sh … > <log> 2>&1 &` and watch it with bounded polls.

| Scenario | `local` (before / after) | `full` | Weekly schedule (local profile) |
|---|---|---|---|
| `s2-recovery-drain` | ≈ 20 / 16 min | + phase C ≈ 6 min | **After WP6 merges** (Task 8). Two nodes at 20 runs/s each, 2 s sleeps, is CPU-light on a 4-vCPU runner. Before WP6 it FAILs by design. |
| `s4-signals` | ≈ 10 min | ≈ 20 min, 3 replicas | **No**: 2–3 nodes at 100–400 signals/s on 4 vCPUs is an INVALID factory (dropped iterations) |
| `s5-pool-health` | ≈ 6 min | ≈ 8 min | **Yes, from merge**: deterministic and runner-speed independent; the exhaustion is built by holds, not by load |
| `s6-retention` | ≈ 8 min | ≈ 15 min | **After WP8 merges**, decided then (Task 9). Seeding 300k rows on a runner disk is fine; the gates need WP8. |

The CI matrix runs scheduled jobs in parallel, each under `timeout-minutes: 90`, so adding S5 (and later S2) does not lengthen the S1 or S3 jobs. Each scenario sets its replica count itself (`run.sh`'s per-scenario rule below), so the scheduled job needs no replicas input.

## 6. Sequencing against WP6 and WP8

- **Buildable now, in this order:** Task 1 (shared harness), Task 2 (`sample-perf`), **Task 3 (S2) first among the scenarios**, then Task 6 (S6), Task 4 (S5) and Task 5 (S4).
- **Must run before WP6 merges** (Task 3 Step 8): S2's "before" on the WP12 branch merged with the latest `origin/main`. If WP6 merges first, it is not lost: run it on an overlay at WP6's merge base (§3). Still in Task 3: the S2 "after" **preview** on an overlay of the `fix/recovery-capacity` tip, handed to WP6's coordinator as merge evidence. It is evidence only; the coordinator does not have to wait for it.
- **Must run before WP8 merges** (Task 6 Step 8): S6's "before" on the WP12 branch. WP8 has not started, so this is comfortable.
- **After WP6 merges** (Task 8): the S2 "after" of record on `main` (with WP12 merged, or an overlay); S2 joins the schedule.
- **When WP8 is code-complete and after it merges** (Task 9): the S6 "after" preview on an overlay, then the run of record. If WP8 changed the janitor's statement text, `perf/sql/pgss-window.sql`'s `signalJanitor` pattern and S6's janitor classifier are updated in the same change (hand-off below).
- **Any time:** S4 and S5 befores (overlays at `0c710a1e`, `bdcc074d`) and afters (the WP12 branch).
- **WP12's own PR** merges after Task 7, with S2's before and preview, S4's and S5's before/after, and S6's before. Tasks 8 and 9 land as small follow-up PRs, or Task 9 inside WP8's PR. A merged S2 or S6 FAILs on `main` until its fix lands; neither is scheduled before then (MD-3).

## 7. Maintainer decisions

| # | Question | Recommendation | Alternative and its cost |
|---|---|---|---|
| MD-1 | How the "before" gets the harness (C4 says cherry-pick) | **Overlay**: on a detached worktree at the before engine, `git checkout <H> -- perf maestro-samples/sample-perf settings.gradle.kts`, after checking `git diff <base> <H> -- settings.gradle.kts` is only the `sample-perf` include. Commit once, locally; never push. Assert `git diff <H> HEAD -- perf maestro-samples/sample-perf` is empty. The harness tree is then provably identical to the after's, and `PERF_ENGINE_REF` names `<H>`. | Cherry-pick every `^test(perf)` commit (≈ 70 by now, from a branch with merges). It worked once onto `cbf5a2a`, but it is slower, can conflict, and proves tree equality only indirectly. |
| MD-2 | The WP2 "before" engine | **`0c710a1e`**: the merge base `834a9da8` plus WP2's first commit, which adds only the gauge. The permit count is then exact. | `834a9da8` literally: the permit count is **not measured** (no gauge, heap noise) and `gate.noStrayPermits` reads INVALID. |
| MD-3 | Fix-linked checks are gates that FAIL on the before engine, and on `main` until the fix merges (S2, S6) | **Gates.** A FAIL verdict with exact counts is the recorded "before". Such a scenario is not scheduled before its fix is on `main`. | Advisory until the fix merges, then a gate: a second edit per scenario, and a "before" that reads PASS. |
| MD-4 | S2 seeding | **SQL clone of a consistent mid-run snapshot**: exact N, valid one-step history, seconds to seed. Fidelity limits stated (§4.1). | Start-then-kill (start 5,000, then `docker kill`). Closest to production, but N is inexact (≈ 10 runs/s complete while seeding), the seed takes minutes, and it needs the 30 s lock expiry. |
| MD-5 | S2 phase B start shape | **Rolling**: node 2 starts when node 1 reads ready (a Kubernetes rolling deploy). The before then hoards deterministically. | Both nodes at once: the before's split depends on which startup pass wins the race (anywhere from 50/50 to 100/0), so it is not a reliable "before". |
| MD-6 | S5 exhaustion injector | **`hold` activities** holding pool connections with `pg_sleep` (a host's own DB work, which is what 3.5a is about) at Boot's default pool of 10. | (a) A harness `LOCK TABLE`: no sample change, but it models a slow database, not a busy pool. (b) A smaller pool: departs from defaults. |
| MD-7 | Which health answer S5 judges | **Maestro's component**, through a management-only group `perf` (`include: maestro`, `show-details: always`). Root `/actuator/health` is recorded as advisory. Boot's own `db` indicator blocks it on an exhausted pool on both builds (prediction), so operations §14's "an `UNKNOWN` `maestro` leaves the aggregate `UP`" is incomplete for hosts with the `db` indicator. **File Issue 75** (docs, operations §14) if the runs confirm it. | Judge the root: the gate would FAIL after the fix for a reason outside Maestro. |
| MD-8 | S6 sweep trigger | **A second node starts after seeding**, so its startup sweep runs: true defaults, and the foreground stays on a warm node. Limit, stated: the sweeping node's own pool is not under foreground load. | (a) A short `maestro.retention.interval` for S6 only: departs from defaults. (b) Restart the single node: cold-JIT confound in the during window. |
| MD-9 | Weekly schedule | **Add `s5-pool-health` now**; `s2-recovery-drain` after WP6 merges; S4 dispatch-only; S6 decided after WP8. | Schedule all four: S4 INVALIDs weekly on 4 vCPUs, and S2/S6 FAIL until their fixes merge. |
| MD-10 | Baselines for the new scenarios | **None.** Within-run gates and arithmetic only; `--record-baseline` refuses them, as it already does for gate-less scenarios. | An S4 per-signal statement baseline: cross-run regression detection, plus a recorded file and settings matching to maintain. |
| MD-11 | S2 activity duration d | **2 s in both profiles** (WP6's figure). drainA ≈ 515 s, and the after ratio ≈ 0.55 sits 0.10 under the 0.65 gate. | d = 1 s halves the run time (≈ 12 min), but the after ratio rises to ≈ 0.57–0.62 (JVM boot is a larger share), too close to the gate on a contended laptop. |

New issue numbers, filed only if the runs confirm them:
- **75**: Boot's `db` health indicator blocks the aggregate on an exhausted pool (MD-7).
- **76**: retention purges every service's rows with this service's cutoffs (`advisory.foreignRetention`), unless WP8's planner takes it.

## Global Constraints

- **Only these paths:** `perf/**`, `maestro-samples/sample-perf/**`, `.github/workflows/perf.yml`, `docs/test-plan.md` and this plan's Review section. No `maestro-*` module changes, no meters, no `CLAUDE.md` changes. If a step seems to need one, stop and ask.
- **Engine APIs:** `sample-perf` code references no engine API newer than `0c710a1e`. Task 2 proves it by compiling the overlay at `0c710a1e` and `bdcc074d`.
- **Commits:** every harness commit is `test(perf): …`, CI is `ci(perf): …`, docs are `docs(…)`, evidence is `docs(perf): WP12 run evidence …`.
- **Branch:** `test/finding-scenarios` from `origin/main`, in worktree `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/finding-scenarios` (`$WT`). Run `git -C /Users/rakheendama/Projects/2026/maestro status` before the first edit and after every commit; it must stay clean.
- **Process rules:** `./gradlew --no-watch-fs` always. No `git stash`. No two Gradle runs on one module in one tree. Commit at every checkpoint. Pass messages with `-m "$MSG"`.
- **Machine:**
  - long runs under `nohup caffeinate -i -m -s …`;
  - one Docker suite at a time: the perf lock covers only perf runs, so before any `local`/`full` run, confirm with a read-only `docker ps` that no Testcontainers suite (`testcontainers-ryuk-*`) is live, and wait if one is;
  - never `pkill`/`killall`, never stop a Gradle daemon;
  - touch only `maestro-perf` project containers, by exact name (`maestro-perf-node-<i>`), never by pattern.
- **Throwaway worktrees:** before-engine overlays live in `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp12-<fix>-<role>`, detached, never pushed. Each is removed with `git worktree remove` (its own call) once its `result.json` is copied.
- **Evidence:** committed as run: `perf/.evidence/wp12/<runId>.result.json` (plus `.prometheus.json` where a self-test needs it). `perf/lib/evidence-selftest.sh` must pass: no path, no host name. Every result identifies itself:
  - `identity.repo.engineCommit` and `harnessCommit`;
  - `PERF_ENGINE_REF="<WPn> <before|after|preview>: engine <sha> (<what it is>); harness <H>"`, which must contain no path.
- **D4:** no absolute-latency gate anywhere. Every gate is a count, or a ratio of two quantities measured in the same run.
- **Kafka-delivered signals:** out of scope. Signals go over REST.

## Review Focus

1. **A planned restart hides an unplanned one.** `validity.noRestart` becomes topology-aware. It is INVALID when any container's final JVM is not the last JVM the topology log recorded for it. Pinned in Task 1 (self-test: a doctored `jvmStartMillis` on a container with a planned restart → INVALID).
2. **A fix-linked gate passes vacuously.** Each one has a validity guard that makes "nothing measured" INVALID:
   - `validity.permitGauge` (no series is not 0);
   - `validity.poolExhausted` (no exhaustion is not health);
   - `validity.recoveryClassifier` and `validity.hoardSeries` (no text match or series is not 0);
   - `validity.sweepObserved` (no sweep is not pacing).

   Each pinned by a self-test case.
3. **Before and after ran different harnesses.** Each overlay asserts tree equality with `<H>` (MD-1), and both runs name `<H>`.
4. **The sweeps model is weakened for every scenario.** The extension only adds allowances for JVMs started inside a window and for windows flagged `sweepWindow`. The existing S1, S3 and smoke self-test cases must still hold, unchanged (Task 1 Step 6).
5. **S5 exhausts a failing pool rather than a busy one.** `validity.noPoolTimeouts` and `validity.storeReachable`.

---

## File structure

```
perf/run.sh                                   (modify: scenario list, usage, per-scenario replica rule)
perf/lib/common.sh                            (modify: K6_NODES override in k6_run)
perf/lib/topology.sh                          (create: node_stop/node_start/node_wait_ready, topology log)
perf/lib/evaluate.sh                          (modify: render measurements.phaseTable)
perf/eval/common.jq                           (modify: topology-aware identity/noRestart; sweeps allowances)
perf/eval/selftest.sh                         (modify: common topology/sweeps cases only)
perf/eval/selftest-wp12.sh                    (create: S2/S4/S5/S6 cases)
perf/eval/selftest-evidence.sh                (modify: also run selftest-wp12.sh on perf/.evidence/wp12/)
perf/eval/s2-recovery-drain.jq  s4-signals.jq  s5-pool-health.jq  s6-retention.jq   (create)
perf/scenarios/s2-recovery-drain.sh  s4-signals.sh  s5-pool-health.sh  s6-retention.sh  (create)
perf/k6/signal-rate.js  perf/k6/health-probe.js                                       (create)
perf/sql/activity-sampler.sql  activity-samples.sql                                   (create)
perf/sql/s2-template.sql  s2-seed.sql  s2-status.sql  s2-recovery.sql                 (create)
perf/sql/s4-status.sql                                                                (create)
perf/sql/s6-template.sql  s6-seed.sql  s6-status.sql                                  (create)
perf/prometheus/queries.txt                   (modify: + adopted rate, recovery deferred, hikari timeout, retention purged)
perf/.evidence/wp12/*.json                    (runs)
maestro-samples/sample-perf/…/workflow/PerfHoldWorkflow.java  HoldInput.java  PerfSignalsWorkflow.java  SignalsInput.java
maestro-samples/sample-perf/…/activity/PerfDbActivities.java  JdbcPerfDbActivities.java
maestro-samples/sample-perf/…/web/StartRequest.java  StartRequestValidator.java  PerfController.java   (modify)
maestro-samples/sample-perf/src/main/resources/application.yml   (modify: health group perf)
maestro-samples/sample-perf/src/test/…  PerfWorkflowsTest  StartRequestValidatorTest  PerfControllerTest (new methods)
.github/workflows/perf.yml  docs/test-plan.md
```

---

### Task 1: Shared harness: planned topology, topology-aware common checks, sampler, node override

**Files:** `perf/lib/topology.sh`, `perf/lib/common.sh`, `perf/run.sh`, `perf/lib/evaluate.sh`, `perf/eval/common.jq`, `perf/eval/selftest.sh`, `perf/sql/activity-sampler.sql`, `perf/sql/activity-samples.sql`, `perf/prometheus/queries.txt`.

**Interfaces (used by Tasks 3–6):**
- **`topology.sh`** (sourced by `run.sh` after `common.sh`):
  - `node_stop <i>` runs `docker stop -t 40 maestro-perf-node-<i>`.
  - `node_start <i>` runs `docker start maestro-perf-node-<i>`, waits ≤ 180 s for its `/perf/info`, and prints the new JVM record (the `/perf/info` JSON + `container`).
  - `node_wait_ready <i> <timeout>` prints the epoch at which `/actuator/health/readiness` first answered `UP`.
  - Each stop or start appends `{at, dbNow, action, container, jvm}` to `${RESULTS}/topology.jsonl`. `topology_records` prints it as a JSON array. Scenarios put it in `measurements.topology`.
- **`k6_run`**: when `K6_NODES` is set and non-empty, `PERF_NODES` is that value instead of `node_urls`. Each k6 record gains `nodes`.
- **`run.sh`**:
  - the `case` and the usage list the four names;
  - when `--replicas` is not given, `s2-recovery-drain` and `s6-retention` default to 2 and `s4-signals` to 2;
  - `s2`/`s6` refuse any replica count other than 2, and `s4` refuses 1 (`die`, exit 3, before anything is touched).
- **Sampler:**
  - `activity-sampler.sql` creates `SCHEMA perf_harness`, table `perf_harness.activity_sample(at timestamptz, label text, matching int, maestro_active int)` and `PROCEDURE perf_harness.sample_activity(secs int, label text, pattern text)`. The procedure loops: insert one row (counting maestro-role backends with `state = 'active' AND query ~ pattern`), `COMMIT`, `pg_sleep(0.1)`, until `secs` have passed.
  - A scenario launches it in the background with `dc exec -T postgres psql -U postgres -d maestro -c "CALL perf_harness.sample_activity(…)" &` and records the pid.
  - `activity-samples.sql -v label=…` returns `{samples, firstAt, lastAt, maxGapSecs, matchingSamples, firstMatchAt, lastMatchAt, matchingInSpan, spanSamples}`.
  - It runs as postgres, so it is never counted as engine load.
- **`common.jq`**:
  - `identity_check` also covers every JVM in `$m.topology[].jvm`;
  - `no_restart_check` uses, per container, the last JVM in the topology log (the initial one if none) as the expected final JVM;
  - `sweeps_problems_and_value` builds its node list from every JVM, each with lifetime `[start, stop or run end]`, and predicts passes only within lifetimes;
  - a window may carry `plannedStarts` (the JVM starts inside it). Each one allows 1 signal-janitor call and 3 retention calls (an empty-backlog sweep: one candidates select per status);
  - a window flagged `sweepWindow: true` is exempt from the janitor and retention upper bounds. The lock-cleaner rule stays.
- **`evaluate.sh`**: renders `.measurements.phaseTable` (an array of flat objects) as a markdown table under "## Phases".

- [ ] **Step 0: Create the worktree.** `git -C /Users/rakheendama/Projects/2026/maestro fetch origin && git -C /Users/rakheendama/Projects/2026/maestro worktree add -b test/finding-scenarios /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/finding-scenarios origin/main`. Both trees clean.

- [ ] **Step 1: Write the failing self-test cases** in `perf/eval/selftest.sh`, doctoring the smoke source run:
  - `topology: planned restart, same commit` → PASS (append a topology start for `maestro-perf-node-1` with a new `jvmStartMillis`, and set `nodesAfter[0]` to it);
  - `topology: planned restart, foreign commit` → INVALID `validity.identity`;
  - `topology: unplanned restart` (`nodesAfter[0].jvmStartMillis` changed, no topology record) → INVALID `validity.noRestart`, exactly as today;
  - `topology: final JVM is not the last recorded` → INVALID `validity.noRestart`;
  - `sweeps: one retention call in a window with no planned start` → INVALID `validity.sweeps`, as today;
  - `sweeps: 3 retention + 1 janitor calls in a window holding one planned start` → PASS;
  - `sweeps: 4 retention calls with one planned start` → INVALID;
  - `sweeps: sweepWindow with 600 retention calls` → PASS;
  - `sweeps: lock-cleaner passes of a JVM stopped before the window` are not predicted (PASS with 0 calls).

  Run `perf/eval/selftest-evidence.sh`. Expected: the new cases FAIL, and all 474 old ones still hold.

- [ ] **Step 2: Implement** `common.jq`, `topology.sh`, `common.sh`, `run.sh`, `evaluate.sh`, the sampler SQL and the `queries.txt` lines:
  - `sum by (instance) (increase(maestro_recovery_adopted_total[1m]))`
  - `maestro_recovery_deferred`
  - `sum by (instance) (increase(hikaricp_connections_timeout_total[1m]))`
  - `sum by (kind) (increase(maestro_retention_purged_total[1m]))`

  Re-run the self-tests. Expected: all cases hold.

- [ ] **Step 3: Lint.** `docker run --rm -v "$PWD/perf:/mnt" koalaman/shellcheck:v0.10.0 /mnt/run.sh /mnt/lib/*.sh /mnt/eval/*.sh`. Expected: no findings.

- [ ] **Step 4: Sampler dry run, without a node.** Bring up only Postgres with `PERF_KEEP_UP` unset. The run lock and project rule apply, so use `docker compose -p maestro-perf -f perf/compose.yml up -d postgres` only when no `maestro-perf` container exists, and `down -v` afterwards. Load `activity-sampler.sql`, `CALL perf_harness.sample_activity(3, 'dry', 'pg_sleep')` while a second psql (role `postgres`) runs `SELECT pg_sleep(2)`.
  - Expected: `samples` ≈ 30, `maxGapSecs` < 0.5, `matching` = 0 (the sleep is not role `maestro`).
  - Repeat with the pattern counting role `postgres`, in the test only. Expected: `matchingSamples` ≈ 20.

  This shows the snapshot is fresh per sample.

- [ ] **Step 5: Regression smoke.** `perf/run.sh smoke`. Expected: `VERDICT PASS`, and `measurements.topology` absent, with identical rules. `perf/run.sh s2-recovery-drain --replicas 1`. Expected: exit 3, "s2-recovery-drain needs exactly 2 replicas", nothing touched.

- [ ] **Step 6: Commit** `test(perf): planned node stops and starts, topology-aware identity and sweep checks, in-database activity sampler`. `git show --stat HEAD`: only `perf/` paths.

---

### Task 2: `sample-perf`: `hold` and `signals` workflows, health group `perf`

**Interfaces (wire contract):**

| `type` | Body knobs (defaults) | Validation (exact error text) | Workflow |
|---|---|---|---|
| `hold` | `activities` (1), `holdMillis` (1000) | `activities must be 1..50`; `holdMillis must be 0..20000` | `@DurableWorkflow(name = "perf-hold") PerfHoldWorkflow.run(HoldInput)`: `db.hold(i, holdMillis)` for i < activities; returns activities. `@Activity interface PerfDbActivities { int hold(int index, long millis); }`; `@Component JdbcPerfDbActivities` runs `SELECT pg_sleep(?)` (seconds = millis / 1000.0) with a `JdbcTemplate` on the application `DataSource` (no `@Transactional`); stub `@ActivityStub(startToCloseTimeout = "PT30S")`. |
| `signals` | `signals` (5), `parkSeconds` (7200) | `signals must be 1..50`; `parkSeconds must be 1..86400` | `@DurableWorkflow(name = "perf-signals") PerfSignalsWorkflow.run(SignalsInput)`: `step(0, 0)`; for k < signals, `awaitSignal("perf.s" + k, String.class, Duration.ofSeconds(parkSeconds))`; a `SignalTimeoutException` returns −1 at once; `step(1, 0)`; returns signals. |

The `type` error becomes `type must be chain, park, hold or signals`.

`application.yml` gains, under `management.endpoint.health.group.perf`, `include: maestro` and `show-details: always`, with a comment: S5 reads Maestro's component alone; the root endpoint is unchanged (show-details never), so `run.sh`'s UP grep stays valid.

- [ ] **Step 1: Failing tests.**
  - `StartRequestValidatorTest`: one case per rule above, plus defaults applied.
  - `PerfWorkflowsTest` (`TestWorkflowEnvironment`):
    - `holdRunsEveryActivity` (a recording fake `PerfDbActivities`; 3 calls with indices 0..2, result 3);
    - `signalsConsumesEachNameInOrder` (deliver `perf.s0..perf.s4`, result 5);
    - `signalsArrivingEarlyAreConsumed` (all five delivered before the run's first park, result 5);
    - `signalsTimesOut` (`parkSeconds 1` plus a clock advance, result −1).
  - `PerfControllerTest`: `hold` and `signals` starts answer 202; a bad knob answers 400 with the text.

  Run `./gradlew --no-watch-fs :maestro-samples:sample-perf:test`. Expected: compile FAIL.

- [ ] **Step 2: Implement.** Same command. Expected: PASS. Then `./gradlew --no-watch-fs verifyModuleTestCoverage`. Expected: SUCCESS.

- [ ] **Step 3: Older engines compile it (MD-1 feasibility).** For each `B` in `0c710a1e` and `bdcc074d`:
  - `git -C /Users/rakheendama/Projects/2026/maestro worktree add --detach /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp12-compile-$B $B`;
  - in it, `git checkout <this branch's HEAD> -- perf maestro-samples/sample-perf settings.gradle.kts`, then `./gradlew --no-watch-fs :maestro-samples:sample-perf:bootJar -PperfGitCommit=probe`;
  - expected: BUILD SUCCESSFUL, and `unzip -p …/sample-perf.jar META-INF/build-info.properties | grep -x 'build.gitCommit=probe'`;
  - remove the worktree (`git worktree remove --force` is acceptable here: it holds only build output and the uncommitted overlay).

  At planning time the same check passed at `0c710a1e` with the WP11 harness.

- [ ] **Step 4: Health group check (one smoke).** `PERF_KEEP_UP=1 perf/run.sh smoke`, then:
  - `docker compose -p maestro-perf -f perf/compose.yml --profile tools exec -T curl curl -s http://maestro-perf-node-1:8080/actuator/health/perf`. Expected: `{"status":"UP","components":{"maestro":{"status":"UP","details":{"store":"reachable",…}}}}`;
  - `/actuator/health` (root). Expected: exactly `{"status":"UP"}`, with no `components` key;
  - `/actuator/health/readiness`. Expected: `UP`.

  Then `docker compose -p maestro-perf -f perf/compose.yml --profile tools down -v`.

- [ ] **Step 5: Commit** `test(perf): sample-perf hold (host DB work on the shared pool) and signals (M distinct awaits) workflows; health group perf`. `git add maestro-samples/sample-perf` only.

---

### Task 3: Scenario 2, `s2-recovery-drain` (before before WP6 merges, plus the after preview)

**Files:** `perf/scenarios/s2-recovery-drain.sh`, `perf/eval/s2-recovery-drain.jq`, `perf/sql/s2-template.sql`, `s2-seed.sql`, `s2-status.sql`, `s2-recovery.sql`, `perf/eval/selftest-wp12.sh` (S2 section).

**Definition.** §4.1. Profiles:

| | N | d | Probes | Phases | Drain timeout per phase |
|---|---|---|---|---|---|
| `local` | 5,000 | 2,000 ms | 0.2/s | A, B | 1,800 s |
| `full` | 5,000 | 2,000 ms | 0.2/s | A, B, C (600 rows; stream 15/s for 120 s) | 1,800 s |

`s2-seed.sql` takes `-v phase=… -v n=…` and refuses (`RAISE EXCEPTION`) when the snapshot tables are missing or any node container is up. The script checks the latter itself, through `docker ps --filter name=^maestro-perf-node-`.

`s2-status.sql` returns, per phase prefix:
- `{total, byStatus, outputs, events, lockRows, firstStartedAt, lastCompletedAt}`;
- `dbNow`.

`s2-recovery.sql` returns `{adoptCas: {calls}}` for text `^UPDATE maestro_workflow_instance SET .*lease_epoch = lease_epoch \+ 1`. Step 1 confirms that text against the engine's SQL, and any other form found.

- [ ] **Step 1: Confirm the adoption CAS text.** Read `AbstractJdbcWorkflowStore`'s adopt/updateInstance SQL on the WP12 branch **and** on `fix/recovery-capacity`; WP6 does not touch the store, so they should match. Write the regex into `s2-recovery.sql`'s header with its file:line. Expected: one text. If the two differ, the regex matches both, and the header lists both.
- [ ] **Step 2: Write the scenario, the SQL and a stub evaluator.** The stub returns `result($id; $m; [check("correctness.s2"; "correctness"; "ERROR"; null; null; "evaluator not written")])`. Shellcheck clean.
- [ ] **Step 3: Seeding dry check.** `PERF_KEEP_UP=1 PERF_S2_STOP_AFTER=seed perf/run.sh s2-recovery-drain`. `PERF_S2_STOP_AFTER` is a test-only hook: it exits after phase A's `validity.seeded` read, writing measurements. Then:
  - `jq '.measurements.phases[0].seeded'`. Expected: total = 5000, `byStatus.RUNNING` = 5000, events = 5000 × k with k ≥ 1, `lockRows` = 0.
  - `jq '.measurements.template'`: status `RUNNING`, exactly one `ACTIVITY_COMPLETED`.
  - Tear down.
- [ ] **Step 4: First full local run = the WP6 "before".** Merge the latest `origin/main` into the branch first, and confirm `git log origin/main --oneline | grep -c 'fix/recovery-capacity'` is 0 (WP6 not merged). Then `PERF_ENGINE_REF="WP6 before: engine = main without WP6; harness $(git rev-parse --short HEAD)" nohup caffeinate -i -m -s perf/run.sh s2-recovery-drain > perf/results/launch-s2-before.log 2>&1 &`, polled until `^VERDICT`. Expected: `VERDICT ERROR` (stub), with a complete `measurements.json`. Copy `result.json` to `perf/.evidence/wp12/`.
- [ ] **Step 5: Failing self-test cases** (`selftest-wp12.sh`, S2 source = Step 4's run). The source is a real "before":
  - unchanged → FAIL, with `gate.twoNodeDrain`, `gate.noHoard` and `gate.freshStartsNotStarved` FAIL and `gate.recoveryStatements` PASS. **If the source's numbers contradict §4.1's before predictions, stop and report before writing cases.**
  - Doctored to after-shape (drainB = 0.55 × drainA, hoard max 18, probe p95 = 5 s) → PASS.
  - `recoveryPage`, `heldKeys` and `adoptCas` set to 0 and `lockAcquire` to the probe count → INVALID `validity.recoveryClassifier`.
  - Slot series removed → INVALID `validity.hoardSeries`.
  - One seeded row `FAILED` → FAIL `correctness.allRecovered`.
  - Phase B with node 2 started before node 1's readiness → INVALID `validity.rollingStart`.
  - `seeded.lockRows` = 3 → INVALID `validity.seeded`.
  - drainB / drainA = 0.66 → FAIL; 0.65 → PASS (the boundary).
  - Recovery statements 2.51 → FAIL; 2.5 → PASS.
  - `full` without phase C → `advisory.c2Stream` WARN; `local` → PASS "not measured".

  Run it. Expected: every case fails (stub).
- [ ] **Step 6: Implement the evaluator.** Run the cases. Expected: all hold. Re-evaluate Step 4's run (`bash perf/lib/evaluate.sh <dir> s2-recovery-drain ""`). Expected: **FAIL**, with the three fix-linked gates FAIL. Its figures go in the Review table next to the predictions.
- [ ] **Step 7: After preview on WP6.**
  - Overlay worktree at the `fix/recovery-capacity` tip (MD-1 steps; tree-equality assert).
  - `PERF_ENGINE_REF="WP6 preview: engine fix/recovery-capacity <sha>; harness <H>" nohup caffeinate -i -m -s perf/run.sh s2-recovery-drain …`.
  - Expected: PASS, with every gate PASS.
  - Copy the evidence and remove the worktree. Send the result (verdict, the gate values, runId) to WP6's coordinator.
- [ ] **Step 8: Commit** `test(perf): scenario 2 — 5,000-run recovery drain, one node vs a rolling two-node start` and, separately, `docs(perf): WP12 run evidence — S2 before and WP6 preview`.

---

### Task 4: Scenario 5, `s5-pool-health` (after on the WP12 branch, before at `bdcc074d`)

**Files:** `perf/scenarios/s5-pool-health.sh`, `perf/eval/s5-pool-health.jq`, `perf/k6/health-probe.js`, S5 cases in `selftest-wp12.sh`.

**`health-probe.js`:**
- `constant-arrival-rate`, 1/s, `PERF_DURATION`, `preAllocatedVUs 4`;
- each iteration GETs `${node}/actuator/health/perf` and `${node}/actuator/health`, each with `timeout: '5s'` and tags `name: perf|root`;
- built-in `k6/metrics` Counters `maestro_up`, `maestro_unknown`, `maestro_down_store_timed_out`, `maestro_down_store_unreachable`, `maestro_down_other`, `maestro_no_answer` and `root_up`, `root_down`, `root_other`, `root_no_answer`, plus Trends `maestro_answer_ms` and `root_answer_ms`;
- `discardResponseBodies: false`;
- advisory thresholds only.

`k6_run`'s summary already records each phase. The scenario adds a jq read of these counters from `k6-<phase>.json`.

- [ ] **Step 1: Write the k6 script, the scenario and a stub evaluator.** `k6 inspect` the script (exit 0). Shellcheck clean.
- [ ] **Step 2: First run = the WP4 "after".** `PERF_ENGINE_REF="WP4 after: engine = WP12 branch; harness <H>" perf/run.sh s5-pool-health` (foreground, `timeout 590`). Expected: `VERDICT ERROR` (stub), complete measurements, and the exhausted phase's sampler `matchingSamples / spanSamples ≥ 0.8`. Copy the evidence.
- [ ] **Step 3: The WP4 "before" on an overlay at `bdcc074d`.** Label `"WP4 before: merge base bdcc074d; harness <H>"`. Expected: ERROR (stub), with the k6 counters showing `maestro_down_store_timed_out` ≈ the exhausted sample count. Copy the evidence and remove the worktree.
- [ ] **Step 4: Failing cases.**
  - After source unchanged → PASS.
  - Before source → FAIL (`gate.notDownWhenBusy`).
  - Exhausted `matchingSamples` at 50 % → INVALID `validity.poolExhausted`.
  - Hikari timeout increase 1 → INVALID `validity.noPoolTimeouts`.
  - Sampler `maxGapSecs` 2.5 → INVALID `validity.storeReachable`.
  - One idle-phase `UNKNOWN` → FAIL `correctness.upWhenIdle`.
  - One `maestro_down_other` → FAIL `correctness.noLoopFault`.
  - One `maestro_no_answer` in the exhausted phase → FAIL `gate.notDownWhenBusy`.
  - A missing `root_*` counter → `advisory.rootHealth` WARN, never ERROR.

  Expected: all fail (stub).
- [ ] **Step 5: Implement the evaluator.** Cases hold. Re-evaluate both runs. Expected: after **PASS**, before **FAIL**. If the after root aggregate shows no answer or `DOWN` in the exhausted window, draft Issue 75 for the coordinator (MD-7).
- [ ] **Step 6: Commit** the test(perf) and evidence commits.

---

### Task 5: Scenario 4, `s4-signals` (after on the WP12 branch, before at `0c710a1e`)

**Files:** `perf/scenarios/s4-signals.sh`, `perf/eval/s4-signals.jq`, `perf/k6/signal-rate.js`, `perf/sql/s4-status.sql`, S4 cases.

**`signal-rate.js`:**
- `ramping-arrival-rate` over the profile's levels (as `start-ramp.js`), `PERF_W`, `PERF_M`;
- iteration i signals workflow `PREFIX + (i mod W)` with name `perf.s${Math.floor(i / W)}`, and stops at W × M;
- routing `PERF_ROUTE=own|next`: `nodeFor(n)` or `nodeFor(n + 1)`;
- writes `k6-<phase>-plan.json` with `{w, m, route, nodes, crossNode}`.

- [ ] **Step 1: Confirm the signal column.** Read how `SIGNAL_RECEIVED` rows record the signal name (`step_name` or `payload`) in `AbstractJdbcWorkflowStore` / `SignalManager`. Record it with its file:line in `s4-status.sql`'s header. If no column carries it, latency is per run (see `advisory.consumeLatency`).
- [ ] **Step 2: Write the script, the scenario, the SQL and a stub evaluator.** `k6 inspect` the script. Shellcheck clean.
- [ ] **Step 3: First run = the WP2 "after".** `perf/run.sh s4-signals --replicas 2`, detached with caffeinate. Expected: ERROR (stub), with the permit reads P0–P3 all 0 on both nodes. Copy the evidence.
- [ ] **Step 4: The WP2 "before" on an overlay at `0c710a1e`** (MD-2), label `"WP2 before: engine 0c710a1e (merge base 834a9da8 + gauge-only commit); harness <H>"`. Expected: ERROR (stub), with P2 − P1 = the accepted cross signals and P3 − P2 = W_s / 2, exactly or within the count of signals k6 did not get accepted. Copy the evidence and remove the worktree.
- [ ] **Step 5: Failing cases.**
  - After source → PASS; before source → FAIL `gate.noStrayPermits`.
  - One node's `maestro_wake_permits` absent at P2 → INVALID `validity.permitGauge`.
  - `--replicas 1` identity → INVALID `validity.replicas`.
  - One signal row unconsumed → FAIL `correctness.allSignalsConsumed`.
  - Cross net per signal = local × 1.10 + tolerance → FAIL `gate.crossNodeStatementParity`.
  - A dropped iteration in the cross phase → INVALID.
  - Cross p95 = 2 × local → `advisory.consumeLatency` WARN.
- [ ] **Step 6: Implement the evaluator.** Cases hold. Re-evaluate both runs: after **PASS**, before **FAIL**, with the deltas matching §4.2.
- [ ] **Step 7: Commit.**

---

### Task 6: Scenario 6, `s6-retention` (before before WP8 merges)

**Files:** `perf/scenarios/s6-retention.sh`, `perf/eval/s6-retention.jq`, `perf/sql/s6-template.sql`, `s6-seed.sql`, `s6-status.sql`, S6 cases.

- [ ] **Step 1: Write the template, seeding and status SQL.**
  - `s6-seed.sql` takes the set sizes as `-v` variables.
  - `s6-status.sql` returns, per set: instance count, children counts (events, timers, signals consumed/unconsumed) and orphan children (rows whose `workflow_instance_id` has no instance).
  - Dry check with `PERF_KEEP_UP=1 PERF_S6_STOP_AFTER=seed`. Expected: exact counts per set, and seeding time recorded (≤ 120 s local).
- [ ] **Step 2: Write the scenario and a stub evaluator.** The sweep window is flagged `sweepWindow: true`; the pre and post windows carry no flag. Shellcheck clean.
- [ ] **Step 3: First run = the WP8 "before".** `PERF_ENGINE_REF="WP8 before: engine = WP12 branch (main without WP8); harness <H>" perf/run.sh s6-retention`, detached. Expected:
  - ERROR (stub);
  - node 2's retention sweeps +1;
  - R-C remainder = 1,000;
  - J-own and J-foreign unconsumed = 0 (both deleted);
  - janitor pgss calls 1 with rows 20,000;
  - sampler busy fraction ≥ 0.8.

  Copy the evidence.
- [ ] **Step 4: Failing cases.**
  - Before source → FAIL (`gate.retentionPaced`, `gate.janitorServiceScoped`, `gate.janitorBatched`).
  - Doctored after-shape (busy 0.5, J-foreign kept, janitor 10 calls × 1,000 rows) → PASS.
  - Node 2's sweeps +0 → INVALID `validity.sweepObserved`.
  - Young set purged by 1 → FAIL `correctness.retentionExact`.
  - Orphan events 1 → FAIL.
  - Busy 0.66 → FAIL; 0.65 → PASS.
  - Post window 20 s → INVALID `validity.windows`.
  - `X-foreign` read missing → `advisory.foreignRetention` WARN.
- [ ] **Step 5: Implement the evaluator.** Cases hold. Re-evaluate Step 3's run: **FAIL** on exactly the three fix-linked gates. If X-foreign was purged (predicted), draft Issue 76 for the coordinator.
- [ ] **Step 6: Commit** the test(perf) and evidence commits.

---

### Task 7: CI, docs, evidence wiring and the closing verification of WP12's PR

**Files:** `.github/workflows/perf.yml`, `perf/eval/selftest-evidence.sh`, `docs/test-plan.md`.

- **`perf.yml`:**
  - the scenario choices gain the four names;
  - the schedule matrix becomes `["s1-throughput","s3-parked","s5-pool-health"]` (MD-9);
  - the header lists what is hardware-sensitive for the new scenarios: S4's dropped iterations; S2's 1,800 s drain timeout; S5 none.
- **`selftest-evidence.sh`** also runs `selftest-wp12.sh` on the committed WP12 source runs (named constants, as `SMOKE_RUN`/`S1_RUN`/`S3_RUN` are).
- **`docs/test-plan.md` §1 *Load suite*:**
  - the commands table and the profiles table gain the four scenarios;
  - the gate rules gain one paragraph per scenario (the check ids and rules from §4);
  - "Adding a scenario" mentions `topology.sh` and the sampler;
  - "An older engine with this harness" becomes the overlay recipe (MD-1, with the tree-equality assert);
  - a new table, "Finding-linked before/after", is filled from the Review section;
  - the seeding fidelity limits (§4.1 and §4.4) are stated;
  - each `sample-perf` addition gets its one-line justification (the `perf` health group; the `hold` and `signals` types).

- [ ] **Step 1: Write the edits.**
- [ ] **Step 2: Closing verification.**

| Check | Command | Expected |
|---|---|---|
| Evaluator self-tests | `perf/eval/selftest-evidence.sh` | `selftest: all cases hold` (WP11's 474 plus the WP12 cases) |
| Evidence privacy | `perf/lib/evidence-selftest.sh` | no personal detail |
| Baseline record | `perf/lib/baseline-selftest.sh` | all hold |
| Lock race (local shell only) | `perf/lib/lock-selftest.sh` | all hold |
| Shellcheck | `docker run --rm -v "$PWD/perf:/mnt" koalaman/shellcheck:v0.10.0 /mnt/run.sh /mnt/lib/*.sh /mnt/scenarios/*.sh /mnt/eval/*.sh` | no findings |
| actionlint | `docker run --rm -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.7 .github/workflows/perf.yml` | no output; and `grep -nE '^\s*push:' .github/workflows/perf.yml` finds nothing |
| k6 | `k6 inspect` of `signal-rate.js` and `health-probe.js` (through the compose `k6` service) | exit 0 |
| `sample-perf` | `./gradlew --no-watch-fs :maestro-samples:sample-perf:test verifyModuleTestCoverage` | SUCCESS |
| One smoke | `perf/run.sh smoke` | `VERDICT PASS` |
| Commit hygiene | `git log --format=%s origin/main..HEAD` | only `test(perf)`, `ci(perf)`, `docs(` subjects; the `test(perf)` commits touch only `perf/`, `maestro-samples/sample-perf/` |
| Uncached build (**coordinator**, alone on the machine) | `./gradlew --no-watch-fs build --no-build-cache --rerun-tasks` | BUILD SUCCESSFUL |

- [ ] **Step 3: Commit** `ci(perf): the four finding-linked scenarios on dispatch; s5-pool-health weekly`, then `docs(test-plan): finding-linked scenarios, the overlay recipe, before/after table`.

---

### Task 8 (after WP6 merges): S2 "after" of record; S2 joins the schedule

- [ ] **Step 1.** Find the merge with `git log --merges --oneline origin/main | grep recovery-capacity`. Run S2 on `main` with WP12 merged (or on an overlay at the merge commit), labelled `"WP6 after: merge <sha>; harness <H>"`. Expected: PASS, with figures within ±10 % of Task 3 Step 7's preview. If not, report: the preview and the merged engine differ.
- [ ] **Step 2.** If WP6 merged before Task 3 Step 4 ran, run S2's before now on an overlay at the merge base, labelled accordingly.
- [ ] **Step 3.** Add `s2-recovery-drain` to `perf.yml`'s schedule matrix (actionlint clean). Fill the Review row. Commit as `ci(perf)` and `docs(perf)`.

### Task 9 (WP8 code-complete, then merged): S6 "after"

- [ ] **Step 1.** Read WP8's janitor and retention SQL. If the janitor's text no longer starts with `DELETE FROM maestro_workflow_signal WHERE consumed = `, update `perf/sql/pgss-window.sql`'s `signalJanitor` pattern and S6's janitor classifier. Add a self-test case per changed pattern, which must fail before the update. Set `gate.janitorBatched`'s documented assumption to WP8's batch constant (file:line).
- [ ] **Step 2.** Preview on an overlay of the WP8 branch tip, then the run of record on `main` after the merge. Expected: PASS. If WP8 added Issue 39's startup WARN, add `advisory.foreignServiceWarn`: count its log line in `compose.log`; expected 1 for `perf-foreign`.
- [ ] **Step 3.** Fill the Review row. Decide S6's schedule (MD-9). Commit.

---

## Out of scope (with reasons)

- **Kafka-delivered signals** (spec): REST signals already exercise cross-node wake.
- **S2 with both nodes started at once** (MD-5: a race, not a reliable "before").
- **S2 on 3+ nodes.**
- **S2 parked-wake latency during the drain.** The fresh-start probe measures the same FIFO position (WP6 D-2: a woken run re-takes its slot in the same line).
- **S2 under `maestro.recovery.enabled=false`** (WP6's ungated one-shot).
- **A fault-injection negative control for S5** (database killed → `DOWN`). With Hikari's 30 s `connectionTimeout` a dead database first reads `UNKNOWN` after WP4. That trade-off belongs to WP4 and its unit tests (`MaestroHealthIndicatorTest`), not to a load scenario. **Not measured.**
- **S5 on more than one node.**
- **S6:**
  - the sweeping node's own pool under foreground load (MD-8);
  - the foreground impact on a constrained database;
  - the janitor's leader election;
  - the retention sweep's own service scoping, which is recorded as an observation only (Issue 76 candidate).
- **Judging heap per permit (S4)**: below heap noise at these counts. **Not measured.**
- **Absolute throughput or latency gates**, and baselines for the new scenarios (MD-10).

## Hand-offs

- **To WP6's coordinator:**
  - Task 3 Step 7's preview result (runId, gates);
  - S2's predictions in §4.1 restate WP6's own table (`wp06-recovery-capacity.md:1071-1079`), with getInstance excluded from the statement gate: WP6's ≤ 3.5 becomes ≤ 2.5 here, for the reason given.
- **To WP8's planner:**
  - S6's "before" is recorded. S6 gates 3.11a as the DB-side busy fraction of the sweep ≤ 0.65, and 3.11b as foreign signals kept and ≥ 2 janitor calls.
  - Keep the janitor `DELETE`'s leading text, or update `perf/sql/pgss-window.sql`'s `signalJanitor` pattern in WP8's PR (Task 9 Step 1).
  - Consider Issue 76: retention is not service-scoped.

---

## Review (filled by the implementers and the coordinator)

**Machine:** `identity.host` of the cited runs. **Harness commit `<H>`** for every row, unless stated otherwise.

### S2 `s2-recovery-drain` (3.1, WP6)

| Role | Engine commit (what it is) | runId | Verdict | drainA s | drainB s | B/A | max hoard (n1/n2) | probe p95 / drainA | recovery stmts / run (A/B) | readiness Δ s | Prediction met? |
|---|---|---|---|---|---|---|---|---|---|---|---|
| before | | | | | | | | | | | before: ≈1.0, ≈4,980, ≈0.9, ≈2.05 |
| after preview (WP6 branch) | | | | | | | | | | | after: ≈0.55, ≤20, ≈0.01, ≈2.2 |
| after of record (main) | | | | | | | | | | | |

### S4 `s4-signals` (3.2, WP2)

| Role | Engine commit | runId | Replicas | Verdict | P1−P0 | P2−P1 | P3−P2 | stmts/signal local / cross | consume p95 local / cross ms | Prediction met? |
|---|---|---|---|---|---|---|---|---|---|---|
| before | `0c710a1e` (merge base `834a9da8` + gauge) | | 2 | | | | | | | 0 / 4,500 / 100 |
| after | | | 2 | | | | | | | 0 / 0 / 0 |

### S5 `s5-pool-health` (3.5a, WP4)

| Role | Engine commit | runId | Verdict | exhausted samples | maestro DOWN / UNKNOWN / UP / no answer | root outcomes (exhausted) | pool sleepers ≥ max−1 % | Hikari timeouts | Prediction met? |
|---|---|---|---|---|---|---|---|---|---|
| before | `bdcc074d` | | | | | | | | ≈100 % DOWN |
| after | | | | | | | | | 0 DOWN, ≈100 % UNKNOWN |

### S6 `s6-retention` (3.11a, 3.11b, WP8)

| Role | Engine commit | runId | Verdict | batches | busy fraction | janitor calls / rows per call | J-foreign kept | X-foreign purged | foreground p95 during/pre | Prediction met? |
|---|---|---|---|---|---|---|---|---|---|---|
| before | | | | | | | | | | ≈0.9, 1 / 20,000, 0 kept |
| after preview (WP8 branch) | | | | | | | | | | ≈0.5, ≥2 / ≤5,000, all kept |
| after of record (main) | | | | | | | | | | |

**MD rulings:** MD-1 … MD-11.

**Issues filed:** 75 / 76, or "not filed: <reason>".

**Gates:** Task 7 Step 2's table with log identities. The coordinator's uncached build.

**Post-merge:** one `Perf (k6)` dispatch of `s5-pool-health` (run URL); the first scheduled run including S5.
