# CodeRabbit external-review triage — PR #30

Worktree: `.claude/worktrees/multi-instance-verification`, base HEAD `d6e5d1c`.
Discipline: every finding verified against source before any change; fixes only
for still-valid issues. Commits: `f8baeff` (engine/SPI), `7a57305` (harness),
`b3c26bf` (docs/annotations).

---

## CR-6 — WorkflowStore.deleteFailureEvents Javadoc (engine SPI)

**Verdict: PARTIAL — Javadoc wording VALID, claimed runtime consequence moot; fixed as contract-wording + regression pins.**

**Verification.** All three implementations were read:

- `maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java` (`deleteFailureEvents`,
  ~L313): deletes `sequence_number = (SELECT MAX(sequence_number) ... WHERE
  event_type = 'SIGNAL_TIMEOUT')` — the **highest-sequenced** memo, with no
  adjacency-to-terminal constraint.
- `maestro-test/.../InMemoryWorkflowStore.java` (~L142): `failingTimeoutSeq =
  ...mapToInt(sequenceNumber).max()` — same.
- `maestro-core/src/test/.../TestEventLogs.java` (~L47): same `max()` logic.

So no implementation follows the flawed wording; the defect was confined to the
SPI Javadoc ("the last memo before `WORKFLOW_FAILED`") and a matching
parenthetical in the JDBC store's inline comment. CodeRabbit's consequence
("`$maestro:retry` would rethrow forever") is additionally **unreachable
through the engine today**: `WorkflowExecutor.retryWorkflow` probes for
`COMPENSATION_STARTED` and returns `COMPENSATED_NOT_RETRYABLE` *before* calling
`deleteFailureEvents` (the f6586f1 guard), so the compensations-intervene
scenario never reaches the delete via retry. The guard is documented as
temporary, however, and the SPI contract must stand alone for third-party
stores — the wording fix is warranted.

**Changes.**

- `maestro-core/.../spi/WorkflowStore.java`: the failing-timeout paragraph now
  says "the instance's highest-sequenced `SIGNAL_TIMEOUT` event", states it is
  *not* necessarily the last memo before the terminal (saga compensations
  intervene), and names the `exceptionType` discriminator in the contract.
- `maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java`: inline comment
  parenthetical aligned.
- Regression pins (RED-or-GREEN; all GREEN first run — implementations correct):
  - `InMemoryWorkflowStoreTest.deleteFailureEventsDeletesFailingTimeoutMemoWhenCompensationEventsFollowIt`
  - `PostgresWorkflowStoreTest$EventTests.deleteFailureEvents_deletesFailingTimeoutMemoWhenCompensationEventsFollowIt`
    (real Postgres via Testcontainers — exercises the JDBC SQL)
  - new `maestro-core/.../TestEventLogsTest` (both the compensation-intervene
    and the caught-gate-discriminator directions)

**Pin output (GREEN, first run):**

```
TEST-io.b2mash.maestro.core.TestEventLogsTest.xml:tests="2" skipped="0" failures="0" errors="0"
TEST-io.b2mash.maestro.test.InMemoryWorkflowStoreTest.xml:tests="31" skipped="0" failures="0" errors="0"
TEST-...PostgresWorkflowStoreTest$EventTests.xml:tests="12" skipped="0" failures="0" errors="0"   (new pin present in XML)
BUILD SUCCESSFUL
```

---

## CR-9 — InvariantChecker blind PASS (authoritative verify)

**Verdict: VALID — fixed RED-first.**

**Verification.** Confirmed at source: `queryStrings` (L470-482) and the three
inline query blocks (I3d L220-223, I1 L300-302, I4 L370-372) caught
`Exception`, warned, and returned empty; every caller treats empty as
"invariant holds"; `verifyAuthoritative` against an unreachable store returned
`violations = []` → `ChaosRun` reports PASS. Periodic path
(`PeriodicChecker.loop`) has its own probe-based blindness accounting
(`unreachableCycles`/`maxUnreachableStreak` + CHECKER BLIND escalation), so
soft behavior is correct there and was kept.

**Fix.** Query failures now throw a typed `QueryFailedException` (names
database + query). `verifyAuthoritative` wraps every invariant in `hard(...)`:
a query failure becomes a violation
`"AUTHORITATIVE CHECK BLIND — query failed on <db> (...) -- <sql> — a store
unreachable at verify time is a run failure, not a pass"`. The periodic
entrypoints (`checkAlwaysInexcusable`, `checkStuckWaitingTimer`) wrap in
`soft(...)`: warn, yield no violations, keep checking the stores still
reachable. Dump-path helpers (`queryRows`, `logExcerpts`) stay best-effort.

**RED (pre-fix), `InvariantCheckerBlindnessTest`:**

```
InvariantCheckerBlindnessTest > authoritativeVerifyAgainstUnreachableStoreIsAHardFailureNotAPass() FAILED
    org.opentest4j.AssertionFailedError at InvariantCheckerBlindnessTest.java:45
InvariantCheckerBlindnessTest > periodicChecksStaySoftWhenStoreUnreachable() PASSED
BUILD FAILED
```

**GREEN (post-fix):**

```
InvariantCheckerBlindnessTest > authoritativeVerifyAgainstUnreachableStoreIsAHardFailureNotAPass() PASSED
InvariantCheckerBlindnessTest > periodicChecksStaySoftWhenStoreUnreachable() PASSED
BUILD SUCCESSFUL
```

---

## CR-8 — ChaosRun drain hot loop on interrupt

**Verdict: VALID as a latent-defect class — fixed; the specific reachability route is narrower than claimed.**

**Verification.** Confirmed: `ChaosRun.sleep` (L484-490) caught
`InterruptedException`, re-asserted the flag and returned; the drain loop then
re-queried three databases and the next `sleep(2000)` threw instantly —
full-speed spin until the drain SLA. On reachability: the claimed route
(controller death after `driver.generate` returns) is largely intercepted
before `drain` — `joinQuietly(controllerThread)` + the `controllerFailure`
check at ChaosRun L163-170 run first, and the controller sets the failure
*before* interrupting, so a death detected there throws with the interrupt
cleared. A late-delivered interrupt racing past that check remains possible,
and the pattern is exactly the swallowed-interrupt class the harness documents
elsewhere (`parkNanos`, `ChaosController`), so the fix is warranted regardless.

**Fix.** `ChaosRun.sleep` and its sibling `ChaosCluster.sleep` (used by the
`awaitNodeHealthy` and `awaitConsumerGroup` polling loops — same spin shape)
now re-assert the flag **and** throw
`IllegalStateException("interrupted mid-wait — aborting the wait loop instead
of hot-spinning")`, so drain/heal waits exit promptly and loudly.
`WorkloadDriver.parkNanos`, `MetricsSampler` and `PeriodicChecker` already
handled interrupts correctly (verified; untouched).

---

## CR-7 — ChaosCluster harassment-state truthfulness

**Verdict: VALID — fixed as specified.**

**Verification.** Confirmed at source: `unpause` (L426-431) and `reconnect`
(L443-451) called `set.remove(role)` *before* the Docker op — a throwing
`unpauseContainerCmd`/`connectToNetworkCmd` left the node frozen/disconnected
while `harassedRoles()` reported it live. `replace` (L505-514) removed from
`dead` before `awaitNodeHealthy`. `healAll` (L531-545) had per-item try/catch
only for backends; the node phases aborted on first failure.

**Fix.** `unpause`/`reconnect`: Docker op first, state cleared only after it
returns (matching `unpauseBackend`'s order). `replace`: `dead.remove(role)`
moved after `awaitNodeHealthy`. `healAll`: every backend unpause, node
unpause, reconnect and replace is attempted per-item; failures are collected
and, after all attempts, thrown as
`IllegalStateException("heal-all FAILED to heal: [<role> (<op>: <cause>)...] —
the cluster is still harassed; verifying now would be blind")`. Combined with
CR-9, a heal failure can no longer decay into a blind-PASS verify.

---

## CR-10 — RunIdentity.git interrupt/process hygiene

**Verdict: VALID — fixed as specified.**

**Verification.** Confirmed at source (L59-74): `catch (Exception)` always
called `Thread.currentThread().interrupt()` — an `IOException` from a missing
`git` binary set the interrupt flag on the orchestrating thread, which treats
a set flag as controller death / pacer abort (`WorkloadDriver` aborts
generation on a pending interrupt), aborting the run for an unrelated reason.
`p.waitFor()` was unbounded and the process was never destroyed on failure.

**Fix.** Split catches: only `InterruptedException` re-asserts the flag;
other exceptions return `"unknown"` without touching it. `waitFor(10s)`
bounded (output read after exit — `git rev-parse` output is tiny, and a hung
git can no longer block run start); `finally` destroys any still-alive
process.

---

## CR-5 — soak-driver-fix-report runaway-cap wording

**Verdict: VALID (docs-only) — report aligned with the implementation.**

**Verification.** `WorkloadDriver.generateAt` L184:
`runawayCap = 3 * Math.max(1, Math.round(ratePerMinute * (window.toMillis() / 60_000.0))) + 100`
→ 400 for 600/min over 10s, while the report's Scope 2 said "generation can
never exceed 3x intended load". The pacing pin asserts the stricter
`generated <= 3 * intendedBudget` (300) — that assertion is true (a prompt
abort generates far fewer), so tests and implementation were left untouched.

**Change.** Scope 2 now states the exact `3x max(1, round(...)) + 100`
formula, explains the `+ 100` floor (tiny smoke/tail windows, Poisson
variance), gives the 400-vs-300 distinction explicitly, and explains why the
pin's plain-3x bound is deliberately stricter.

---

## CR-2 — soak-of-record generationBackPressure provenance

**Verdict: VALID (docs-only) — documented; artifact untouched.**

`run-summary.json` of run `20260801-214325--6973268155056049009` omits
`payload.generationBackPressure`: the recorded binary `b2b5c65` predates the
field (added in `8cd2754`). Added a schema-provenance note to the
soak-of-record row in `evidence/task7/INDEX.md` and a caveat 4 to the
"Soak-run provenance and caveats" list in `docs/open-issues.md` (Issue 11):
values unavailable for this run, historical artifact not regenerated, future
soak runs record the field. `run-summary.json` itself not touched.

---

## CR-3 / CR-4 — wrapper scripts (ruling: annotate, don't engineer)

**Verdict: annotated per ruling — no functional changes.**

All six `*-wrapper.sh` files under `.superpowers/sdd/multi-instance/`
(`smoke`, `soak`, `soak-after-smoke`, `final-build`, `final-verify`,
`qa-gate`) now carry the same header after the shebang: archival record of the
exact invocation used during the verification cycle; hardcoded worktree path
intentional (single-use session tooling); consumers read the `*_EXIT=` marker
from the log, wrapper process exit deliberately not meaningful. `bash -n`
clean on all six.

Tracking note: only `smoke-wrapper.sh` — the file CodeRabbit's comments
anchor to — is committed (`.superpowers/sdd/.gitignore` ignores the tree;
files are force-added selectively, per the evidence-tree convention). The
five siblings are local-only; they carry the same header on disk but were
deliberately NOT force-added — expanding the committed evidence surface was
not part of the ruling.

---

## CR-1 — absolute paths in committed evidence

**Verdict: NO CHANGE by ruling.** Absolute paths are the cycle's
artifact-identity convention (QA rejects artifacts by embedded identity —
`RunIdentity` Javadoc, `tasks/lessons.md` binding). Coordinator handles the PR
reply.

---

## Verification of record

Evidence log (identity header + full output):
`.superpowers/sdd/multi-instance/evidence/task9/coderabbit-wave-verify.log`

- `./gradlew :maestro-core:test :maestro-store-jdbc:test :maestro-test:test
  :maestro-integration-tests:test --rerun-tasks` → `TARGETED_EXIT=0`
- `./gradlew build` → `FULL_BUILD_EXIT=0`
