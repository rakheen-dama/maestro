# Soak Driver-Fix Report — pacer runaway + controller loud-death cycle

- pwd: /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/multi-instance-verification
- branch: `worktree-multi-instance-verification`
- base: 9494b4b (investigation HEAD) → fixes b8729bf..b2b5c65 (+ smoke/docs commit to follow)
- Date: 2026-08-01 (UTC)
- Governing inputs: `checker-blindness-investigation.md` (root cause), `task-7-delta-review.md`
  Important #1 + Minor #2 (queued for this pass), chaos-harness FAIL-LOUDLY principle.

## 1. What changed, per scope item

All changes are harness-only (`maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/e2e/chaos/`).
No engine/module code touched. No invariant weakened.

### Scope 1 — pacer interrupt handling (the disease)
`WorkloadDriver`: the Poisson gap sleep moved from the shared swallow-and-continue
`parkNanos` into a dedicated `pace(waitNanos, seq)`. An interrupt (mid-sleep, or
pending before the first park) now ERROR-logs the seq + thread name + the
`InterruptedException` and throws `IllegalStateException("Workload generation
interrupted at seq ...")` — the run fails loudly instead of hot-looping. The
interrupter was never identified (the old code swallowed it unlogged), so the
abort logs everything available to catch it if it recurs; with this fix the
trigger is moot either way (the run aborts in ms at ~paced seq).

Script-side `parkNanos` now returns `false` on interrupt (flag re-asserted) and
all script wait loops (`effectWithRepost`, `pollUntil`, `post`) abort their wait
instead of hot-spinning on a no-op sleep until their deadlines — the same bug in
miniature, triggered at every `executor.shutdownNow()`.

### Scope 2 — runaway guard (belt and braces)
`generateAt` computes `runawayCap = 3 x round(rate x window-minutes) + 100`
(slack for tiny smoke/tail windows and Poisson variance). `seq >= cap` ERROR-logs
and throws `IllegalStateException("Runaway workload generation ...")` — whatever
the trigger, generation can never exceed 3x intended load without failing the run.

### Scope 3 — in-flight bound
`Semaphore inFlight = max(60, 15 x config.ratePerMinute)`, acquired
(interruptibly, bounded by the window) before each submit, released in the
script's `finally`. Sizing by Little's law (in-flight = arrival rate x script
latency): the worst *legitimate* script is CONDITIONS_LOOP with every effect
check burning its full 120s `sampleTimeout` under chaos ≈ 12 min, so a 15-minute
budget of arrivals (soak 20/min → 300; PR gate 12/min → 180; floor 60 for
golden/benchmark-tail rates) can never throttle real load — but a stalled store
back-pressures generation (logged WARN) instead of accumulating 10^6 virtual
threads. Interrupt while waiting takes the same loud-abort path as Scope 1.

### Scope 4 — futures list
`CopyOnWriteArrayList<Future<?>>` → `ConcurrentLinkedQueue<Future<?>>` (O(1)
add; `awaitScriptsSettled` iteration unchanged). Removes the O(n²) copy storm
that turned the runaway into GC death (investigation §5 mech. 2).

### Scope 5 — checker/sampler diagnosability + fail-fast JDBC
- `PeriodicChecker.probeDatabases` returns `null` or `"<service>: <exception>"`;
  every unreachable-cycle WARN and the `CHECKER BLIND` ERROR now carry the cause
  (class + message, every cycle — stronger than the required first-per-streak).
- `MetricsSampler`: status-count and recovery-calls probe failures WARN with the
  cause on the first failure of each streak (were silent / debug).
- `ChaosCluster.dataSource`: `connectTimeout=5s`, `loginTimeout=5s`,
  `socketTimeout=30s` — a dead host→postgres path fails in ≤5s at connect and
  any read stall is bounded at 30s (the sampler once hung 92 minutes in a login
  read; 30s is an order of magnitude above the heaviest verify/census query on
  soak-scale data).

### Scope 6 — ChaosController.sleep
Same disease, same cure: interrupt re-asserts the flag, ERROR-logs, throws
`IllegalStateException("Chaos controller interrupted mid-schedule ...")` — no
more zeroed gaps/pause durations blasting docker ops in a hot loop.

### Scope 7 — controller loud-death
`ChaosRun.execute`: the controller thread's `catch (Throwable)` no longer merely
logs. It records the throwable, ERROR-logs, and **interrupts the orchestrating
thread** — the interrupt-hardened pacer aborts generation promptly and loudly.
After the (now-prompt) join, `execute` throws
`IllegalStateException("CHAOS CONTROLLER DIED mid-schedule ...", death)`. The
16:08Z escape path (VERIFY_A replace failed → `awaitNodeHealthy` timeout →
thread died → run limped on 105 min unwatched) is closed in both orderings:
death-during-generation fails within one pacer gap; death-at-window-boundary is
caught at the join check. Symmetrically, a generation failure now stops the
controller promptly via a `controllerStopRequested` flag (logged as a deliberate
stop, not misreported as a controller death).

### Scope 8 — LogTailScanner split trailing line (delta-review Important #1)
Standard tail semantics: the scanner advances its per-file offset only past the
last complete `'\n'` in the chunk; a partial trailing line (writer flush racing
`Files.size`/read) is re-read whole on the next poll instead of being consumed
in halves and permanently missed. Escape hatch: a single line filling the whole
4MB chunk cap is skipped rather than wedging. (First cut wrongly fired the hatch
on every read because the chunk is sized to exactly the available bytes — caught
by the RED test staying red; fixed to compare against the cap constant.)
Testability refactor: static nested class with an injected file supplier.
Delta-review Minor #2 landed in the same pass: `FileLogConsumer` drops frames
arriving after `close()` instead of silently reopening a never-closed writer.

## 2. RED evidence (verbatim, from evidence/task7/red-driverfix-unit-tests.log)

RED run at gitHead 9494b4b (pre-fix), command
`./gradlew :maestro-integration-tests:test --tests '...chaos.WorkloadDriverPacingTest' --tests '...chaos.LogTailScannerTest' --tests '...chaos.ChaosControllerInterruptTest'`:

```
<failure message="org.opentest4j.AssertionFailedError: RUNAWAY PACER: 63798 scripts generated after one interrupt (intended 10s budget at 600/min = 100, 3x cap = 300) ==> expected: <true> but was: <false>"
<failure message="org.opentest4j.AssertionFailedError: the abort must name the interrupt (no action may be dispatched on a zeroed gap); got: java.lang.NullPointerException: Cannot invoke "io.b2mash.maestro.integration.e2e.chaos.ChaosCluster.pause(io.b2mash.maestro.integration.e2e.chaos.NodeRole)" because "this.cluster" is null ==> expected: <true> but was: <false>"
<failure message="org.opentest4j.AssertionFailedError: the completed effect line was permanently missed: the offset advanced past the partial trailing line on the previous poll ==> expected: <true> but was: <false>"
```

Console tail of the same RED run:

```
Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "Test worker"
WorkloadDriverPacingTest > interrupt pending before the first park: generation aborts at seq 0/1 immediately SKIPPED (JVM died)
5 tests completed, 3 failed, 1 skipped
```

Note the RED run is itself a miniature reproduction of the production failure:
one interrupt → 63,798 scripts against a 100-script budget → **OOM of the 512m
unit-test JVM** — exactly the investigation's mechanism, at 1/28th scale.

## 3. GREEN evidence (verbatim, from evidence/task7/green-driverfix-unit-tests.log)

At gitHead b2b5c65:

```
ChaosControllerInterruptTest > an interrupted controller aborts the schedule loudly before dispatching any action PASSED
LogTailScannerTest > boundary-checked id match still works on whole lines across polls PASSED
LogTailScannerTest > effect line split across two polls is still matched once complete PASSED
WorkloadDriverPacingTest > interrupt at T+2s of a 10s/600-per-min window: generated count stays <= 3x budget, abort is prompt PASSED
WorkloadDriverPacingTest > interrupt pending before the first park: generation aborts at seq 0/1 immediately PASSED
BUILD SUCCESSFUL in 4s
EXIT=0
```

Unit-test justification for the non-unit-tested scopes:
- Scope 5 is logging + driver-config only; `PeriodicChecker`/`MetricsSampler`
  probes need a live `ChaosCluster` (final class, real containers) — verified by
  compile + the smoke console (any unreachable cycle now prints its cause).
- Scope 7 end-to-end needs `ChaosRun.execute` (boots the full cluster); it is
  pinned compositionally: the controller-interrupt unit pin (abort before any
  action) + the pacer-interrupt pins (orchestrator interrupt → loud abort) cover
  both halves of the new death path.

## 4. AFTER smoke

Pattern reused from `soak-after-smoke.log` (task-7-report §10.3): compressed
soak, seed 558112, two back-to-back 8-min SOAK runs in one JVM.

Command:
```
./gradlew :maestro-integration-tests:e2eTest --rerun-tasks \
    -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=8 \
    -Dmaestro.chaos.seed=558112
```
Console: `evidence/task7/soak-after-smoke-postdriverfix.log` (identity header embedded).

Provenance note: the first smoke invocation (started 21:59 SAST as a session
background task) was killed silently at ~22:02 when the session went idle — the
known harness background-task pattern from tasks/lessons.md; the archived clean
run is the coordinator's nohup relaunch via
`.superpowers/sdd/multi-instance/smoke-wrapper.sh` (same command/seed), started
22:29:10 SAST.

### RESULTS — PASS on all criteria (all quotes verbatim from the log)

Identity header (file head):

```
=== ARTIFACT IDENTITY ===
pwd: /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/multi-instance-verification
toplevel: /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/multi-instance-verification
HEAD: b2b5c658a160a690a62c48ce976f3a1d55df3579
branch: worktree-multi-instance-verification
started: Sat Aug  1 22:29:10 SAST 2026
=========================
```

**Sane submission counts, no runaway** — both back-to-back runs paced exactly
on budget, runaway cap (580) never approached:

```
[chaos] mode=SOAK seed=558112 runId=20260801-202918-558112
22:29:57.807 ... WorkloadDriver -- [chaos] workload generation begins: 20/min for PT8M (chaos, runaway cap 580)
22:37:59.187 ... WorkloadDriver -- [chaos] workload generation window closed: 168 workflows submitted (chaos)
[chaos] mode=SOAK seed=558112 runId=20260801-205118-558112
22:51:54.746 ... WorkloadDriver -- [chaos] workload generation begins: 20/min for PT8M (chaos, runaway cap 580)
22:59:56.435 ... WorkloadDriver -- [chaos] workload generation window closed: 168 workflows submitted (chaos)
```

(168 = the identical count of both pre-fix AFTER validations at this seed —
the pacer's deterministic arrivals are unchanged by the fix.) Benchmark tails:
`window closed: 28 workflows submitted (tail6)` / `30 workflows submitted (tail3)`
in both runs, each logged with `runaway cap 190`.

**No CHECKER BLIND** — `grep -cE "BLIND|store unreachable"` over the log = 0
matches; no `!!!` banner; every periodic cycle reached all three databases
through the new fail-fast data sources.

**Chaos actions continue to the end of the window** — 12 `action#` lines
(6 per run), the last action of each run healing seconds after its window
closed, then a clean handoff:

```
22:38:44.425 ... ChaosController -- [chaos] action#6 PAUSE_RESUME LOAN_B started=2026-08-01T20:37:41.360812Z healed=2026-08-01T20:38:44.424716Z
22:38:44.425 ... ChaosController -- [chaos] controller done: 6 actions, 3 loan pause-resumes
22:38:44.425 ... ChaosRun -- [chaos] generation + chaos complete; healing
...
23:00:40.963 ... ChaosController -- [chaos] controller done: 6 actions, 3 loan pause-resumes
```

No `CONTROLLER DIED`, no `GENERATION INTERRUPTED`, no `RUNAWAY` anywhere in
the log.

**Both runs complete with a verdict** — drain reached all-terminal well inside
the 240s SLA in both runs, census clean:

```
22:40:56.532 ... ChaosRun -- [chaos] drain: all workflows terminal in all three services
22:40:56.825 ... SideEffectCensus -- [chaos] side-effect census: totalDup=0 explained=0 unexplained=0 missingSagaComp=0
[chaos] VERDICT: PASS
[chaos] side-effect duplicates: total=0 explained=0 unexplained=0
...
23:02:55.860 ... ChaosRun -- [chaos] drain: all workflows terminal in all three services
[chaos] VERDICT: PASS
[chaos] side-effect duplicates: total=0 explained=0 unexplained=0
[chaos] FINDING (Ruling 3): redelivered-but-unconsumed signals (consumedTwin=true, Kafka at-least-once redelivery): [loan-chaos-558112-29:underwriting.decision x1 consumedTwin=true, loan-chaos-558112-34:verification.result x1 consumedTwin=true]
```

(The run-2 Ruling-3 redelivery finding is the known, informational
at-least-once shape — not a failure.)

Build outcome:

```
Chaos soak: hours-long multi-instance correctness + Issue 12 curves > cluster stays correct across the full soak window PASSED
BUILD SUCCESSFUL in 44m 9s
SMOKE_EXIT=0
```

## 5. Commits

- b8729bf `test(e2e/chaos): RED pins — pacer interrupt runaway, scanner split line, controller interrupt`
- 8550075 `fix(e2e/chaos): interrupt-safe pacer, 3x runaway guard, in-flight bound, O(1) futures`
- dbea374 `fix(e2e/chaos): LogTailScanner keeps partial trailing lines; FileLogConsumer drops post-close frames`
- 467bc09 `fix(e2e/chaos): checker/sampler log their swallowed causes; JDBC probes fail fast`
- b2b5c65 `fix(e2e/chaos): controller interrupt aborts loudly; a dead controller fails the run`
- (this report + smoke evidence + wrapper: docs/evidence commit, SHA recorded in the final reply)

## 6. Fix loop round 1 (delta-review-2: 0 Critical, 3 Important, 7 Minor)

Minors parked for final-review triage per the coordinator. The three Importants:

### I-1 — generation catch missed `Error`s (ChaosRun.java:130)
`catch (RuntimeException)` → `catch (Throwable)` with house-rule rethrow
order: `instanceof Error` checked and rethrown FIRST (a concurrent controller
death attached as suppressed), controller-death `IllegalStateException` next
(generation failure suppressed), `RuntimeException` rethrown as-is, checked
throwables wrapped. The observed disease class was this wave's own RED-run OOM;
pre-fix it skipped the catch, leaving the controller thread alive, unjoined,
and armed to interrupt the Test worker during a LATER test. The catch now only
decides what to throw — teardown moved to the I-2 finally.

### I-2 — no failure-path teardown (checker/sampler/driver/controller leak)
All teardown now lives in a `finally` covering every exit path of the
orchestration: `controllerStopRequested.set(true)` + controller interrupt +
join, `periodic.stop()`, `sampler.stop()` (made idempotent with a `stopped`
guard), `driver.close()`, `controller.close()`, then `Thread.interrupted()` so
no stray controller-death interrupt leaks into the next test. Pre-fix, any of
the new loud aborts left the daemon PeriodicChecker ERROR-spamming
`CHECKER BLIND … cause: …` every 30s through the next run's console in the same
JVM — corrupting the `grep -c BLIND == 0` evidence criterion this cycle
established. Normal-path ordering preserved: `periodic.stop()` still precedes
verify; sampler still runs through the benchmark tail (finally executes after).

### I-3 — silent load truncation under back-pressure
Driver side (2ac7a57): the first delayed arrival WARNs at the moment the
`tryAcquire` fast path fails (deterministic — the old close-time
`availablePermits()==0` peek was racy and could skip); every wait is accounted
(count / max wait / total blocked ms, cross-thread atomics); a throttled window
emits a close-out WARN with submitted-vs-intended numbers; a shed final arrival
is named. Run side (eac200e): `run-summary.json` gains
`generationBackPressure {delayedArrivals, maxWaitMs, totalBlockedMs}` and the
console a `!!! GENERATION WAS BACK-PRESSURED` banner marking the run's Issue 12
curves NOT comparable.

**Decision — shedding does not hard-fail the run, justified:** back-pressure is
the designed survival response to a store stall; the stall itself already gates
the run through I1/drain/checker signals; hard-failing on shedding would abort
exactly the brownouts the harness exists to exercise. The harness principle
("never limp on silently degraded") is honoured by making degradation loud and
machine-checkable — the `!!!` banner + summary fields mean a throttled window
cannot pass unnoticed into benchmark selection. If the coordinator wants a hard
threshold (e.g. submitted < 50% of intended), it is a two-line addition on top
of these fields.

### Verification (soak attempt 3 running in this worktree — no gradle here)

Per instruction, tests ran in an isolated temp worktree
(`git worktree add …/scratchpad/verify-fixloop1 eac200e`, removed after).
Verbatim from `evidence/task7/green-fixloop1-unit-tests.log`:

```
ChaosControllerInterruptTest > an interrupted controller aborts the schedule loudly before dispatching any action PASSED
LogTailScannerTest > boundary-checked id match still works on whole lines across polls PASSED
LogTailScannerTest > effect line split across two polls is still matched once complete PASSED
WorkloadDriverBackPressureTest > normal load never touches the back-pressure accounting PASSED
WorkloadDriverBackPressureTest > a fully back-pressured window is accounted: waits, max wait, blocked time, zero submissions PASSED
WorkloadDriverPacingTest > interrupt at T+2s of a 10s/600-per-min window: generated count stays <= 3x budget, abort is prompt PASSED
WorkloadDriverPacingTest > interrupt pending before the first park: generation aborts at seq 0/1 immediately PASSED
BUILD SUCCESSFUL in 24s
EXIT=0
```

RED-run honesty: no executable pre-fix RED exists for this round — I-1/I-2 live
in `ChaosRun.execute`, which boots the full container cluster (not
unit-testable; unit-level-only constraint), and I-3's accounting surface did
not exist pre-fix to assert against (a test naming the getters cannot compile
at the pre-fix commit). The BackPressure pins hold the contract going forward;
I-1/I-2 are covered structurally plus by the running soak and the next PR gate.

### Round 1 commits
- 2ac7a57 `fix(e2e/chaos): back-pressure is loud and accounted, never silent truncation (I-3 driver side)`
- eac200e `fix(e2e/chaos): every-exit-path teardown, Error-safe generation catch, back-pressure surfacing (I-1, I-2, I-3)`
