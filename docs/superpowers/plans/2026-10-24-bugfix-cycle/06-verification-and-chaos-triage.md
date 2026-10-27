# Verification and Chaos Triage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the project's only live chaos/E2E signal, turn the seven untriaged chaos-harness failures (I1, I4) into classified, reproducible findings mapped onto Plans 01–05 (or filed as new ledger issues), de-flake the three integration suites that reddened `Build & Test`, bring the test-plan documents back to the real inventory, close the sample exclusion in the coverage gate, and gate the whole bug-fix branch with identity-stamped evidence.

**Architecture:** Six tasks. Task 1 is independent of every fix and runs first (re-enable the schedule, make it immune to GitHub's inactivity rule, dispatch a baseline run of `main` so fresh evidence artifacts exist — the old ones have expired). Task 2 is an investigation with a written template, not a guessed fix. Task 3 fixes one real ordering race and proves two already-fixed races stay fixed. Tasks 4–5 are documentation and build-gate hygiene. Task 6 runs LAST, after Plans 01–05 have landed on the branch, and records the whole-branch gate.

**Tech Stack:** GitHub Actions + `gh` CLI, Gradle 9 Kotlin DSL, JUnit 5, Testcontainers 2 (the chaos harness boots a six-node Docker cluster from the loan-origination boot jars), bash.

**Spec:** [`00-spec-independent-assessment.md`](00-spec-independent-assessment.md) §3b (chaos evidence) and §6 (verification maturity); `README.md` (this folder) row 6.

## Global Constraints

See `README.md` § Global Constraints. In addition, for this plan only:

- Evidence files written by this plan carry an identity header — `pwd`, `git rev-parse HEAD`, UTC timestamp — written *inside the file at write time* (`tasks/lessons.md`, "Shared scratchpads need artifact identity"). Every number quoted in a report must be greppable from an archived log (`tasks/lessons.md`, "Every quoted number must be greppable").
- No sleep or timeout is lengthened to make a test pass (`tasks/lessons.md`: "No sleeps and no lengthened timeouts — those only move the odds").
- Nothing under `maestro-integration-tests/.../e2e/chaos/` changes invariant semantics (I1–I5). The harness is the oracle; this plan reads it, it does not tune it.
- Long-running commands (anything over ~5 minutes: chaos runs, E2E, 10× loops) are launched as a detached `nohup` wrapper script writing to an identity-stamped log, then watched with bounded foreground polls (`tasks/lessons.md`, 2026-08-02 close-out, first bullet).

## Review Focus

1. **A seed reproduces decisions, not timing.** `ChaosConfig.seed()` fixes the PRNG split (`ChaosRun.java:84-86`) but Docker, Kafka rebalance and Poisson arrival timing are not seeded, so a local re-run with a CI seed may pass. Task 2 must not conclude "did not reproduce ⇒ fixed": its rule is three local attempts, then the fresh-dispatch artifacts from Task 1 are the evidence of record. Pinned in Task 2 Step 6.
2. **Plan 04's durable `awaitSignal` deadline (C12) is stricter than today's behaviour.** Today a `KILL9` of the loan node mid-decision-wait re-arms the 120 s wall-clock timeout from scratch (`SignalManager.java:359`); a durable deadline anchored at first arm will not. If I1's `WORKFLOW_FAILED` payloads turn out to be `SignalTimeoutException` at the `underwriting.decision` await, I1 may become *more* frequent after Plan 04. Task 2's report records "elapsed since first arm vs configured timeout" for every I1 workflow so Task 6 can tell a C12 regression from a chaos budget collision. Pinned in Task 2 Step 5 (classification table row 1) and Task 6 Step 7.
3. **Expired evidence.** All seven failing runs' artifacts have expired (14-day retention; verified 2026-10-24: `gh api .../runs/<id>/artifacts` returns an empty list for every one). Only the step logs remain, and GitHub keeps those 90 days: run 31288365087 (2026-08-09) expires on or about 2026-11-07. Task 2 Step 1 archives all seven logs *before anything else in this plan runs*.
4. **The re-enable job must not run with a read-only token.** `gh workflow enable` needs `actions: write`; on `pull_request` from a fork `GITHUB_TOKEN` is read-only and the step would fail every PR. Task 1 scopes the job to `if: github.event_name == 'push'` and declares `permissions: actions: write` on that job only. Pinned in Task 1 Step 5.
5. **New ITs from Plans 01–05 must gate on the terminal *event*, not the status.** Two of the three flaky suites in Task 3 failed exactly this way (commit 6296427 explains the two-write window). Task 6 Step 2 greps every new `*IT.java` on the branch for `awaitStatus(` / `getStatus() == ` followed by a log read and fails the gate if one is found without `TerminalWait`.

---

## Evidence gathered 2026-10-24 (do not re-fetch; cite this section)

Fetched read-only from GitHub on 2026-10-24 against `main` @ 4eb28e9. Implementers cite these lines; they re-fetch only what Task 2 Step 1 archives.

### E2E workflow state

```
$ gh workflow list --all
Build & Test             active                253105934
E2E (loan-origination)   disabled_inactivity   322504643
Javadoc                  active                253111029
Release to Maven Central active                253111030
```

`gh workflow list` without `--all` hides the disabled workflow — this is why the outage went unnoticed. Last scheduled run: 38018334537 on 2026-10-10 (success). Every run since 2026-08-11 is `event=schedule` at `headSha=66cdf95`; nothing since 2026-10-10. The yml already declares `workflow_dispatch:` (`.github/workflows/e2e-nightly.yml:21`), so no trigger needs adding.

### The seven failing nightly runs (all at 66cdf95 except the first at 60e7789)

Workflow IDs embed `Long.toUnsignedString(seed)` (`WorkloadDriver.java:213`), so a negative seed appears as its unsigned twin in the ids (e.g. seed `-891813452390949498` ⇒ prefix `17554930621318602118`).

| Run | Date | Job that failed | Seed (`[chaos] mode=… seed=…`) | Violations (verbatim from `gh run view <id> --log-failed`) |
|---|---|---|---|---|
| 31288365087 | 2026-08-09 | Chaos soak (2h) | `-5682539354314886163` | `VIOLATION [I1] terminal status != declared expectation -> [loan-chaos-12764204719394665453-1153 (expected=COMPLETED, actual=FAILED, path=CONDITIONS_LOOP)]` · `VIOLATION [I1] output.status != declared expectation -> [loan-chaos-12764204719394665453-1153 (expected output=FUNDED)]` |
| 31919632989 | 2026-08-16 | Chaos soak (2h) | `7671915568149341571` | `VIOLATION [I1] terminal status != declared expectation -> [loan-chaos-7671915568149341571-500 (expected=COMPLETED, actual=FAILED, path=HAPPY)]` · `VIOLATION [I1] output.status != declared expectation -> [loan-chaos-7671915568149341571-500 (expected output=FUNDED)]` |
| 33285262356 | 2026-08-30 | Chaos soak (2h) | `8117718602935604897` | `VIOLATION [I1] terminal status != declared expectation -> [loan-chaos-8117718602935604897-669 (expected=COMPLETED, actual=FAILED, path=CONDITIONS_LOOP)]` · `VIOLATION [I1] output.status != declared expectation -> [loan-chaos-8117718602935604897-669 (expected output=FUNDED)]` |
| 33463346389 | 2026-09-01 | Chaos PR-gate (iteration 1 of 3) | `-970181608425652116` | `VIOLATION [I1] terminal status != declared expectation -> [loan-chaos-17476562465283899500-41 (expected=COMPLETED, actual=FAILED, path=HAPPY), loan-chaos-17476562465283899500-39 (expected=COMPLETED, actual=FAILED, path=HAPPY)]` · `VIOLATION [I1] output.status != declared expectation -> [...-41 (expected output=FUNDED), ...-39 (expected output=FUNDED)]` · `VIOLATION [I4] unconsumed application signal with NO consumed twin for COMPLETED workflow (loan DB) — genuinely missed signal -> [loan-chaos-17476562465283899500-2:document.uploaded x1 consumedTwin=false]` |
| 34668175046 | 2026-09-12 | Chaos PR-gate (iteration 2 of 3; iteration 1, seed `-6839934821064559381`, passed) | `-3264688620830235656` | `VIOLATION [I4] unconsumed application signal with NO consumed twin for COMPLETED workflow (loan DB) — genuinely missed signal -> [loan-chaos-15182055452879315960-42:document.uploaded x3 consumedTwin=false]` |
| 35481047255 | 2026-09-20 | Chaos soak (2h) | `7736940657655325443` | `VIOLATION [I1] terminal status != declared expectation -> [loan-chaos-7736940657655325443-1362 (expected=COMPLETED, actual=FAILED, path=HAPPY), ...-1352 (HAPPY), ...-1360 (HAPPY), ...-1930 (HAPPY), ...-2024 (expected=COMPLETED, actual=FAILED, path=CONDITIONS_LOOP)]` · `VIOLATION [I1] output.status != declared expectation -> [...-1362, ...-1352, ...-1360, ...-1930, ...-2024 (expected output=FUNDED)]` · `VIOLATION [I4] ... -> [loan-chaos-7736940657655325443-287:document.uploaded x1 consumedTwin=false]` |
| 37169821147 | 2026-10-04 | Chaos soak (2h) | `-891813452390949498` | `VIOLATION [I1] terminal status != declared expectation -> [loan-chaos-17554930621318602118-108 (expected=COMPLETED, actual=FAILED, path=HAPPY), loan-chaos-17554930621318602118-1832 (expected=COMPLETED, actual=FAILED, path=CONDITIONS_LOOP)]` · `VIOLATION [I1] output.status != declared expectation -> [...-108 (expected output=FUNDED), ...-1832 (expected output=FUNDED)]` · `VIOLATION [I4] ... -> [loan-chaos-17554930621318602118-2172:document.uploaded x1 consumedTwin=false, loan-chaos-17554930621318602118-2175:document.uploaded x1 consumedTwin=false]` |

Every run also logged `[chaos] side-effect duplicates: total=0 explained=0 unexplained=0`.

**Pattern.** I1 fires only on the two paths whose declared terminal is `COMPLETED`/`FUNDED` (`HAPPY`, `CONDITIONS_LOOP` — `LoanPath.java:15-18`); `SAGA_WITHDRAWAL` and `SIGNAL_TIMEOUT` never violate. I1 appears in **5 of 5** failed soaks (1–5 workflows per ~2,400) and 1 of 2 failed PR-gates; the soak has not been green since 2026-08-02. I4 is always `document.uploaded`, always `consumedTwin=false`, always on a `COMPLETED` workflow — the workflow finished `FUNDED` while a document signal it was sent sat unconsumed. The `x3` case (09-12) is a workflow with *three* unconsumed document rows and none consumed with matching bytes, yet `COMPLETED`.

**What the CI logs do and do not contain.** They contain the chaos action timeline — one line per action, e.g. `[chaos] action#3 KILL9 UW_A started=2026-10-04T02:08:43.908494262Z healed=2026-10-04T02:09:…` (`ChaosController.java:152-199` via `record(...)`), with mix in the 10-04 soak of 24 `KILL9`, 23 `PARTITION`, 22 `BACKEND_OUTAGE` ChaosCluster lines plus `PAUSE_RESUME` and `ROLLING_RESTART` controller lines. They do **not** contain per-workflow submit times, the `WORKFLOW_FAILED` payload or signal rows — those were in `ledger.jsonl` and `failures/<workflowId>/{instance,events,signals,timers,ledger}.json` (`InvariantChecker.java:463-480`) inside the expired artifacts. Correlating a violation with the action that caused it therefore needs a fresh run (Task 1 Step 7, Task 2 Step 3).

### The three `Build & Test` failures

| Run | Date / ref | Failing test | Assertion | Verdict at HEAD |
|---|---|---|---|---|
| 30396734228 | 2026-07-28, PR branch `test/integration-suite-p0-p6` @ d8182b2 | `EnginePostgresSagaIT.sagaStepFailure_compensatesInLifoOrderAndFails` | `AssertionFailedError at EnginePostgresSagaIT.java:105` = `assertEquals(1, eventsOfType(events, EventType.WORKFLOW_FAILED).size())` (file unchanged since e818636, so the line is the same at HEAD) | Terminal-event race: `handle.awaitTerminal(BOUND)` returned on status `FAILED` before `WORKFLOW_FAILED` was appended. Closed by 6296427 (2026-08-03): `WorkflowHandle.awaitTerminal` now delegates to `TerminalWait.awaitTerminal` (`support/WorkflowHandle.java:115-117`), which requires the run-closing event. Verify only (Task 3). |
| 30728290264 | 2026-08-02, `main` @ 7bfedbe | `EnginePostgresParallelIT.parallelReplay_resumesOnlyTheUnfinishedBranch` | `AssertionFailedError at EnginePostgresParallelIT.java:149` (at 7bfedbe) = the five-event `describe(...)` list; 6296427's message: "expected five events and read four, the missing one being 5001:WORKFLOW_COMPLETED" | Same race, same fix: test now calls `awaitTerminal(workflowId, Duration.ofSeconds(15))` (`EnginePostgresParallelIT.java:134`) from `PostgresIntegrationSupport:137`. Verify only (Task 3). |
| 30773436894 | 2026-08-03, `main` @ 15b27dc | `RecoveryPassIsolationTest.throwingOnOneInstanceEndsTheWholePass` | `AssertionFailedError at RecoveryPassIsolationTest.java:119` = `assertEquals(0, runs.get(), ...)` (file unchanged since 4d3d884, so the line is the same at HEAD) | **Still racy.** `PoisonableStore.getRecoverableInstances()` (`RecoveryPassIsolationTest.java:211-217`) sorts *ascending* by `workflowId` (`:215`), so `"healthy-wf"` precedes `"poisoned-wf"` — the opposite of its own comment "Ordered so the poisoned instance is adopted FIRST". `recoverWorkflows` (`WorkflowExecutor.java:504-519`) therefore launches the healthy workflow on a virtual thread (`launchWorkflow`, `WorkflowExecutor.java:1377`) *before* the poisoned `getInstance` re-read throws (`WorkflowExecutor.java:1345`); `runs.incrementAndGet()` races the assertion and loses on a loaded CI runner. Fix in Task 3. |

A fourth failure on 2026-07-28 (run 30340391726, PR branch @ 4860f19) was `TestWorkflowEnvironmentTest.advanceTimeFiresSleepTimer` (`TimeoutException at TestWorkflowEnvironmentTest.java:107`); `TestWorkflowHandle` was swept by 6296427 and 157c43b. Task 3's loop includes it as a free check.

### Test inventory (counted 2026-10-24, `main` @ 4eb28e9)

Commands: `find <module>/src/test/java \( -name '*Test.java' -o -name '*IT.java' \) | wc -l` and `grep -rhE '^\s*@(Test|ParameterizedTest|RepeatedTest)\b' <module> --include='*.java' | wc -l`.

| Module | Test classes | `@Test` methods |
|---|---|---|
| maestro-core | 48 | 412 |
| maestro-test | 6 | 58 |
| maestro-spring-boot-starter | 15 | 109 |
| maestro-store-jdbc | 2 | 11 |
| maestro-store-postgres | 5 | 66 |
| maestro-messaging-kafka | 11 | 59 |
| maestro-messaging-postgres | 3 | 19 |
| maestro-lock-postgres | 3 | 25 |
| maestro-lock-valkey | 4 | 32 |
| maestro-admin | 3 | 20 |
| maestro-admin-client | 5 | 22 |
| maestro-integration-tests | 41 (35 `*IT`, 6 `*Test`; `multinode/` = 5 suites / 14 tests; `e2e/chaos/` = 11 classes) | 116 |
| maestro-samples (loan-origination: 2 + 1 + 1; order-service, payment-gateway, postgres-only: 0) | 4 | 24 |
| **Total** | **150** | **973** |

`docs/test-plan.md` currently says "16 unit test classes" (`:71`), "(3 test classes)" for the starter (`:93`), "(2 test classes)" for Kafka (`:104`), "(3 classes)" for maestro-test (`:129`), credits `RabbitMqWorkflowMessagingTest` (`:110`, `:131`) for a module deleted in 8f8a36d (2026-08-02), and its CI-wiring bullet (`:239-241`) names the deleted module too.

---

### Task 1: Re-enable the nightly schedule, make it immune to the inactivity rule, dispatch a baseline

**Files:**
- Modify: `.github/workflows/build-test.yml:12-39` (add a second job)
- Modify: `.github/workflows/e2e-nightly.yml:1-8` (header comment)
- Modify: `docs/test-plan.md:236-241` (§ CI wiring)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: a run id `BASELINE_RUN_ID` (the `workflow_dispatch` run of `main` started in Step 7) recorded in `docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/baseline-run.txt`; Task 2 downloads its artifacts.

Decision (coordinator): documented approach plus `workflow_dispatch`. `workflow_dispatch` already exists (`e2e-nightly.yml:21`). A *scheduled* keepalive cannot work — GitHub disables every scheduled workflow in a repository with no commits for 60 days, the keepalive included. The one mechanism that is actually immune is a `push`-triggered re-enable: the first commit after dormancy turns the schedule back on. This task adds that, re-enables now, and documents the rule.

- [ ] **Step 1: Re-enable the workflow and prove the state flipped**

Run: `gh workflow enable e2e-nightly.yml && gh workflow list --all | grep 'E2E'`
Expected: `E2E (loan-origination)   active   322504643`

- [ ] **Step 2: Write the failing check for the new CI job**

There is no unit test for a workflow file; the check is `actionlint`-free and structural. Run from repo root:

```bash
grep -c 'gh workflow enable e2e-nightly.yml' .github/workflows/build-test.yml
```

Expected: `0` (the job does not exist yet).

- [ ] **Step 3: Add the re-enable job to `build-test.yml`**

Append after the `build` job (`build-test.yml:39`), as a separate job so the build job's `permissions: contents: read` is untouched:

```yaml
  # GitHub disables every scheduled workflow after 60 days without a commit
  # (that is what took E2E (loan-origination) offline from 2026-10-10 until
  # this cycle). A scheduled keepalive would be disabled with it; a push is
  # the only event that proves the repository is alive, so the first push
  # after a dormant period turns the nightly schedule back on.
  keep-nightly-enabled:
    name: Re-enable the nightly E2E schedule
    if: github.event_name == 'push'
    runs-on: ubuntu-latest
    permissions:
      actions: write
    steps:
      - name: Enable E2E (loan-origination) if GitHub disabled it
        env:
          GH_TOKEN: ${{ github.token }}
          GH_REPO: ${{ github.repository }}
        run: gh workflow enable e2e-nightly.yml
```

- [ ] **Step 4: Run the structural check again**

Run: `grep -c 'gh workflow enable e2e-nightly.yml' .github/workflows/build-test.yml`
Expected: `1`

- [ ] **Step 5: Pin the token scoping (Review Focus 4)**

Run: `awk '/keep-nightly-enabled:/,0' .github/workflows/build-test.yml | grep -E "if: github.event_name == 'push'|actions: write" | wc -l`
Expected: `2`

- [ ] **Step 6: Document the rule where CI is described**

In `.github/workflows/e2e-nightly.yml:1-8` header comment add one sentence: the schedule is subject to GitHub's 60-day inactivity disable; `build-test.yml`'s `keep-nightly-enabled` job re-enables it on every push to `main`; manual recovery is `gh workflow enable e2e-nightly.yml`; `gh workflow list --all` (not `gh workflow list`) shows disabled workflows.

In `docs/test-plan.md` § CI wiring (`:236-241`) replace the two stale bullets with three: (1) `test` runs unit + `@Tag("integration")` suites on every PR, `e2eTest` is excluded from `build` (`build-logic/src/main/kotlin/maestro.integration-test-conventions.gradle.kts:18-39`); (2) nightly/weekly schedule in `e2e-nightly.yml`, the 60-day rule, the re-enable job and the `--all` flag; (3) the coverage gate sentence, rewritten by Task 5 Step 6 (leave a `<!-- Task 5 -->` marker for it here).

- [ ] **Step 7: Dispatch a baseline run of unchanged `main` and record its id**

The dispatch runs all four jobs (`e2e-nightly.yml:48,86,122,170` — every `if` admits `workflow_dispatch`), ~3.5 h of CI wall-clock with fresh 14-day artifacts. This is the only way to get `failures/` dumps for Task 2, because the old artifacts are gone (Review Focus 3).

```bash
gh workflow run e2e-nightly.yml --ref main
sleep 20
mkdir -p docs/superpowers/plans/2026-10-24-bugfix-cycle/triage
{ echo "# pwd=$(pwd) head=$(git rev-parse HEAD) written=$(date -u +%FT%TZ)";
  gh run list --workflow e2e-nightly.yml --event workflow_dispatch --limit 1 --json databaseId,createdAt,headSha; } \
  > docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/baseline-run.txt
cat docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/baseline-run.txt
```

Expected: a JSON array with one object whose `headSha` starts `4eb28e9`; that `databaseId` is `BASELINE_RUN_ID`.

- [ ] **Step 8: Commit**

```bash
git add .github/workflows/build-test.yml .github/workflows/e2e-nightly.yml docs/test-plan.md \
        docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/baseline-run.txt
git commit -m "ci: re-enable the nightly E2E schedule on every push; document GitHub's 60-day inactivity rule

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Triage the I1 and I4 chaos violations as a reproducible investigation

**Files:**
- Create: `docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/ci-logs/<runId>.log` (×7)
- Create: `docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/ViolationTriageReport.md`
- Create: `docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/evidence/<runId>/` (downloaded artifacts; add `evidence/` to the folder's `.gitignore` if any single run exceeds 5 MB — commit the `failures/` subtrees and `chaos-actions.jsonl`, never node logs)
- Modify (conditional): `docs/open-issues.md` (new `### Issue 29 — …` section(s); **Issue 28 is already taken** by Plan 01 Task 3's structural sequence-space entry, so this plan numbers from 29; after `:2341-2408`), `docs/open-issues.md` §6 "Suggested order" (`:2409`)

**Interfaces:**
- Consumes: `BASELINE_RUN_ID` from Task 1 Step 7; the harness's evidence layout — `ledger.jsonl` (one `LedgerEntry` per workflow: `workflowId`, `path`, `expectedTerminal`, `expectedOutput`, submit timestamp, `notes`), `chaos-actions.jsonl` (`seq`, `type`, `target`, `startTs`, `healStart`, `healedTs`), `failures/violations.json`, `failures/<workflowId>/{instance,events,signals,timers,ledger}.json` and `node-log-excerpts.txt` (`InvariantChecker.java:443-480`, `EvidenceWriter.java:88-166`); every file starts with the identity header.
- Produces: `ViolationTriageReport.md` with one filled block per violating workflow and a classification per violation; zero or more new ledger issues numbered from 28, each with a task stub appended to the sibling plan that owns the code (or to this file's Task 6 Step 7 if none does).

- [ ] **Step 1: Archive the seven CI logs now (Review Focus 3)**

```bash
cd /Users/rakheendama/Projects/2026/maestro
d=docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/ci-logs; mkdir -p $d
for r in 31288365087 31919632989 33285262356 33463346389 34668175046 35481047255 37169821147; do
  { echo "# source=gh run view $r --log-failed  repo=$(gh repo view --json nameWithOwner -q .nameWithOwner)  archived=$(date -u +%FT%TZ)  by_head=$(git rev-parse HEAD)";
    gh run view $r --log-failed; } > $d/$r.log
done
grep -c 'VIOLATION \[I' $d/*.log
```

Expected: `31288365087.log:2`, `31919632989.log:2`, `33285262356.log:2`, `33463346389.log:3`, `34668175046.log:1`, `35481047255.log:3`, `37169821147.log:3`. If a count differs from the Evidence table above, the table is wrong — fix the table, not the log.

- [ ] **Step 2: Write the triage report template**

Create `triage/ViolationTriageReport.md` with this skeleton (identity header first; one `###` block per violating workflow id; the two tables filled in Steps 4–5):

```markdown
# Chaos violation triage — I1 / I4
<!-- pwd= head= written= (fill at write time) -->

## Sources
| Run | Date | Mode | Seed | Log | Artifacts |
(one row per run in the Evidence table; Artifacts = "expired" or the evidence/<runId> path)

## Per-workflow findings
### <workflowId>
- Run / seed / mode:
- Path (ledger) / declared expectation (status, output):
- Ledger notes (`notes[]` from ledger.json — e.g. `doc-not-landed:<type>`, `decision-round-1-APPROVED-not-consumed`):
- Submitted at (ledger) → terminal at (`instance.json: completed_at`):
- Chaos actions overlapping [submitted, terminal] (from chaos-actions.jsonl: `#seq TYPE target startTs→healedTs`):
- Last event seq / type (events.json):
- I1 only — WORKFLOW_FAILED payload: `exceptionType`, `message`, `stepName` of the preceding event; the await it died in and elapsed-since-first-arm vs configured timeout (120 s; Review Focus 2):
- I4 only — signal rows (signals.json): for each `document.uploaded` row: id, consumed, received_at, payload bytes; which consumed rows are byte-identical duplicates of each other; the `collectSignals` count the workflow needed (`application.requiredDocs().size()` or `decision.conditions().size()`):
- Classification (table below) and the planned fix it maps to:

## Classification
| Violation | Workflows | Class | Maps to | Evidence line |
```

- [ ] **Step 3: Obtain fresh evidence — download the baseline artifacts, then reproduce locally with the CI seeds**

Download (as soon as the baseline run finishes; `gh run watch $BASELINE_RUN_ID` is acceptable in a bounded foreground call):

```bash
cd /Users/rakheendama/Projects/2026/maestro
BASELINE_RUN_ID=$(jq -r '.[0].databaseId' <(tail -n +2 docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/baseline-run.txt))
gh run view $BASELINE_RUN_ID --log-failed | grep 'VIOLATION \[I'
mkdir -p docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/evidence/$BASELINE_RUN_ID
gh run download $BASELINE_RUN_ID -n chaos-soak-evidence   -D docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/evidence/$BASELINE_RUN_ID/soak
gh run download $BASELINE_RUN_ID -n chaos-pr-gate-evidence -D docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/evidence/$BASELINE_RUN_ID/pr-gate
ls docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/evidence/$BASELINE_RUN_ID/*/*/failures/ 2>/dev/null
```

Expected: at least one `failures/<workflowId>/` directory (the soak has violated 5 of 5 times). If the baseline run is wholly green, dispatch once more (`gh workflow run e2e-nightly.yml --ref main`) and record the second id in `baseline-run.txt`; stop after two green dispatches and record "not reproduced in CI on 2026-xx-xx (2 dispatches)".

Local reproduction with the two PR-gate seeds (seeding exists: `-Dmaestro.chaos.seed`, `ChaosConfig.java:55`; `maestro.chaos.evidenceDir` relocates the evidence, `ChaosConfig.java:78-80`). Each run is ~10 min and needs Docker; launch detached:

```bash
cat > /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/repro.sh <<'EOF'
#!/usr/bin/env bash
set -u
cd /Users/rakheendama/Projects/2026/maestro
out=docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/evidence/local
mkdir -p "$out"
for seed in -3264688620830235656 -970181608425652116; do
  for attempt in 1 2 3; do
    log="$out/prgate-seed${seed}-attempt${attempt}.log"
    { echo "# pwd=$(pwd) head=$(git rev-parse HEAD) started=$(date -u +%FT%TZ) seed=$seed attempt=$attempt"; } > "$log"
    ./gradlew :maestro-integration-tests:e2eTest --rerun-tasks --console=plain \
      -Dmaestro.chaos.seed=$seed -Dmaestro.chaos.evidenceDir="$(pwd)/$out/chaos-evidence" >> "$log" 2>&1
    echo "# exit=$? finished=$(date -u +%FT%TZ)" >> "$log"
    grep -q 'VIOLATION \[I' "$log" && break
  done
done
EOF
chmod +x /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/repro.sh
nohup /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/repro.sh > /dev/null 2>&1 &
```

Expected: between 2 and 6 log files; `grep -l 'VIOLATION \[I' evidence/local/*.log` lists the attempts that reproduced (possibly none — Review Focus 1). Each reproducing attempt has a `chaos-evidence/<runId>/failures/` tree.

- [ ] **Step 4: Fill one report block per violating workflow**

For every `failures/<workflowId>/` directory found in Step 3 (CI download and local), fill the block from the template using exactly these sources: `ledger.json` → path, expectation, notes, submit time; `instance.json` → `status`, `output`, `completed_at`, `event_sequence`; `events.json` → last seq, and for I1 the `WORKFLOW_FAILED` row's `payload.exceptionType`/`payload.message` and the row before it; `signals.json` → for I4 the per-row `consumed`, `received_at`, `payload`; `../../chaos-actions.jsonl` → every action whose `[startTs, healedTs]` overlaps `[submit, completed_at]`. Compute "byte-identical duplicates among consumed rows" with `jq -r '.[] | select(.consumed==true) | .payload' signals.json | sort | uniq -d`.

Expected: every block has no empty field, and every quoted value is greppable in the cited JSON file (`tasks/lessons.md`).

- [ ] **Step 5: Classify each violation against Plans 01–04 using this table**

| # | Observation in the dumps | Class | Maps to |
|---|---|---|---|
| 1 | I1; `exceptionType=SignalTimeoutException`; the `underwriting.decision` (`LoanApplicationWorkflow.java:207-208`) or `verification.result` (`:138-139`) row **is present** in `signals.json` with `received_at` **earlier** than the `SIGNAL_TIMEOUT` event's time, `consumed=false` | engine: signal arrived in time, wake lost, recheck did not consume before the wall-clock deadline | Plan 04 (C14 lost-wakeup window / wake-recheck; C12 durable deadline) |
| 2 | I1; `SignalTimeoutException`; the decision row is **absent** or `received_at` **later** than the timeout event; overlapping actions include `KILL9`/`PAUSE_RESUME`/`PARTITION` of `UW_*` or `VERIFY_*`, or a `BACKEND_OUTAGE kafka` | upstream genuinely late under chaos — sample timeout budget (120 s, `ChaosCluster.java:329-332`) vs action durations (pause 45–75 s, `ChaosController.java:158`; child double-timeout ≈ 2:35, `ChaosConfig.java:95-100`) | **no engine fix.** File as Issue 29 "harness: declared expectation not chaos-tolerant" only if ≥ 2 workflows land here; otherwise record as calibration finding in the report and in `docs/operations.md` §8. Flag Review Focus 2 for Plan 04. |
| 3 | I1; `exceptionType` is `OptimisticLockException`, `IllegalStateException` mentioning sequence/`parallel`, or a compensation failure, on a path that never compensates | engine | Plan 01 (C1–C4) or Plan 02 (C8/C9 — escaped `Error`, replay misclassification); cite the finding by `exceptionType` |
| 4 | I1; row `FAILED` but `events.json` has **no** `WORKFLOW_FAILED`, or has `WORKFLOW_COMPLETED` | engine: non-atomic terminal write (Issue 26) | Plan 04 (C13/#26) |
| 5 | I4; among the **consumed** `document.uploaded` rows there are byte-identical duplicates (`uniq -d` non-empty) and the unconsumed row(s) are the doc types missing from the consumed set; `collectSignals(N, …)` (`LoanApplicationWorkflow.java:92-93`, `:194-195`) was satisfied by N rows that include a duplicate | engine semantics: `collectSignals` N-of-M counts an at-least-once redelivery of signal A as a second member, so a later distinct signal B is never consumed and the workflow completes with the wrong N. The sample dedups signatures by `signerId` (`:220-246`) precisely because of this, but not documents. | **no planned fix** → Issue 29: "`collectSignals` satisfies N-of-M with byte-identical redelivered rows" (engine) — stub to Plan 04 as a new task (consume-time dedup of byte-identical unconsumed rows for the same `(workflow_id, signal_name)`, with a unit pin on the fakes and an `@Tag("integration")` pin that re-posts one document twice and asserts the third distinct document is consumed). |
| 6 | I4; no consumed duplicates; the unconsumed row's `received_at` is **after** `completed_at` | signal delivered to a terminal workflow (driver re-post after the fan-in closed; the driver's store-check at `WorkloadDriver.java:415-424` lost to a `BACKEND_OUTAGE postgres`) | Plan 04 (C14: signals to terminal workflows) — and I4's query should then be re-read, not changed: the harness still reports it, the report explains it |
| 7 | I4; no consumed duplicates; `received_at` **before** `completed_at`; the doc-collection await ended by `SIGNAL_TIMEOUT` and the workflow still reached `FUNDED` | sample proceeds past a timed-out `collectSignals` (check whether `LoanApplicationWorkflow.java:92` is inside a `catch (SignalTimeoutException)`) | sample defect → Plan 05 (sample accuracy) task stub; not engine |
| 8 | anything else | unmapped | Issue 29+ |

Fill the `## Classification` table with one row per violation (I1 status, I1 output, I4) per run, naming the class number and the plan/finding. The I1 "output.status" violation is a consequence of the status violation for the same ids and takes the same class.

- [ ] **Step 6: Record non-reproduction honestly (Review Focus 1)**

If Step 3 produced no `failures/` tree at all (two green CI dispatches *and* six green local attempts), the report's Classification table gets one row: `not reproduced; last observed 2026-10-04 run 37169821147; evidence expired; re-triage from the first Task 6 violation` — and Task 6 Step 7 becomes the pin. Do not classify from the CI log lines alone.

- [ ] **Step 7: File any unmapped class as a ledger issue**

For each row classified 5 or 8 (and 2 if its threshold is met): append to `docs/open-issues.md` after Issue 27 (`:2341-2408`) a section in the ledger's exact shape — `### Issue 29 — <title> {#issue-29}`, a `> **Open.** Filed from …` blockquote naming the run ids and the report path, then `**Kind:**`, `**Severity:**`, `**What's wrong.**`, `**Where.**` (file:line), `**Proposed fix (sketch, not committed to).**` — and add the issue to §6 "Suggested order" (`:2409`). Add a one-paragraph task stub titled `### Task N+: Issue 29 — …` at the end of the sibling plan that owns the code (Plan 04 for class 5), with Files / Interfaces / the two pins the Global Constraints require.

Run: `grep -c '^### Issue 2[89]' docs/open-issues.md`
Expected: equals the number of unmapped classes found (possibly `0`).

- [ ] **Step 8: Commit the archive, report and any issues**

```bash
git add docs/superpowers/plans/2026-10-24-bugfix-cycle/triage docs/open-issues.md
git commit -m "docs(triage): archive the seven chaos-failure logs; classify I1/I4 against the bug-fix plans

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: De-flake the three `Build & Test` suites

**Files:**
- Modify: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/RecoveryPassIsolationTest.java:84-123` (both tests) and `:211-217` (`PoisonableStore.getRecoverableInstances`)
- Verify only: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/EnginePostgresSagaIT.java`, `EnginePostgresParallelIT.java`, `maestro-test/src/test/java/io/b2mash/maestro/test/TestWorkflowEnvironmentTest.java`
- Create: `docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/flaky-10x.log` (evidence)

**Interfaces:**
- Consumes: `WorkflowExecutor.recoverWorkflows(Map<String, WorkflowRegistration>)` (`WorkflowExecutor.java:504`) — iterates `store.getRecoverableInstances()` in list order, calls `resumeWorkflow` (`:470`) → `launchWorkflow` (`:1311`), which re-reads `store.getInstance(workflowId)` synchronously (`:1345`) before starting the virtual thread (`:1377`). Plan 02 may touch `recoverWorkflows` (C9 stand-down taxonomy); re-read it before editing.
- Produces: nothing other tasks consume.

- [ ] **Step 1: Write the failing precondition assertion**

Add to `throwingOnOneInstanceEndsTheWholePass()` immediately after the `store.invisibleTo(...)` call (`RecoveryPassIsolationTest.java:110-113`) and before the `assertThrows` — after, not before, because Step 3's comparator keys on the poison predicate that `invisibleTo` sets:

```java
assertEquals(List.of("poisoned-wf", "healthy-wf"),
        store.getRecoverableInstances().stream().map(WorkflowInstance::workflowId).toList(),
        "the poisoned instance must be adopted FIRST, or the healthy one is launched "
                + "before the throw and runs.get() races the assertion below");
```

- [ ] **Step 2: Run it and watch it fail for the ordering reason**

Run: `./gradlew :maestro-core:test --tests '*RecoveryPassIsolationTest' --console=plain`
Expected: FAIL in `throwingOnOneInstanceEndsTheWholePass` with `expected: <[poisoned-wf, healthy-wf]> but was: <[healthy-wf, poisoned-wf]>`.

- [ ] **Step 3: Make `PoisonableStore.getRecoverableInstances()` return poisoned instances first**

Replace the alphabetical comparator (`:215`) with ordering by the poison predicate — `Comparator.comparing((WorkflowInstance i) -> poisoned.test(i.workflowId()) ? 0 : 1).thenComparing(WorkflowInstance::workflowId)` — and correct the comment above it (`:212-213`) to say the order is enforced by the predicate, not by name. Add the same precondition assertion to `skippingOneInstanceLetsThePassContinue()` after its `store.invisibleTo(...)` call (`:88`) so both tests state the order they depend on.

- [ ] **Step 4: Run the class and confirm both tests pass**

Run: `./gradlew :maestro-core:test --tests '*RecoveryPassIsolationTest' --console=plain`
Expected: `BUILD SUCCESSFUL`, 2 tests passed. `runs.get()` is now deterministically `0` in the throwing test: the first instance throws synchronously inside `launchWorkflow`'s re-read, so no virtual thread is ever started.

- [ ] **Step 5: Run the three suites (plus the 07-28 PR failure) ten times each with no failures**

```bash
cat > /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/flaky-10x.sh <<'EOF'
#!/usr/bin/env bash
set -u
cd /Users/rakheendama/Projects/2026/maestro
log=docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/flaky-10x.log
{ echo "# pwd=$(pwd) head=$(git rev-parse HEAD) started=$(date -u +%FT%TZ)"; } > "$log"
fail=0
for i in $(seq 1 10); do
  echo "=== iteration $i $(date -u +%FT%TZ)" >> "$log"
  ./gradlew --rerun-tasks --console=plain \
    :maestro-core:test --tests '*RecoveryPassIsolationTest' \
    :maestro-test:test --tests '*TestWorkflowEnvironmentTest' \
    :maestro-integration-tests:test --tests '*EnginePostgresSagaIT' --tests '*EnginePostgresParallelIT' \
    >> "$log" 2>&1 || fail=$((fail+1))
done
echo "# finished=$(date -u +%FT%TZ) failed_iterations=$fail" >> "$log"
EOF
chmod +x /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/flaky-10x.sh
nohup /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/flaky-10x.sh > /dev/null 2>&1 &
```

Then (bounded polls until the `# finished=` line appears; ~10 × 3 min):

Run: `tail -1 docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/flaky-10x.log; grep -c 'BUILD SUCCESSFUL' docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/flaky-10x.log; grep -c ' FAILED' docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/flaky-10x.log`
Expected: `# finished=… failed_iterations=0`, `10`, `0`.

If an iteration fails: the failing assertion goes into the log; apply the same discipline (find the ordering or two-write race, replace the wait with an event-based predicate — `TerminalWait`, a latch, or a store-visible condition — never a longer bound), and restart the loop from 1.

- [ ] **Step 6: Commit**

```bash
git add maestro-core/src/test/java/io/b2mash/maestro/core/engine/RecoveryPassIsolationTest.java \
        docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/flaky-10x.log
git commit -m "test(core): adopt the poisoned instance first so RecoveryPassIsolationTest proves what it claims

The fake store sorted by workflowId, so healthy-wf was launched before
poisoned-wf threw and runs.get() raced the assertion (Build & Test run
30773436894, 2026-08-03). The Saga and Parallel ITs that reddened runs
30396734228 and 30728290264 were the terminal-event race closed by
6296427; ten consecutive --rerun-tasks iterations of all three are green
(triage/flaky-10x.log).

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Refresh `docs/test-plan.md` and `docs/multi-instance-test-plan.md` to the real inventory

**Files:**
- Modify: `docs/test-plan.md:3`, `:71`, `:93`, `:104`, `:110`, `:129`, `:131`, `:133-134`, and a new subsection after `:134`
- Modify: `docs/multi-instance-test-plan.md:3-28` (status banner), `:41-48` (§1 inventory)

**Interfaces:**
- Consumes: the Test inventory table in the Evidence section above; the names of the new `@Tag("integration")` suites from Plans 01–05 — read each sibling plan's **Interfaces → Produces** and **Files → Test** lines at execution time (the sibling files did not exist when this plan was written).
- Produces: nothing other tasks consume.

- [ ] **Step 1: Write the failing checks**

Run from repo root:

```bash
grep -c 'RabbitMqWorkflowMessagingTest' docs/test-plan.md          # expect 4 today (:110, :131, :279-280, :377)
grep -c '16 unit test classes' docs/test-plan.md                   # expect 1
grep -c 'maestro-messaging-rabbitmq | ' docs/test-plan.md          # expect 1 (the §1 Other row)
grep -c 'Bug-fix cycle 2026-10-24' docs/test-plan.md               # expect 0
```

- [ ] **Step 2: Correct the §1 matrix headings and the Other table**

- `:71` `(maestro-core — 16 unit test classes)` → `(maestro-core — 48 test classes, 412 tests)`.
- `:93` `(3 test classes)` → `(15 test classes, 109 tests)`.
- `:104` `(2 test classes)` → `(11 test classes, 59 tests)`.
- `:110` delete ` + \`RabbitMqWorkflowMessagingTest\`` from the Notes cell.
- `:129` `✅ U (3 classes)` → `✅ U (6 classes, 58 tests)`.
- `:131` delete the `maestro-messaging-rabbitmq` row entirely (the header note at `:21-26` already explains the removal; §5's historical mentions at `:279-280` and `:376-378` stay as history).
- `:133-134` append to both E rows: `— schedule was auto-disabled 2026-10-10 → 2026-10-24 (GitHub 60-day inactivity rule, see § CI wiring); soak failed 5/5 and PR-gate 2/30 between 2026-08-09 and 2026-10-04, triaged in docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/ViolationTriageReport.md`.
- `:3` append ` · **Inventory refreshed:** 2026-10-24 (150 test classes / 973 \`@Test\` across 13 modules — counts from \`find … -name '*Test.java' -o -name '*IT.java'\` and \`grep -rhE '^\\s*@(Test|ParameterizedTest|RepeatedTest)\\b'\`)`.

- [ ] **Step 3: Add the bug-fix-cycle pins subsection after the Other table (`:134`)**

Heading `### Bug-fix cycle 2026-10-24 — new pins`, then a table `| Finding | Unit pin (in-memory) | Integration pin (real Postgres) | Plan |` with one row per finding closed by Plans 01–05 (C1–C15, F1–F13 docs parts, #25–#27) and one row per Issue 29+ Task 2 filed. Fill class names from each sibling plan's Interfaces; where a sibling plan has not yet landed when you write, put `fill from <NN>-<plan>.md Interfaces` in the cell rather than inventing a name. Add one sentence under the table: every integration pin gates on the terminal **event** via `TerminalWait` (Review Focus 5).

- [ ] **Step 4: Refresh `docs/multi-instance-test-plan.md`**

- Status banner (`:3-28`): add a dated paragraph after the bullet list: the chaos soak has not been green on `main` since 2026-08-02 (list the five soak run ids and two PR-gate ids from the Evidence table), the schedule was disabled 2026-10-10 → 2026-10-24, and the triage lives at the report path.
- §1 (`:41-48`): `(4 suites, ~12 tests)` → `(5 suites, 14 tests — \`MultiNodeLockContentionIT\`, \`MultiNodeNoLockBackendIT\`, \`MultiNodeOwnerDeathIT\`, \`MultiNodeSignalRoutingIT\`, \`MultiNodeTimerWakeIT\`)`.

- [ ] **Step 5: Run the checks again**

```bash
grep -c 'RabbitMqWorkflowMessagingTest' docs/test-plan.md          # expect 2 (historical §5 only)
grep -c '16 unit test classes' docs/test-plan.md                   # expect 0
grep -c 'maestro-messaging-rabbitmq | ' docs/test-plan.md          # expect 0
grep -c 'Bug-fix cycle 2026-10-24' docs/test-plan.md               # expect 1
grep -c '5 suites, 14 tests' docs/multi-instance-test-plan.md      # expect 1
```

- [ ] **Step 6: Commit**

```bash
git add docs/test-plan.md docs/multi-instance-test-plan.md
git commit -m "docs: refresh test-plan inventories to the real 150-class/973-test baseline; drop the deleted RabbitMQ suite

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: `verifyModuleTestCoverage` covers the samples

**Files:**
- Modify: `build.gradle.kts:38-40` (filter + comment), `:32` (`modulesWithoutTests` only if Step 3's precondition fails)
- Modify: `docs/test-plan.md` § CI wiring (the `<!-- Task 5 -->` marker left by Task 1 Step 6)

**Interfaces:**
- Consumes: Plan 05's sample tests. Precondition: every sample subproject with `src/main/java` (`sample-order-service`, `sample-payment-gateway`, `sample-postgres-only`, and the three loan-origination services) has at least one `*Test.java`/`*IT.java` under `src/test/java`. The loan services already do (2/1/1); the other three have none at 4eb28e9.
- Produces: `./gradlew build` fails when any module with production code — library *or* sample — has no test class.

- [ ] **Step 1: Check the precondition**

Run: `for d in maestro-samples/sample-order-service maestro-samples/sample-payment-gateway maestro-samples/sample-postgres-only; do echo "$d $(find $d/src/test/java \( -name '*Test.java' -o -name '*IT.java' \) 2>/dev/null | wc -l)"; done`
Expected: three non-zero counts. If any is `0`, Plan 05 has not landed for that sample: stop, finish Plan 05, or (only if the coordinator rules the sample stays untested this cycle) add that project *name* to `modulesWithoutTests` (`build.gradle.kts:32`) with a comment citing the Plan 05 task — the gate's designed mechanism, which also prints the debt on every run (`:65-72`).

- [ ] **Step 2: Demonstrate the gate is blind to samples today**

```bash
mv maestro-samples/sample-postgres-only/src/test /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/sample-postgres-only-test
./gradlew verifyModuleTestCoverage --console=plain | grep -E 'Test-coverage gate|no test classes'
```

Expected: `Test-coverage gate: 11 modules with production code, 0 accepted without tests.` and no failure — the sample's missing tests are invisible because `:40` filters `it.name.startsWith("maestro-")`.

- [ ] **Step 3: Remove the name filter**

In `build.gradle.kts:40` replace `subprojects.filter { it.name.startsWith("maestro-") }` with `subprojects` (the `hasMain` check at `:41`/`:55` already excludes container projects `maestro-samples` and `sample-loan-origination`, which have no `src/main/java`). Replace the comment at `:38-39` with: samples are covered too — Plan 05 of the 2026-10-24 bug-fix cycle gave every sample a test, and "covered by the E2E instead" let `sample-postgres-only` ship with `NO-SOURCE` tests.

- [ ] **Step 4: Confirm the gate now fails on the hidden sample, then restore and confirm it passes**

```bash
./gradlew verifyModuleTestCoverage --console=plain | grep -E 'no test classes'
```

Expected: `These modules have production code but no test classes: sample-postgres-only. Add a test, or …` (`BUILD FAILED`).

```bash
mv /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/sample-postgres-only-test maestro-samples/sample-postgres-only/src/test
git status --short maestro-samples/sample-postgres-only   # expect empty
./gradlew verifyModuleTestCoverage --console=plain | grep 'Test-coverage gate'
```

Expected: `Test-coverage gate: 17 modules with production code, 0 accepted without tests.` (11 library modules + 6 sample services; adjust only if Plan 05 added or removed a sample project).

- [ ] **Step 5: Run the whole gate once**

Run: `./gradlew build --console=plain | tail -3`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Document and commit**

Replace the `<!-- Task 5 -->` marker in `docs/test-plan.md` § CI wiring with: `Coverage gate: every subproject with \`src/main/java\` — libraries and samples alike — must carry ≥1 test class (\`verifyModuleTestCoverage\`, root \`build.gradle.kts\`); accepted debt is listed by name in \`modulesWithoutTests\` and printed on every run.`

```bash
git add build.gradle.kts docs/test-plan.md
git commit -m "build: test-coverage gate covers the sample applications

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Whole-branch verification gate (runs after Plans 01–05 and Tasks 1–5 have landed)

**Files:**
- Create: `docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-verification.md`
- Create: `docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-logs/*.log` (identity-stamped command logs)
- Modify: `docs/release-notes.md` `## Unreleased` (`:3`) — one `### Verified` paragraph

**Interfaces:**
- Consumes: the branch with every sibling plan merged; `ViolationTriageReport.md` classifications (Task 2); the chaos harness as-is; `run-e2e.sh` env knobs `E2E_LOCK_BACKEND={valkey|postgres}`, `E2E_ONLY` (`run-e2e.sh:128-150`).
- Produces: `branch-verification.md`, the cycle's evidence of record.

- [ ] **Step 1: Prune stale evidence and stamp the report header**

```bash
cd /Users/rakheendama/Projects/2026/maestro
rm -rf maestro-integration-tests/build/chaos-evidence maestro-samples/sample-loan-origination/e2e/logs
mkdir -p docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-logs
{ echo "# Branch verification — bug-fix cycle 2026-10-24";
  echo "pwd=$(pwd)  branch=$(git branch --show-current)  head=$(git rev-parse HEAD)  written=$(date -u +%FT%TZ)";
  echo; echo "| Gate | Command | Result | Log |"; echo "|---|---|---|---|"; } \
  > docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-verification.md
```

Expected: a file whose second line names the branch's HEAD; `git status` shows only the new file.

- [ ] **Step 2: Static sweep for the status-before-log anti-pattern in new ITs (Review Focus 5)**

Run: `git diff --name-only main...HEAD -- '*IT.java' | xargs -r grep -ln 'awaitStatus(.*\(COMPLETED\|FAILED\)'`
Expected: no output (no new IT polls a terminal status). Any hit is fixed in the owning plan's file (use `awaitTerminal`/`TerminalWait`) before continuing.

- [ ] **Step 3: Unit + starter + build gate**

```bash
log=docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-logs/build.log
{ echo "# pwd=$(pwd) head=$(git rev-parse HEAD) started=$(date -u +%FT%TZ)"; ./gradlew build --console=plain; echo "# exit=$? finished=$(date -u +%FT%TZ)"; } > $log 2>&1
grep -E '^# exit=|BUILD (SUCCESSFUL|FAILED)|tests completed' $log
```

Expected: `BUILD SUCCESSFUL`, `# exit=0`, no `tests completed, N failed` line. Append the row `| ./gradlew build | … | PASS | branch-logs/build.log |` to the report.

- [ ] **Step 4: Integration suites against real backends**

Same shape as Step 3 with `./gradlew :maestro-integration-tests:test --rerun-tasks --console=plain` into `branch-logs/integration.log`.
Expected: `BUILD SUCCESSFUL`; `grep -c ' FAILED' branch-logs/integration.log` → `0`. Append the row.

- [ ] **Step 5: Loan-origination E2E, all ten scenarios, both lock backends**

Two detached runs, sequential (they share ports 8091-8093; `tasks/lessons.md` 2026-07-28: verify process identity):

```bash
cat > /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/e2e-both.sh <<'EOF'
#!/usr/bin/env bash
set -u
cd /Users/rakheendama/Projects/2026/maestro/maestro-samples/sample-loan-origination
for backend in valkey postgres; do
  log=../../docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-logs/e2e-$backend.log
  { echo "# pwd=$(pwd) head=$(git rev-parse HEAD) started=$(date -u +%FT%TZ) E2E_LOCK_BACKEND=$backend"; } > "$log"
  for p in 8091 8092 8093; do lsof -nP -iTCP:$p -sTCP:LISTEN >> "$log" 2>&1; done
  E2E_LOCK_BACKEND=$backend ./e2e/run-e2e.sh >> "$log" 2>&1
  echo "# exit=$? finished=$(date -u +%FT%TZ)" >> "$log"
done
EOF
chmod +x /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/e2e-both.sh
nohup /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/e2e-both.sh > /dev/null 2>&1 &
```

Expected per log (~45 min each): the `lsof` lines before the run are empty (ports free); `grep -c 'LOCK BACKEND VERIFIED' e2e-<backend>.log` ≥ 3 (`run-e2e.sh:485`); `=== RESULTS ===` block shows ten `PASS` rows and no `FAIL`; `# exit=0`. Append two rows.

- [ ] **Step 6: Chaos PR-gate three times with fresh seeds, then one 2 h soak**

PR-gate (local, detached, ~35 min total):

```bash
cat > /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/prgate-3x.sh <<'EOF'
#!/usr/bin/env bash
set -u
cd /Users/rakheendama/Projects/2026/maestro
for i in 1 2 3; do
  log=docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-logs/chaos-prgate-$i.log
  { echo "# pwd=$(pwd) head=$(git rev-parse HEAD) started=$(date -u +%FT%TZ) iteration=$i"; } > "$log"
  ./gradlew :maestro-integration-tests:e2eTest --rerun-tasks --console=plain >> "$log" 2>&1
  echo "# exit=$? finished=$(date -u +%FT%TZ)" >> "$log"
done
EOF
chmod +x /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/prgate-3x.sh
nohup /private/tmp/claude-501/-Users-rakheendama-Projects-2026-maestro/8bd2a3b5-21d0-4182-a382-26f478ede1ec/scratchpad/prgate-3x.sh > /dev/null 2>&1 &
```

Soak: dispatch CI on the branch rather than hold a laptop for 2 h, and download the identity-stamped artifact:

```bash
git push -u origin "$(git branch --show-current)"
gh workflow run e2e-nightly.yml --ref "$(git branch --show-current)"
# later, when complete:
SOAK_RUN_ID=$(gh run list --workflow e2e-nightly.yml --event workflow_dispatch --branch "$(git branch --show-current)" --limit 1 --json databaseId -q '.[0].databaseId')
gh run view $SOAK_RUN_ID --json jobs -q '.jobs[] | "\(.name): \(.conclusion)"'
gh run view $SOAK_RUN_ID --log 2>/dev/null | grep -E 'VIOLATION \[I|\[chaos\] mode=' | sed -E 's/^[^\t]*\t[^\t]*\t//' > docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-logs/chaos-soak-ci-$SOAK_RUN_ID.log
gh run download $SOAK_RUN_ID -n chaos-soak-evidence -D docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/branch-logs/soak-evidence-$SOAK_RUN_ID
```

Expected: all three local logs end `# exit=0` and `grep -c 'VIOLATION \[I' branch-logs/chaos-prgate-*.log` → `0` for each; the CI run's four jobs all `success`; `grep -c 'VIOLATION \[I' branch-logs/chaos-soak-ci-*.log` → `0`; the soak artifact's `summary.json` has `"verdict": "PASS"` and `"workflowsSubmitted"` ≥ 2000. Append five rows (three PR-gate, one soak, one for the CI job set).

- [ ] **Step 7: Confirm the triage mapping held (closes I1/I4, or re-opens them with evidence)**

For every violation class Task 2 mapped to a plan: the absence of the `VIOLATION` line in Step 6's four chaos runs is the pin. Record in `branch-verification.md` a `## Chaos triage closure` table `| Class (Task 2) | Mapped to | Observed in Step 6 | Verdict |`. If any `VIOLATION` line appears, download that run's `failures/` tree, fill a new block in `ViolationTriageReport.md`, classify it with Task 2 Step 5's table, and — for class 1/2 — record `elapsed since first arm` to decide between a Plan 04 C12 regression and a budget collision (Review Focus 2). A reproduced violation blocks the cycle until mapped to a fix or filed as Issue 29+; do not merge over it.

- [ ] **Step 8: Release notes and commit**

Add under `docs/release-notes.md` `## Unreleased` a `### Verified` paragraph: the branch passed `./gradlew build`, the integration suites, the ten-scenario loan E2E on both lock backends, three consecutive fresh-seed chaos PR-gates and one 2 h soak with zero invariant violations, with the evidence path; and that the nightly E2E schedule is re-enabled and self-healing.

```bash
git add docs/superpowers/plans/2026-10-24-bugfix-cycle/triage docs/release-notes.md
git commit -m "docs(verification): whole-branch gate for the 2026-10-24 bug-fix cycle — build, ITs, E2E x2 backends, chaos PR-gate x3, soak x1

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review notes (written with the spec open)

- **Spec coverage.** §3b bullets: schedule disabled → Task 1; I1/I4 untriaged → Task 2 (+ Task 6 pin); three `Build & Test` failures → Task 3. §6: stale `docs/test-plan.md` → Task 4; coverage gate excludes samples → Task 5; static analysis → out of scope per README. README row 6 "pins against real Postgres for every fix above" → Task 4 Step 3 lists them, Task 6 Steps 3–4 run them.
- **Step scan.** Every step has a command and an expected output or an exact edit. The one place the plan cannot be exact — new IT names from sibling plans — says so and says where to read them.
- **Type consistency.** `TerminalWait.awaitTerminal(store, workflowId, timeout)` and `WorkflowHandle.awaitTerminal(Duration)` are used with the signatures at HEAD; `PoisonableStore` method names match the file; `LoanPath`, `ChaosConfig`, `ChaosController` fields cited by line.
- **Review Focus.** All five lines are pinned to a step (1→T2 S6, 2→T2 S5/T6 S7, 3→T2 S1, 4→T1 S5, 5→T6 S2).
- **Proportion.** Code blocks are shell wrappers and one YAML job; no production bodies beyond a one-line comparator.
