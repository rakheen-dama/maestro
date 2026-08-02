# Checker-Blindness Investigation (soak attempts 1 & 2)

- Date: 2026-08-01 (SAST)
- HEAD: 9494b4b12b4b673531dc69a3c52facfaa3104ca5
- pwd: /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/multi-instance-verification
- Investigator: read-only; no source modified, no containers started.

## 1. How the checker connects (source facts)

`PeriodicChecker.probeDatabases()` (PeriodicChecker.java:118-128):
- Every 30s cycle it probes ALL THREE service databases with `SELECT 1`.
- Each probe: `cluster.dataSource(svc).getConnection()` in try-with-resources —
  a **fresh connection per probe, properly closed**.
- `ChaosCluster.dataSource()` (ChaosCluster.java:334-342) builds a **new
  `PGSimpleDataSource` on every call** and **re-resolves
  `postgres.getMappedPort(5432)` every call**. There is NO cached Connection,
  NO pool, NO cached URL in the checker path. (Mapped port is constant for a
  container's life anyway — the postgres container is never
  restarted/replaced, only pause/unpause in BACKEND_OUTAGE.)
- **The actual exception is swallowed silently**: `catch (Exception e) { return
  false; }` — the WARN/ERROR lines have no cause attached. The underlying
  exception (connect refused vs timeout vs FATAL: too many clients) was never
  logged anywhere. This is itself a finding: we went blind about *why* we went
  blind.
- `InvariantChecker` and `MetricsSampler` likewise use fresh
  try-with-resources connections per query via the same `dataSource()`; no
  test-JVM connection leak is visible in source.
- All three "databases" live in ONE postgres container (`init-loan-dbs.sh`
  creates per-service DBs); one failing TCP connect to the single host-mapped
  port makes the whole probe report "store unreachable".

Consequences:
- "Stale cached connection/URL that never reconnects" is **falsified by
  source**: the checker re-creates everything each cycle. Whatever failed,
  failed freshly ~380+ times in a row over 95 min.
- The failure is therefore on the *path*: test JVM (host) → Docker
  Desktop port-forward → postgres container, or postgres refusing new
  connections (e.g. max_connections exhausted by orphaned backends from
  KILL9'd node containers — postgres holds dead peers' connections until TCP
  keepalive, default ~2h, which matches "never recovers within the run").

## 2. Candidate mechanisms to discriminate (before log evidence)

- H1 host-path death: Docker Desktop port-forward (com.docker.backend/vpnkit)
  stops servicing the postgres mapped port (or all mapped ports) while
  in-network traffic is fine.
- H2 max_connections exhaustion: node KILL9s orphan their Hikari pool's
  server-side backends (no RST when the container's netns dies); each
  kill/replace cycle net-adds ~pool-size backends; once at the 100 default,
  every NEW connection (checker, sampler) gets FATAL 53300 while nodes'
  existing pooled connections keep working. Never recovers within the run
  because keepalive reaping takes ~2h.
  - Discriminator: a successful REPLACE (node needs ~pool-size fresh
    in-network connections + Flyway) during the blind window falsifies H2.
    metrics.csv statusCounts going to zero at onset is consistent with both
    H1 and H2 (sampler also connects from host).
- H3 host ephemeral-port/fd exhaustion from driver volume.
  - Discriminator: driver HTTP (also host-mapped) would fail too.

## 3. What the run artifacts show (run 2, 20260801-153653-739466153284315030)

All timestamps UTC unless marked "local" (local = UTC+2 SAST).

Timeline of blindness onset (metrics.csv + ledger.jsonl + console):

| Time (UTC) | Event |
|---|---|
| 15:37:31 | generation begins: `20/min for PT2H` (console line 177) |
| 15:37:31 → 16:01:44 | 495 submissions in 24.2 min ≈ 20.3/min — perfectly paced |
| 16:01:44 | last paced submission (seq 495) |
| 16:01:51–16:01:53 | **pacer collapses**: seq 498 at :51.11, then 601, 837, 1078, 1117 within 3 ms at :53.86-88 (~10k/s instantaneous) |
| 16:01:56 | heap-samples.csv: heap jumps 39 MB → 992 MB (flat 28–58 MB for the previous 24 min) |
| 16:01:56 | metrics.csv: all DB-derived columns (running/waitingSignal/waitingTimer/recovery) drop to 0 — the sampler's host-JDBC queries now fail; Valkey columns (docker-exec) keep reporting |
| 16:02:07 | first "store unreachable this cycle" (checker) — blindness onset |
| 16:03:29–16:04:24 | action #22 KILL9 LOAN_B **heals successfully inside the blind window** (fresh container: ~10 new in-network DB conns + Flyway + host-mapped HTTP health check all worked) |
| 16:05:05 | action #23 KILL9 VERIFY_A; replacement container starts 16:05:28 but never becomes HTTP-ready |
| 16:08:29 | **CONTROLLER DIED**: `IllegalStateException: Node VERIFY_A did not become HTTP-ready` (awaitNodeHealthy 3-min timeout) — chaos actions STOP here for the remaining ~105 min (the "chaos actions kept working the whole time" premise is FALSE) |
| 16:11:28 | seq reaches 71033 (~120/s average since collapse, slowing as heap fills) |
| 16:21:37 | last metrics.csv row; heap 1620 MB (was 1214 MB fifteen seconds earlier); sampler then stalls for **92 minutes** (one row at 17:53:33, heap 1905/2048 MB) |
| 17:35–17:53 | checker cadence degrades from 30 s to ~5 min gaps (cycles 173→175 span 10 min) — test JVM in GC starvation |
| 17:53:26 | generation window closes **16 min late**: "**1811027 workflows submitted**" — this number is loop iterations of a runaway pacer, not real workload |
| 17:53:26-44 | Gradle client vanishes; daemon logs "thread 236: client disconnection detected, canceling the build"; test-executor process ABORTED exit 143 (SIGTERM); daemon itself SURVIVED (still alive at investigation time) |

Cross-checks:
- ledger.jsonl: 2117 entries total, max seq 71033. Of 1622 post-knee completed
  scripts, **zero** have empty notes — not one script after the knee ever
  confirmed a store effect over the host-mapped port. All carry
  `create-not-confirmed` / `doc-not-landed` / `underwriting-...-not-requested`.
- Node logs: loan-a-gen1.log contains only **3704 distinct** loan-chaos
  workflow ids and **zero** "too many clients"/Hikari errors — the nodes'
  in-network DB access was healthy the whole time and they never received
  anything like 1.8 M requests. The intended-load figure for the window was
  ~2 900 workflows; actual store content stayed at that order.
- Expected soak load: config SOAK = 120 min at 20/min (ChaosConfig.java:60-62).

## 4. Occurrence 1 (20260801-123243--3913844229203478509) — identical signature

- Generation begins 12:33:22 UTC at 20/min (console line 175).
- Paced to seq 434 (12:57:09), knee between 12:57:09 and 12:57:24 —
  **23m47s–24m02s after generation start** (run 2: 24m13s–24m20s). Both knees
  land during/just after chaos action #20 (actions average ~72 s, so #20 ≈
  24 min — time and action-count are confounded).
- heap-samples.csv: 1589 MB by 12:57:53 (was <60 MB before); pinned
  1880–1920 MB until the host rebooted (last sample 15:22:33, i.e. 17:22 local,
  right at the 17:23 reboot).
- ledger: 1838 entries, max seq 60008. Same runaway.
- Verdict on "environmental" theory: **reversed** — the runaway test JVM
  (2 GB heap thrash + ~10⁶ virtual threads + sustained connection flood
  through Docker Desktop's port proxy) is what distressed the host into the
  reboot, not the other way round.

## 5. Root-cause verdict

**Root cause: the workload generator's Poisson pacer self-destructs on a
single thread interrupt, and the resulting runaway submission loop drowns the
host→postgres path; checker blindness is a symptom.**

Mechanism (WorkloadDriver.java):
1. `parkNanos()` (line 607-616) catches `InterruptedException`, re-sets the
   interrupt flag, and returns. Because the flag stays set, **every subsequent
   `TimeUnit.sleep` throws immediately** — after ONE interrupt the pacer never
   sleeps again. `generateAt` (line 110-130) then submits a script per loop
   iteration at allocation speed.
2. Each iteration: `executor.submit(...)` spawns a virtual thread and
   `futures.add(...)` on a `CopyOnWriteArrayList` copies the whole array —
   O(n) per add, O(n²) cumulative: at seq 1 M that is ~8 MB of garbage *per
   submission*. Result: the observed 1–1.9 GB heap oscillation and eventual
   GC starvation of every other thread (sampler stalled 92 min, checker
   cadence stretched to 5 min, generation window overran by 16 min).
3. ~1.8 M scripts were launched; each immediately begins HTTP posts (3 s
   timeouts) and, crucially, **unpooled JDBC connection attempts** (each
   `instanceExists`/`signalCount` poll = a fresh `PGSimpleDataSource`
   connection through the Docker Desktop port-forward). Thousands of
   concurrent connect attempts permanently saturate the path — postgres
   max_connections (default 100), the proxy's accept/dial pipeline, and host
   ephemeral ports (macOS ~16k, 15 s TIME_WAIT) are all plausibly exceeded,
   and all are downstream of the same flood. From 16:01:53 every host-side
   JDBC consumer fails: checker (blind), sampler (zero columns then hang),
   driver effect checks (0/1622 post-knee confirmations).
4. In-network traffic (node Hikari pools, already-established) and
   docker-exec probes never traverse that path — hence "everything else looked
   fine".

What the evidence falsifies:
- *Stale cached connection/URL*: falsified by source — fresh DataSource +
  fresh `getMappedPort` per probe, try-with-resources everywhere.
- *Pool poisoning after the kafka outage*: no pools exist on the test-JVM
  side; the kafka outage (#14, 15:53) precedes onset by 9 min of healthy
  cycles.
- *Docker Desktop proxy degradation as PRIMARY cause*: falsified by action #22
  — a replacement node passed its host-mapped HTTP health check inside the
  blind window, and node HTTP ports kept answering; only the flooded postgres
  path died, and it died 2 s AFTER the pacer collapsed and the heap exploded.
- *Host fd/port exhaustion as primary*: it is a consequence of the flood, not
  an independent cause (flat heap + paced ledger for the first 24 min).

**Open question — the interrupter.** The interrupt source could not be
identified from the artifacts: `probeDatabases` and the driver's `safe()`
swallow the underlying exceptions unlogged, and nothing in the harness
interrupts the Test worker thread (only PeriodicChecker.stop/MetricsSampler.stop
call `interrupt()`, both at run end; JUnit `@Timeout(200, MINUTES)` is
SAME_THREAD and had not expired; no `junit-platform.properties`, no Gradle
task timeout). The ~24-min offset is tightly reproduced across both runs
(23m47s–24m02s and 24m13s–24m20s after generation start; both during action
#20), so the trigger is systematic, but time-in-run and action-count are
confounded and no candidate 24-min timer exists in the harness, JUnit, Gradle
or Testcontainers config. The fix below makes the trigger moot and adds the
diagnostics to catch it if it recurs. (Falsifier for the whole verdict: a
soak with the interrupt-hardened pacer that still goes blind at ~24 min with
a flat heap would reopen the host-path theory.)

Note: the same swallow-and-continue sleep bug exists in
`ChaosController.sleep` (ChaosController.java:252-258) — an interrupted
controller would blast docker ops with zero gaps/pause durations — and in
`ChaosRun.sleep`. `MetricsSampler`/`PeriodicChecker` loops exit on interrupt
(correct).

## 6. The Gradle daemon death at 19:53 (local)

Consequence, not an independent problem. Daemon log
(`~/.gradle/daemon/9.2.0/daemon-3753.out.log`): at 19:53:26-44 local the
daemon detected "client disconnection, canceling the build", ABORTED the test
executor (exit 143 = SIGTERM from the cancel), finished the build, and could
not write the result back ("Could not write message … to '/127.0.0.1:50448'").
The daemon itself survived and is still running; what "disappeared" from the
client's perspective was the broken client↔daemon socket — i.e. the *client*
JVM died (most plausibly the host's memory pressure: a 2 GB-heap test JVM in
GC hell with ~1.8 M virtual threads, 9+ maestro containers, the b2b stack and
2 h of TIME_WAIT churn). No jetsam record was recoverable this long after the
fact. SOAK_EXIT=1 is the wrapper reporting the dead client. The timing
(seconds after the generation window closed, when `awaitScriptsSettled` began
iterating 1.8 M futures and heal-all started replacing nodes) is consistent
with a final host-memory spike.

## 7. Recommended fix (concrete)

Primary — make the pacer interrupt-safe and bounded
(`WorkloadDriver.generateAt` / `parkNanos`):
1. `parkNanos` must not swallow interrupts silently. In `generateAt`, treat
   interrupt as "abort generation": check
   `Thread.currentThread().isInterrupted()` after each park and `break` with a
   loud `log.error("[chaos] generation interrupted at seq {} — aborting", seq)`
   (nothing in the harness legitimately interrupts mid-window).
2. Belt-and-braces rate guard: `long expected = ratePerMinute * window.toMinutes();
   if (seq > 3 * expected + 100) { log.error(...); break; }` — a runaway can
   then never exceed 3× intended load regardless of trigger.
3. Replace `futures` `CopyOnWriteArrayList` with a plain `ArrayList` guarded
   by a lock, or `ConcurrentLinkedQueue` — removes the O(n²) copy storm that
   turned the runaway into GC death.
4. Bound in-flight scripts: a `Semaphore` (e.g. 4× rate) acquired before
   `executor.submit`, released in the script's `finally` — a stalled store
   then back-pressures generation instead of accumulating 10⁶ virtual threads.

Secondary — never go blind about *why* you are blind
(`PeriodicChecker.probeDatabases`): log the swallowed exception once per
streak (`log.warn("... first failure: {}", e.toString())`) and include the
per-service breakdown; likewise log the first failure cause in
`MetricsSampler.statusCounts`. Set `connectTimeout`/`socketTimeout`/
`loginTimeout` (e.g. 5 s) on the `PGSimpleDataSource` in
`ChaosCluster.dataSource` so no consumer can hang 92 min in a login read the
way the sampler did.
Fix the same interrupt-swallow in `ChaosController.sleep` (abort the schedule
loudly on interrupt).

RED test for the pacing/interrupt behaviour (new
`WorkloadDriverPacingTest`, no containers needed — inject a stub
cluster/evidence): start `generateAt(600/min, PT10S)` on a thread, interrupt
that thread at T+2 s, join, and assert (a) the returned seq is ≤ 3× the
intended 10-s budget (fails today: returns tens of thousands) and (b) the
method returned promptly after interrupt. A second case interrupts before the
first park and asserts seq stays 0/1. For the checker: a
`PeriodicCheckerProbeTest` against a stopped Testcontainers postgres asserting
the WARN carries the underlying exception text (fails today: no cause logged).

## 8. Salvageability of the two soak attempts

**Neither run is salvageable as multi-instance-correctness evidence — agree
with the prior, and it is worse than "invariants unwatched":**
- Run 1: blind from 26 min; run killed by host reboot; no authoritative
  verify, no census, no run-summary.
- Run 2: blind 189/~205 cycles; chaos schedule dead from 16:08 (22 of the
  planned ~100 actions executed); the workload itself was garbage after the
  knee (1.8 M phantom submissions, 0 confirmed effects); the build was
  cancelled before heal-all completed, so no authoritative invariant check,
  no census, no run-summary.json exists in either run dir.
- The only durable value: both runs are a high-quality reproduction of the
  pacer-runaway failure mode, and the first ~24 min of each show the cluster
  healthy under paced load with 20+ chaos actions and zero periodic
  violations. A fresh soak is required after the fixes above.

## 9. File/line index

- Checker probe + swallowed cause: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/e2e/chaos/PeriodicChecker.java:118-128`
- Fresh DataSource per call: `.../ChaosCluster.java:334-342`
- Pacer + interrupt swallow: `.../WorkloadDriver.java:110-130` (generateAt), `:607-616` (parkNanos), `:59-61` (CoW futures/ledger), `:487-515` (post), `:537-580` (unpooled JDBC polls)
- Controller sleep swallow: `.../ChaosController.java:252-258`
- Controller death guard: `.../ChaosRun.java:101-113`
- Run dirs: `maestro-integration-tests/build/chaos-evidence/20260801-123243--3913844229203478509`, `.../20260801-153653-739466153284315030`
- Consoles: `.superpowers/sdd/multi-instance/evidence/task7/soak-console-KILLED-reboot-20260801-1723.log`, `.../soak-console.log`
- Daemon log: `~/.gradle/daemon/9.2.0/daemon-3753.out.log`

## 10. ADDENDUM (2026-08-01, post-fix): the interrupter is identified — §5's "open question" is CLOSED

The "Open question — the interrupter" paragraph above is retracted: the
interrupt-hardened pacer caught the interrupter red-handed in soak attempt 3.
`-Dmaestro.chaos.soak=true` did not exclude `ChaosPrGateE2EIT`, so every soak
invocation ran BOTH chaos classes; `ChaosConfig.fromSystemProperties(PR_GATE)`
resolves the soak flag to SOAK mode and honors `durationMinutes=120`, so the
PR-gate test ran a 120-min generation window into its own
`@Timeout(25 MINUTES)` — and JUnit's `TimeoutExtension` interrupted the Test
worker at ~25 min. That matches the ~24-min knee of both failed soaks
(generation starts a minute-ish into the test; both knees 23m47s–24m20s after
generation start) and explains why "no candidate 24-min timer exists in the
harness": the timer was the PR-GATE test's timeout, running in a mode it was
never meant to run. Verbatim from
`evidence/task7/soak-console.log` (attempt 3, 23:43:23 local):

```
23:43:23.289 [Test worker] ERROR io.b2mash.maestro.integration.e2e.chaos.WorkloadDriver -- [chaos] GENERATION INTERRUPTED at seq 503 on thread 'Test worker' — aborting generation and failing the run (a swallowed interrupt here caused the soak-killing pacer runaway)
java.lang.InterruptedException: sleep interrupted
	at java.base/java.lang.Thread.sleepNanos0(Native Method)
	at java.base/java.lang.Thread.sleepNanos(Thread.java:509)
	at java.base/java.lang.Thread.sleep(Thread.java:577)
	at java.base/java.util.concurrent.TimeUnit.sleep(TimeUnit.java:446)
	at io.b2mash.maestro.integration.e2e.chaos.WorkloadDriver.pace(WorkloadDriver.java:189)
	at io.b2mash.maestro.integration.e2e.chaos.WorkloadDriver.generateAt(WorkloadDriver.java:145)
	at io.b2mash.maestro.integration.e2e.chaos.WorkloadDriver.generate(WorkloadDriver.java:117)
	at io.b2mash.maestro.integration.e2e.chaos.ChaosRun.execute(ChaosRun.java:131)
	at io.b2mash.maestro.integration.e2e.chaos.ChaosPrGateE2EIT.prGate_clusterSurvivesChaos_allInvariantsIntact(ChaosPrGateE2EIT.java:35)
	...
	at org.junit.jupiter.engine.extension.SameThreadTimeoutInvocation.proceed(SameThreadTimeoutInvocation.java:51)
	at org.junit.jupiter.engine.extension.TimeoutExtension.intercept(TimeoutExtension.java:163)
	at org.junit.jupiter.engine.extension.TimeoutExtension.interceptTestableMethod(TimeoutExtension.java:148)
	at org.junit.jupiter.engine.extension.TimeoutExtension.interceptTestMethod(TimeoutExtension.java:86)
```

Corrections to the body of this document implied by the addendum:
- §5 called `@Timeout(200, MINUTES)` (the SOAK class's) "not expired" — true
  but looking at the wrong class: the PR-GATE class's 25-min timeout was live
  because both classes ran under the soak flag. seq 503 ≈ 25 min of 20/min
  Poisson pacing, confirming the offset.
- The knee being "during action #20" in both runs was time-confounding, as
  suspected: the trigger is pure wall-clock (test start + 25 min).
- Attempt 3's ~23:43 abort also exercised the falsifier honestly: the
  hardened pacer aborted LOUDLY with a flat heap — no runaway, no blindness
  from the pacer path — upholding the §5 verdict.

Fix of record: `ChaosPrGateE2EIT` now carries
`@DisabledIfSystemProperty(named = "maestro.chaos.soak", matches = "true")`
(plus golden/smoke/mode guards) — a soak invocation selects ONLY
`ChaosSoakE2EIT` (200-min timeout), locally and in the CI weekly `chaos-soak`
job, which uses the identical invocation and self-heals via the exclusion.
Pinned by `ChaosSuiteSelectionTest` and the execution-time selection proof in
`evidence/task7/fixloop3-suite-selection.log`.
