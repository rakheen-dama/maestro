# Research D — grounding for k6 performance/load tests in Maestro

Read-only research on `main` @ `cbf5a2a` (2026-11-12). Nothing was modified.
All `file:line` anchors are against that commit. k6 facts come from context7
(`/websites/grafana_k6`, `/grafana/k6`), the GitHub releases API and the
GitHub runner docs, fetched 2026-11-12.

**Scope note first.** `docs/test-plan.md:502` lists "performance/load testing"
as explicitly out of scope. Adding k6 is a scope change; that line, and
`docs/operations.md` §13's "*Arithmetic, not measurement*" disclaimer
(`docs/operations.md:1146-1151`), are what a first perf cycle would replace.

---

## 1. What exists today

### 1.1 Inventory

| Suite | Entry point | Load generation | What it measures / asserts | CI trigger | Duration (observed) |
|---|---|---|---|---|---|
| **Build & Test** (unit + `@Tag("integration")`) | `./gradlew build` (`.github/workflows/build-test.yml:31-32`); `e2e` tag excluded by `build-logic/src/main/kotlin/maestro.integration-test-conventions.gradle.kts:17-22` | None: functional tests only. `TaskQueueConcurrencyIT` checks `concurrency = 1` semantics with 2 workflows (`maestro-integration-tests/.../engine/TaskQueueConcurrencyIT.java:1-36`) | Correctness | push/PR to `main` | 30 min timeout (`build-test.yml:16`) |
| **Multi-node ITs** (`MultiNode*IT`: fencing, lock contention, owner death, signal routing, timer wake, retention purge, per-run query) | `maestro-integration-tests/src/test/java/.../multinode/` | In-JVM engines, a handful of workflows each | Correctness of the multi-node contracts | Every PR (part of `build`) | Minutes |
| **Loan E2E, scenarios 1-10** | `maestro-samples/sample-loan-origination/e2e/run-e2e.sh` (2,079 lines), job `e2e` (`.github/workflows/e2e-nightly.yml:43-79`) | `curl` against the three services as host JVMs, one loan per scenario (`run-e2e.sh:315-338`); `E2E_CLUSTER=1` adds a second node per service (`run-e2e.sh:68-86`) | Scripted single-fault scenarios: kill -9, owner-kill adoption, rolling restart, timer-leader failover, cross-node admin (`run-e2e.sh:7-60`). Asserts status/event-log integrity per scenario. Timing waits are generous bounds (`run-e2e.sh:134-137`), not SLIs | Nightly 02:30 UTC + `workflow_dispatch` (`e2e-nightly.yml:18-29`) | ~10 min (run 43142092073: 02:32→02:42) |
| **Chaos PR-gate** | `ChaosPrGateE2EIT` (`maestro-integration-tests/.../e2e/chaos/ChaosPrGateE2EIT.java:48-57`), `./gradlew :maestro-integration-tests:e2eTest --rerun-tasks` ×3 (`e2e-nightly.yml:109-114`) | Testcontainers 6-node loan cluster (2 per service, `eclipse-temurin:25-jre`, `-Xmx256m`; `ChaosCluster.java:72-75, 332`). Java `WorkloadDriver`: Poisson arrivals, one virtual thread per loan, HTTP + JDBC effect checks (`WorkloadDriver.java:26-38, 157-178, 691-738`), **12 workflows/min for 6 min** (`ChaosConfig.java:68-71`) ≈ 72 loans | Store invariants I1-I5 after heal + 240 s drain (`InvariantChecker.java:132-195`; `ChaosConfig.java:94`); failure = any violation (`ChaosRun.java:50-53`). Side-effect census recorded, not failing | Nightly (3× consecutive) | ~33 min for 3 runs (run 43142092073) |
| **Chaos soak** | `ChaosSoakE2EIT` (`ChaosSoakE2EIT.java:27-39`), `-Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120` (`e2e-nightly.yml:150-153`) | Same driver, **20 workflows/min for 120 min** (`ChaosConfig.java:60-63`) ≈ 2,400 loans (run of record: 2,376, `docs/multi-instance-test-plan.md:12-14`); then a **benchmark tail**: no chaos, **6/min for 300 s at 6 nodes, then 300 s at 3 nodes** (`ChaosRun.java:193-198, 375-408`) | Same invariants; plus `metrics.csv` every 15 s (below) and `benchmark-tail.json` | Weekly Sunday 01:00 UTC + `workflow_dispatch` (`e2e-nightly.yml:28, 131-133`) | ~2 h 17 min (run 43082784284: 18:58→21:15) |
| **Demo stack** (not a test) | `demo/docker-compose.yml`, `demo/scripts/*.sh` | Manual, one loan per `drive-loan.sh` call | Prometheus (5 s scrape) + Grafana dashboard + Jaeger (`demo/docker-compose.yml:149-226`, `demo/prometheus.yml:14-36`) | None | Manual |

No JMH, Gatling, JMeter or k6 exists anywhere in the repo (grep for
`jmh|k6|gatling|jmeter` outside build output finds only an unrelated plan file).

### 1.2 What the soak's "metrics" actually are

`MetricsSampler` (`maestro-integration-tests/.../chaos/MetricsSampler.java:15-41`)
writes one row per 15 s window with:
`windowStartUtc,windowSecs,liveNodes,running,waitingSignal,waitingTimer,compensating,recoveryCalls,recoveryRatePerSec,lockProbeCalls,lockRenewCalls,lockHeldKeysCalls,leaderElectionCalls,listenCalls,notifyCalls,chaosActive`
— status counts from `maestro_workflow_instance` and **statement call counts
from `pg_stat_statements`** matched by normalised SQL text
(`MetricsSampler.java:54-83`; Postgres started with
`shared_preload_libraries=pg_stat_statements`, `track_utility=on`,
`ChaosCluster.java:162-166`). Plus test-JVM heap samples. It never scrapes the
nodes' `/actuator/prometheus`, records no latency, no throughput, no pool
state. The driver records back-pressure (its in-flight semaphore,
`max(60, 15×rate)` permits, `WorkloadDriver.java:115-116`) only to mark a
benchmark window "NOT comparable" (`ChaosRun.java:427-432`).

### 1.3 Performance questions they already answer

- **Correctness under load-with-chaos** at ~0.2-0.33 workflows/s across 6 nodes.
- **Issue 12 trend, vs node count:** recovery-query rate is linear in node
  count (~0.0167 calls/s per node at 6 and 3 nodes) and lock traffic tracks
  parked backlog — but that benchmark *predates* batched renewal and the paged
  scan and "has not been re-run since" (`docs/operations.md:437-446`;
  `docs/open-issues.md:1072-1076`).
- **Statement-count shapes** for recovery/lock/LISTEN/NOTIFY, per 15 s.

### 1.4 What they cannot answer

1. **Any capacity number.** The highest arrival rate ever driven is 20/min.
   No suite searches for a throughput knee, so "20/20" is untested as a
   *performance* choice (handover §6.3: "a judgement, not a measurement",
   `tasks/handover-2026-11-12-after-host-safety-cycle.md:157-158`).
2. **The shipped defaults are never under load.** Every sample lists its task
   queue with `concurrency: 5` (`loan-application-service/.../application.yml:81-84`,
   `verification-gateway-service/.../application.yml:83-85`,
   `underwriting-service/.../application.yml:87-89`, `sample-order-service/.../application.yml:27-30`),
   so the 20-workflow default applies to none of them (activity slots do
   default to 20: `MaestroProperties.java:481`). Every loan service sets
   `wake-recheck-interval: 5s` instead of the 30 s default
   (`loan-application-service/.../application.yml:70-71`); the chaos cluster
   sets 5 s too (`ChaosConfig.java:98`, `ChaosCluster.java:326`). No sample
   sizes Hikari, so each node runs Boot's default pool (10) under 20 activity
   slots — the opposite of §13's "≥ 25" advice (`docs/operations.md:1192-1197`).
3. **Retention under load:** the chaos cluster disables the sweeper
   (`ChaosCluster.java:327-331`).
4. **Latency SLIs:** no start→complete, signal→consume, or recovery-drain time
   is measured anywhere.
5. **Recovery drain of a large backlog** (Issue 30): the chaos backlog is tens
   of workflows, not thousands.
6. **Parked-population scaling** beyond the soak's natural spread; the §13
   per-parked cost (2P–3P reads / 30 s, `docs/operations.md:1163`) is
   arithmetic only.
7. **Health under pool pressure** (handover 3.5) and **wake-permit leak**
   growth (handover 3.2): no test drives either.

---

## 2. HTTP surface a k6 script can drive today

### 2.1 Per sample

**sample-loan-origination** (ports 8091/8092/8093; actuator `health,info,prometheus,metrics`, `loan-application-service/.../application.yml:16-20`; Prometheus registry in all three, e.g. `loan-application-service/build.gradle.kts:14-17`)

| Method + path | Body | Effect | Source |
|---|---|---|---|
| `POST /applications` | `{"applicationId","borrowerIds":[..],"amount","income","propertyValue","requiredDocs":[..]}` | `startAsync` of `loan-{id}`; **202** with `{applicationId}` | `LoanApplicationController.java:47-68` |
| `POST /applications/{id}/documents` | `{"docType","uploadedBy"}`, optional `Idempotency-Key` header | `deliverSignal("document.uploaded")`; 202 | `:79-89` |
| `POST /applications/{id}/sign` | `{"signerId"}`, optional `Idempotency-Key` | signal `package.signed`; 202 | `:91-100` |
| `POST /applications/{id}/withdraw` | `{"reason"}`, optional `Idempotency-Key` | signal `application.withdrawn`; 202 | `:102-111` |
| `GET /applications/{id}` | — | status + output read **from the store**, 404 if absent | `:120-128` |
| `POST /underwriting/{loanId}/rounds/{n}/decision` (and `/senior-decision`) | `{"verdict":"APPROVED|REJECTED|CONDITIONS","conditions":[..]}` | signal into `underwriting-{loanId}-round{n}`; 202 | `UnderwritingController.java:40-57, 65-73` |
| `GET /underwriting/pending` | — | node-local, best-effort list (`docs/operations.md:414-421`) | `:60-63` |
| `POST /webhooks/{type}/{loanId}` (verification-gateway) | `{"approved","details"}` | verification webhook | `VerificationWebhookController.java:66`; `run-e2e.sh:336-338` |

A loan with DTI < 3.0 auto-approves (no underwriter call): create, upload each
required doc, sign (`demo/scripts/drive-loan.sh:12-15, 148-160`). It still
crosses three services and four Kafka topics, runs verification child
workflows with simulated provider latency, and has a 1 s withdrawal gate. That
makes it realistic but a poor microbenchmark: many moving parts, and HTTP
latency is dominated by none of them.

**sample-order-service** (8081) + **sample-payment-gateway** (8082)

| Method + path | Body | Source |
|---|---|---|
| `POST /orders` | `PlaceOrderRequest` (customerId, items, paymentMethod, shippingAddress); server picks the id; 202 `{orderId}` | `OrderController.java:33-52` |
| `GET /orders/{id}/status` | — ; store status + a `@QueryMethod` that answers only on the owning node | `OrderController.java:61-78` |

The flow is fully automatic (payment over Kafka, result back via
`@MaestroSignalListener`), but the payment simulator throws on **30 %** of
calls and sleeps 100-500 ms (`SimulatedPaymentProviderActivities.java:37-47`),
which injects noise by design. Neither service depends on actuator (only
`webmvc` + jdbc + kafka: `sample-order-service/build.gradle.kts:7-19`), so
there is **no `/actuator/prometheus`** — no Maestro meters to scrape. The
payment gateway has no HTTP controller at all.

**maestro-admin** (8090): Thymeleaf/HTMX HTML (`DashboardController.java:59-174`),
fed by lifecycle events into its own database, so it lags. `POST
/admin/workflows/{id}/signal` (form params `signalName`, `payload`) publishes a
signal **to Kafka** (`AdminActionController.java:97-115`, via
`AdminCommandService`'s `KafkaTemplate`). It always redirects and turns errors
into a flash message, so k6 cannot tell success from failure.

### 2.2 What the HTTP path does and does not exercise

- **Signals over REST bypass Kafka.** `WorkflowHandle.signal` calls
  `executor.deliverSignal` directly (`WorkflowHandle.java:87-88`): persist the
  row, then cross-node `NOTIFY`. So k6 over HTTP *does* exercise cross-replica
  wake (LISTEN/NOTIFY) and signal dedup (`Idempotency-Key`), but not the Kafka
  signal channel (`maestro.signals.<service>` consumer, redelivery/DLT).
- **HTTP 202 hides the queue.** `startAsync` returns once the instance row is
  written; the run takes its task-queue slot later, on its own thread
  (`WorkflowExecutor.java:2359-2362`). `http_req_duration` therefore measures
  the instance insert, not slot wait or execution. Queueing behind 20/20 is
  invisible to k6's built-in metrics.

### 2.3 What is missing

1. **Completion without per-id polling.** No endpoint lists or counts
   workflows (there is no list API on `MaestroClient` at all; handover §6.4).
   Per-id `GET` polling at load adds store reads to the system under test and
   costs k6 VUs. Options: an aggregate endpoint in a perf app (`GET
   /perf/stats?tag=` returning counts by status + latency percentiles from
   `started_at`/`completed_at`, which exist on `maestro_workflow_instance`:
   `V1__create_maestro_schema.sql:20-22`); or k6 `teardown()` reading Postgres
   through `k6/x/sql` (§4.4); or a SQL step after k6 exits.
2. **Kafka-delivered signals.** k6 needs `xk6-kafka` (a community extension
   that needs a CGO custom build; §4.4), or an app-side HTTP→Kafka bridge like
   the admin endpoint above, which is unusable for measuring because it
   swallows errors.
3. **Meters a load test needs but the catalog lacks** (`docs/observability.md:70-143`
   lists 14 counters, 1 timer, 2 gauges):
   - no workflow start→terminal duration timer (only `maestro.activity.duration`, `:133`);
   - no task-queue slot gauges or wait timer, though
     `TaskQueueGovernor.availableWorkflowSlots/availableActivitySlots` exist
     (`TaskQueueGovernor.java:290, 301`) and `MaestroEngineGauges` registers
     only `running` and `parked` (`MaestroEngineGauges.java:62-65`);
   - no `runAbandoned`-by-reason counter (handover §4.2,
     `tasks/handover-...md:83-85`), so `STORE_UNAVAILABLE` from pool
     exhaustion is visible only in logs or spans;
   - no recovery-pass duration or wake-recheck counters.
4. **A workload with knobs.** No sample lets you set the number of activities,
   activity time, park mode (none / `sleep` / `awaitSignal`) or task queue per
   request, and run at the shipped defaults.

---

## 3. Engine behaviours worth a load test

Prometheus names below follow Micrometer's conventions for the catalog's
meters (as already used by the demo dashboard: `maestro_workflow_started_total`,
`maestro_workflows_parked`, `maestro_activity_duration_seconds_bucket`,
`maestro_recovery_adopted_total`, `maestro_standdown_total` —
`demo/grafana/dashboards/maestro.json`). Activity-duration buckets need
`management.metrics.distribution.percentiles-histogram.maestro.activity.duration=true`
(`demo/scripts/lib/jvm-env.sh:45-51`). Hikari/JVM/HTTP meters
(`hikaricp_connections_*`, `jvm_*`, `http_server_requests_seconds_*`) are
Spring Boot's standard actuator meters; confirm their exact names against a
Boot 4 scrape before writing thresholds on them.

Threshold strategy throughout: **statement counts per unit of work are
deterministic and machine-independent**, so they can be absolute, model-checked
thresholds even on noisy runners. **Latency and throughput are
machine-dependent**, so they are either ratios measured in the same job (2 nodes
vs 1) or baseline-relative with a wide band, and on GitHub-hosted runners
should only catch large regressions.

### S1 — Throughput knee at the default bounds (20/20) *(highest value)*

Why: handover §6.3 asks whether 20/20 is right; §13's bounds are arithmetic
(`docs/operations.md:1183-1191`); the Boot pool default (10) is below 20
activity slots (§1.4.2).

- **SUT:** 1 node, no `task-queues` listed (true defaults), Hikari at Boot
  default; then a matrix: `default-concurrency` ∈ {10, 20, 40, 0/0 unbounded}
  × pool ∈ {10, 25}.
- **Workflow:** K = 5 activities of 50 ms each (configurable), no park.
- **k6:** `ramping-arrival-rate`, `timeUnit: '1s'`, stages stepping e.g.
  5 → 10 → 20 → 40 → 80 starts/s, 3 min per step, `preAllocatedVUs` sized so
  `dropped_iterations` stays 0 (if it doesn't, the generator saturated, not the SUT).
- **SLIs:** k6 `http_req_duration{name:start}`, `http_req_failed`,
  `dropped_iterations`, `iterations`; store-derived start→complete p50/p95/p99
  and completed/s per step (post-run SQL on `completed_at - started_at`);
  `rate(maestro_workflow_completed_total)`, `maestro_workflows_running`,
  `maestro_activity_duration_seconds`; `hikaricp_connections_active/pending`,
  `hikaricp_connections_acquire_seconds`, `hikaricp_connections_timeout_total`;
  `pg_stat_statements` total calls/s and mean time; node CPU.
  Gap: no slot-wait metric (§2.3.3); infer it from completed-latency minus
  K × activity time until a gauge exists.
- **Little's-law check:** with 20 workflow slots and per-run time D, the cap is
  ~20 / D runs/s per node; for D ≈ 5 × 50 ms + overhead ≈ 0.3-0.5 s that is
  ~40-65/s. The knee should sit near the model; if it is far below, the pool
  or the store is the bottleneck, not the slots.
- **Pass/fail:** none at first. Output is a characterisation table (knee per
  config) that feeds the 20/20 decision and §13. Later regression gate:
  throughput at the recorded knee ≥ 80 % of baseline, and **statements per
  completed workflow** within ±10 % of baseline (absolute, noise-free).

### S2 — Recovery drain of a backlog, 1 vs 2 nodes (Issue 30, handover 3.1)

Why: `docs/open-issues.md:2818-2858` — the first node to recover adopts the
whole backlog and drains it through 20 slots while a peer idles; the handover
arithmetic is 5,000 runs × 2 s ≈ 500 s on one node vs ~250 s on two
(`tasks/handover-...md:57`). Startup recovery also runs serially on the
`ApplicationRunner` thread, delaying readiness (same row).

- **Workflow:** one 2 s activity per step, several steps.
- **Shape:** (1) seed with k6 `shared-iterations` (`iterations: 5000`,
  `vus: 50`) against both nodes; (2) once most runs are `RUNNING`, `docker
  kill` both nodes; (3) wait out the lock TTL (30 s); (4a) start one node, or
  (4b) start two together; (5) a k6 `constant-arrival-rate` scenario at 1/s
  polls the aggregate stats endpoint (or SQL) until all terminal.
- **SLIs:** drain time (last `completed_at` − restart time); per-node share of
  adoptions (`maestro_recovery_adopted_total` per instance) and of completions;
  `maestro_workflows_running` per node over time; time from process start to
  `/actuator/health` UP (readiness delay); instance-lock rows per node in
  `maestro_distributed_lock`.
- **Pass/fail:** ratio, same job: `drain(2 nodes) / drain(1 node) ≤ 0.65` and
  the smaller node's adoption share ≥ 30 %. **Expected to fail today** —
  that is the Issue 30 finding. This is the natural "before" for the
  recommended next engine cycle, and the acceptance test once it is fixed
  (Issue 30's own "Done when" is the same experiment at `concurrency: 1`,
  `docs/open-issues.md:2839-2841`).

### S3 — Parked population steady load (Issue 12, handover 3.9)

Why: §13 says the wake re-check (2P–3P reads per 30 s) dominates at scale
(`docs/operations.md:1163-1181`); measured only as a soak trend at 5 s recheck.

- **Shape:** k6 `shared-iterations` to park P workflows in `awaitSignal` with a
  long timeout (and a separate run with `sleep(1h)`), plateaus P ∈ {1k, 5k,
  10k} on 1 node and on 2 nodes, **30 s default recheck**, 10 min hold per
  plateau with no arrivals; then a small probe scenario
  (`constant-arrival-rate`, 1/s, short no-park workflows) on each plateau.
- **SLIs:** `pg_stat_statements` deltas per statement shape (reuse
  `MetricsSampler`'s patterns, `MetricsSampler.java:54-83`, extended with the
  status/signal/timer re-check reads); `maestro_workflows_parked`;
  `hikaricp_connections_active`; JVM heap after GC and live thread count vs P
  (each parked run holds a virtual thread and a `ParkingLot` future,
  `docs/observability.md:153-160`); probe start→complete p95.
- **Pass/fail:** absolute against the model: measured reads/s within ±25 % of
  P × (2..3) / 30 s plus renewal ⌈H/500⌉/10 s plus recovery ⌈A/200⌉/60 s.
  Probe p95 at 10k parked ≤ 2× its value at P = 0 (same job). Heap per parked
  workflow recorded, not gated, the first time.

### S4 — Signal throughput across N replicas (+ wake-permit leak, handover 3.2)

Why: with N replicas most HTTP signals land on a non-owning node, which goes
through `NOTIFY`; handover 3.2 says each such delivery leaves a `pendingWakes`
entry for the process lifetime (`tasks/handover-...md:58`).

- **Shape:** park W workflows in a loop of `awaitSignal` (M signals each);
  k6 `constant-arrival-rate` sends signals round-robin across N ∈ {1, 2, 3}
  nodes at a stepped rate; 30 min hold for the leak.
- **SLIs:** `http_req_duration{name:signal}`; signal→consume latency from the
  store: `maestro_workflow_signal.received_at` (DB clock, `V5__signal_received_at_default.sql`)
  to the `SIGNAL_RECEIVED` event's `created_at` (JVM clock: fine on one host,
  skewed across hosts); `rate(maestro_signal_consumed_total)`;
  `maestro_signal_duplicates_total` (retries with `Idempotency-Key`);
  `NOTIFY`/`LISTEN` call counts; heap after GC on the non-owning nodes over
  time (no meter for `pendingWakes`).
- **Pass/fail:** consume p95 at N = 3 ≤ 1.5× N = 1 (same job); zero lost
  signals (store check: every sent signal consumed); heap slope on non-owners
  ≈ 0 after warm-up. **Expected to fail the heap check today** if 3.2 is real.
- **Kafka path:** out of scope for a first cut (§4.4).

### S5 — Pool exhaustion vs the health indicator (handover 3.5)

Why: `MaestroHealthIndicator` probes the store through the shared pool with a
2 s timeout (`MaestroHealthIndicator.java:97, 181-185`); a busy pool turns
health DOWN, and a liveness probe on aggregate health would kill healthy pods
(`tasks/handover-...md:61`).

- **Shape:** pool 10 (Boot default), activity-heavy load at ~1.5× the S1 knee,
  activities that themselves borrow connections (e.g. `SELECT pg_sleep(0.05)`
  on the host pool, to model a real app's DB use); a second k6 scenario,
  `constant-arrival-rate` 1/s, `GET /actuator/health`.
- **SLIs:** `checks{scenario:health}` (status 200 and `UP`),
  `hikaricp_connections_pending`, `hikaricp_connections_timeout_total`,
  `STORE_UNAVAILABLE` abandons (log count until a meter exists), HTTP 5xx.
- **Pass/fail:** health check pass rate = 100 % while Postgres is reachable.
  **Expected to fail today**: this documents the finding; after the fix it
  becomes a gate.

### S6 — Retention sweeper impact (handover 3.11)

Why: the sweeper can run 100 batches of 500 back to back with no pause
(`tasks/handover-...md:67`; defaults `docs/operations.md:977-1001`); never
exercised under load (§1.4.3).

- **Shape:** seed ~100k terminal instances with events, `completed_at` older
  than retention. Bulk SQL `generate_series` is far faster than driving them
  through the engine; the alternative is a short `completed-retention` on a
  run that k6 pre-filled. Then a steady probe workload (`constant-arrival-rate`)
  across the first sweep.
- **SLIs:** probe start→complete p99 and `http_req_duration` during vs before
  the sweep; `rate(maestro_retention_purged_total{kind})`; `pg_stat_wal`
  bytes; lock waits (`pg_locks`/`pg_stat_activity.wait_event`).
- **Pass/fail:** baseline-relative in the same job: probe p99 during the sweep
  ≤ 1.5× pre-sweep; zero `STORE_UNAVAILABLE`.

**Suggested order:** S1 (it builds the harness and answers the open 20/20
question), S2 (it is the before/after for the recommended next cycle), S3, then
S4/S5/S6.

---

## 4. Where it lives and how it runs

### 4.1 Options

| | A. k6 + docker compose, dedicated perf app | B. k6 inside the Testcontainers chaos harness | C. Extend the Java `WorkloadDriver` (no k6) |
|---|---|---|---|
| SUT bring-up | `perf/compose.yml`: Postgres with `pg_stat_statements`, Kafka + topic init, `node-a`/`node-b`(/`node-c`) as named services, Prometheus | `ChaosCluster` as is, plus a k6 container in the same network (Testcontainers has a k6 module; check that it ships in 2.0.5) | `ChaosCluster` as is |
| Fit for kill/restart scenarios (S2) | Good: named services, `docker kill node-a` | Best: `ChaosController` already kills/pauses/partitions | Best |
| Reuse | Boot-jar-in-`eclipse-temurin:25-jre` node pattern (`ChaosCluster.java:72-75, 298-310`); topic list from `docker-compose.yml:72-116` | All the orchestration | All the orchestration |
| Cost | New module + compose + scripts | JUnit ↔ k6 coupling; k6 metrics end up in Java | No k6 thresholds, summary, or outputs; you rebuild them |
| Local run | `./perf/run.sh s1-throughput` | `./gradlew :maestro-integration-tests:perfTest -Dmaestro.perf.scenario=s1` | same as chaos |

**Recommendation: A**, with a **dedicated perf sample app**, and S2's
kill/restart done with `docker kill`/`docker compose start` from the run
script. The loan sample stays the correctness workload; it can be added later
as a realistic mixed-traffic scenario. The order sample is unsuitable as is: it
has no actuator, its random 30 % failure is noise, and it needs payment-gateway
over Kafka.

### 4.2 Proposed layout

```
maestro-samples/sample-perf/                 # Spring Boot app, NOT published
  build.gradle.kts                           # starter + store-postgres + messaging-kafka
                                             # + actuator + micrometer-registry-prometheus
  src/main/java/.../perf/
    PerfWorkflow.java                        # knobs from input: activities, activityMillis,
                                             # park = none|sleep|signal, parkSeconds, signals
    PerfController.java                      # POST /perf/workflows, POST /perf/workflows/{id}/signals,
                                             # GET /perf/workflows/{id}, GET /perf/stats?tag=
  src/main/resources/application.yml         # NO task-queues listed (true 20/20), 30 s recheck,
                                             # retention on, histogram for activity.duration
perf/
  compose.yml                                # postgres(pg_stat_statements), kafka, kafka-init,
                                             # node-a, node-b, node-c (profiles), prometheus
                                             # (--web.enable-remote-write-receiver)
  prometheus.yml
  k6/
    lib/{client.js,sql.js,config.js}
    s1-throughput.js  s2-recovery-drain.js  s3-parked.js
    s4-signals.js     s5-pool-health.js     s6-retention.js
  sql/{snapshot-pg-stat-statements.sql, workflow-latency.sql, reset.sql}
  run.sh                                     # build bootJar, compose up, reset stats,
                                             # k6 run, SQL + Prometheus snapshots, compose down
  baselines/<scenario>.json                  # committed reference numbers (if decided)
  README.md
```

Gradle wiring follows the chaos precedent: a `perf` task that depends on the
perf app's `bootJar` and never hangs off `build`/`check`
(`maestro-integration-tests/build.gradle.kts:45-91`).

### 4.3 Running it

- **Local:** `./perf/run.sh s1-throughput` (wraps `./gradlew
  :maestro-samples:sample-perf:bootJar`, `docker compose -f perf/compose.yml
  --profile two-nodes up -d`, `k6 run perf/k6/s1-throughput.js`). On macOS,
  Docker Desktop's VM limits CPU and memory, so local numbers compare only with
  local numbers.
- **k6 install, pinned:** latest is **v2.4.0, released 2026-11-12 (today)**;
  previous **v2.3.0 (2026-09-21)**; the v1 line is at v1.8.2 (2026-10-12)
  (GitHub releases API). Pin **2.3.0** until 2.4.0 has aged. Either
  `grafana/setup-k6-action@v1` with `k6-version: '2.3.0'` (its inputs are
  `k6-version` and `browser`; omitting the version installs latest, so always
  set it) plus `grafana/run-k6-action@v1`, or run the `grafana/k6:2.3.0` image
  inside the compose network. The image route avoids host-port plumbing and
  pins the binary in the same file as the SUT.
- **k6 v2 changes that matter:** the HTTP API server no longer starts by
  default (`--address` to enable); `externally-controlled` executor removed;
  `--summary-mode=legacy` removed (`compact`/`full`); `handleSummary()`
  timeout configurable since v2.2.0 (`handleSummaryTimeout`); auto extension
  resolution on by default, including community extensions
  (`K6_ENABLE_COMMUNITY_EXTENSIONS` and `K6_BINARY_PROVISIONING` removed).
- **Executors and thresholds** (confirmed in current docs):
  `constant-arrival-rate` (`rate`, `timeUnit`, `duration`, `preAllocatedVUs`,
  `maxVUs`), `ramping-arrival-rate` (`startRate`, `stages`), `shared-iterations`
  (`vus`, `iterations`, `maxDuration`, default 10m); per-scenario `exec`,
  `tags`, `env`, `startTime`; thresholds on tagged sub-metrics such as
  `'http_req_duration{scenario:probe}': ['p(95)<250']`. Use arrival-rate
  executors (an open model) for every rate scenario, and treat any
  `dropped_iterations` as an invalid run.

### 4.4 Extensions

| Need | Option | Status (2026-11-12) |
|---|---|---|
| Read Postgres from k6 (completion, latency, `pg_stat_statements`) | `k6/x/sql` + `k6/x/sql/driver/postgres` | **Official** Grafana extensions (xk6-sql v1.0.5, xk6-sql-driver-postgres v0.1.1), so auto-resolved on import. Resolution downloads a provisioned binary at run time, so CI needs network access to Grafana's build service. A plain `psql` step after k6 exits avoids the dependency. |
| Push k6 metrics next to Maestro meters | built-in output `-o experimental-prometheus-rw` (`K6_PROMETHEUS_RW_SERVER_URL=http://prometheus:9090/api/v1/write`; optional native histograms; `K6_PROMETHEUS_RW_LABELS` new in v2.3.0) | Built in, still named "experimental". Prometheus needs the remote-write receiver flag. Output extensions are *not* auto-resolved, but this one is built in. |
| Kafka-delivered signals | `xk6-kafka` (community, mostafa) | v2.x targets k6 v2 (`go.k6.io/k6/v2`). It needs a **CGO custom build** via `xk6 build` (confluent-kafka-go), and the extension catalog lists v1.0.0. Its README notes a producer throughput regression in v2.1.0, fixed after it. A custom-binary maintenance burden; defer. |

Recommendation: k6 for arrivals, HTTP SLIs and thresholds. Store-derived SLIs
come from a SQL snapshot after the run (`perf/sql/*.sql`, so there is no
extension dependency on day one). Prometheus scrapes the nodes at 5 s, and k6
remote-writes into the same Prometheus, so one query surface covers both.

### 4.5 CI placement

- **Never PR-gating.** Repository practice already keeps anything heavier than
  integration tests out of the PR loop (`e2e-nightly.yml:3-10`).
- **`workflow_dispatch` with a `scenario` choice input**, plus at most a
  weekly cron for S1 (and S3) so a regression signal exists. Put it either as
  a job in `e2e-nightly.yml`, which `keep-nightly-enabled` already re-enables
  after GitHub's 60-day inactivity disable (`build-test.yml:42-58`), or as a
  new `perf.yml` whose name is added to that `gh workflow enable` step.
- **Duration budget:** S1 matrix ~20-40 min per config; S2 ~15 min per
  topology; S3 ~45 min; soak-length runs fit the 6 h hosted-job limit. The
  current weekly soak already costs ~2 h 17 min (run 43082784284).
- **Runner noise:** this repo is public, so `ubuntu-latest` is **4 vCPU /
  16 GB / 14 GB SSD** (GitHub runner docs). k6, Postgres, Kafka, Prometheus and
  2-3 JVMs share those 4 vCPUs, so the load generator competes with the SUT,
  and neighbours vary run to run. Consequences: (1) gate only on deterministic
  counts (statements per workflow, rows, zero lost signals) and on same-job
  ratios; (2) give latency thresholds wide bands as catastrophe detectors;
  (3) record `nproc`, `free -m`, the k6 version and the commit in every
  result; (4) for numbers worth quoting in `docs/operations.md`, use a larger
  or self-hosted runner, or a documented laptop run with evidence files (the
  pattern the soak already uses, `docs/operations.md:437-446`).
- **Results:** upload k6's end-of-test summary JSON (`handleSummary` or
  `--summary-export`), the SQL snapshots and a Prometheus range export as an
  artifact (14-day retention like the others, `e2e-nightly.yml:155-161`);
  compare against `perf/baselines/<scenario>.json` with a small `jq` script
  and a tolerance band. Long-term history, if wanted, needs a home: a
  committed CSV, a `perf-results` branch, or Pages.

---

## 5. Decisions only the maintainer can make

1. **What is the suite for: characterisation or regression gate?**
   Characterisation (numbers for the 20/20 decision, §13 and Issue 30) needs
   no thresholds and suits manual dispatch. A regression gate needs committed
   baselines, a stable runner and an owner who triages it. This decides almost
   everything else.
2. **Dedicated perf app and new meters?** A `sample-perf` module (recommended)
   plus product changes to the starter: slot gauges or a slot-wait timer, a
   workflow-duration timer, and a `runAbandoned`-by-reason counter. Without
   them S1 and S5 infer their key SLIs indirectly. Alternatively accept the
   loan sample's noise and the current 17 meters.
3. **CI budget and runner.** Dispatch-only vs weekly; GitHub-hosted (noisy, ~4
   vCPU shared) vs a larger or self-hosted runner; where results live
   (artifacts only, committed baselines, or a history branch/Pages).
4. **Known-red scenarios.** S2 (Issue 30), S5 (health) and S4's heap check are
   expected to fail today. Ship them as "expected-fail / characterise only"
   before the fixes, so each fix has a before/after, or add them only with the
   fix. The handover recommends Issue 30 as the next cycle
   (`tasks/handover-...md:152-153`), which argues for building S2 first.
5. **Kafka signal path in scope?** HTTP-only (REST signals go through
   `deliverSignal` + `NOTIFY`, which covers cross-replica wake) vs adding a
   CGO-built `xk6-kafka` binary to cover the `maestro.signals.*` consumer,
   redelivery and DLT under load.
