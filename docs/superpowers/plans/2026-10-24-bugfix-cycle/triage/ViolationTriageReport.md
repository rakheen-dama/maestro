# Chaos violation triage — I1 / I4
<!-- pwd=/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/bugfix-cycle head=2e0c29d0a9dfe25c65e988af4adefc944b706367 written=2026-10-28T12:59:42Z (plan 06 Task 2) -->

Every quoted value below is copied from the file named next to it. Paths are
relative to this folder (`docs/superpowers/plans/2026-10-24-bugfix-cycle/triage/`);
`F/` abbreviates the committed failure dump
`evidence/40674015602/soak/20261028-104145--6945054112340787534/failures/loan-chaos-11501689961368764082-1145/`,
and `R/` its run directory `evidence/40674015602/soak/20261028-104145--6945054112340787534/`.
Node logs under `R/logs/` are local-only (gitignored, never committed); the
quotes taken from them are marked *(node log, not committed)*.

## Sources

| Run | Date | Mode | Seed | Log | Artifacts |
|---|---|---|---|---|---|
| 31288365087 | 2026-08-09 | soak | `-5682539354314886163` | `ci-logs/31288365087.log` (2 violation lines) | expired |
| 31919632989 | 2026-08-16 | soak | `7671915568149341571` | `ci-logs/31919632989.log` (2) | expired |
| 33285262356 | 2026-08-30 | soak | `8117718602935604897` | `ci-logs/33285262356.log` (2) | expired |
| 33463346389 | 2026-09-01 | PR-gate | `-970181608425652116` | `ci-logs/33463346389.log` (3) | expired |
| 34668175046 | 2026-09-12 | PR-gate | `-3264688620830235656` (the job's other seed, `-6839934821064559381`, was green) | `ci-logs/34668175046.log` (1) | expired |
| 35481047255 | 2026-09-20 | soak | `7736940657655325443` | `ci-logs/35481047255.log` (3) | expired |
| 37169821147 | 2026-10-04 | soak | `-891813452390949498` | `ci-logs/37169821147.log` (3) | expired |
| 40593820947 | 2026-10-27 | soak + PR-gate ×3 (baseline dispatch, `66cdf95`) | soak `6283271085498931864`; PR-gate `1867544705614913715`, `-6883450985522340821`, `7049814436427796888` (run-directory names) | none — wholly green, no `--log-failed` | `evidence/40593820947/{soak,pr-gate}` — no `failures/` tree; not committed |
| 40674015602 | 2026-10-28 | soak (second dispatch, `66cdf95`; PR-gate ×3 and both loan E2Es green) | `-6945054112340787534` (`F/ledger.json` `_identity.seed`; also the `[chaos] mode=SOAK seed=` line in `ci-logs/40674015602.log`) | `ci-logs/40674015602.log` (2 violation lines) | `R/` — `failures/` and `chaos-actions.jsonl` committed |
| local | 2026-10-28 | PR-gate × seeds `-3264688620830235656`, `-970181608425652116`; soak × `-6945054112340787534` | — | `evidence/local/*.log` (local-only) | see § Local reproduction |

The seven historical runs (2026-08-09 → 2026-10-04) have only their step logs;
their dumps expired (plan 06 Review Focus 3). Their violations are listed here
for completeness and **not classified** — the plan forbids classifying from
log lines alone. The only dump available is run 40674015602's.

## Per-workflow findings

### loan-chaos-11501689961368764082-1145

- **Run / seed / mode:** CI run 40674015602, job "Chaos soak (2h window)", runId `20261028-104145--6945054112340787534`, seed `-6945054112340787534`, mode `SOAK`, gitHead `66cdf952a17987774a31c08e9afb1d316a8d2349` (`F/ledger.json` `_identity`). The workflow id's `11501689961368764082` is the seed printed unsigned.
- **Path (ledger) / declared expectation (status, output):** `"path" : "CONDITIONS_LOOP"`, `"expectedTerminal" : "COMPLETED"`, `"expectedOutput" : "FUNDED"`, `"compensationExpected" : false`, borrowers `"borrower-a", "borrower-b"` (`F/ledger.json`). Actual: `"status" : "FAILED"`, output `Signature collection consumed 5 signals but only 1 of 2 borrowers signed` / `io.b2mash.maestro.samples.loan.application.exception.LoanRejectedException` (`F/instance.json`).
- **Ledger notes:** `"notes" : [ ]` — empty: the driver recorded no `doc-not-landed:` / `signature-not-landed:` note, i.e. from its point of view every action landed (`F/ledger.json`).
- **Submitted at (ledger) → terminal at:** `"submittedAtUtc" : "2026-10-28T11:41:46.495862026Z"` (`F/ledger.json`) → `"completed_at" : "2026-10-28 11:42:30.854412"` (`F/instance.json`); 44.4 s. `"event_sequence" : "28001"`, `"version" : "24"`.
- **Chaos actions overlapping [submitted, terminal]:** exactly one — `#50 BACKEND_OUTAGE valkey 2026-10-28T11:42:03.505974365Z → 2026-10-28T11:42:30.532741553Z` (`"secs":27`) (`R/chaos-actions.jsonl`). It is `docker pause` of the Valkey container (`ChaosController.backendOutage` → `ChaosCluster.pauseBackend`), so connections hang rather than reset. The previous action, `#49 ROLLING_RESTART UNDERWRITING`, healed at `2026-10-28T11:41:38.505684116Z`, before submission; the next, `#51 KILL9 UW_B`, started at `2026-10-28T11:43:08.533922908Z`, after terminal.
- **Last event seq / type:** `28002` `WORKFLOW_FAILED`, `created_at` `2026-10-28 11:42:30.882967` (`F/events.json`).
- **I1 — WORKFLOW_FAILED payload:** `exceptionType` = `io.b2mash.maestro.samples.loan.application.exception.LoanRejectedException`, `message` = `Signature collection consumed 5 signals but only 1 of 2 borrowers signed`; the row before it is seq `28001` `COMPENSATION_COMPLETED`, `step_name` `$maestro:compensation` (`F/events.json`). The business failure is the step before compensation began: seq `25` `SIGNAL_RECEIVED` `$maestro:awaitSignal:package.signed` at `2026-10-28 11:42:16.52217`, then seq `26` `COMPENSATION_STARTED` at `2026-10-28 11:42:16.525515`.
  **Await it died in / elapsed vs timeout (Review Focus 2):** it did **not** time out. It died in the signature fan-in (`LoanApplicationWorkflow.collectSignatures`, from `:219` at `66cdf95` / `:230` at HEAD; the bound is `:48` / `:49`): `collectSignals("package.signed", 2, …)` consumed seq `20` and `22`, the dedupe loop (`while signed.size() < required.size() && received < MAX_SIGNATURE_SIGNALS`, bound `MAX_SIGNATURE_SIGNALS = 5`) consumed seq `23`, `24`, `25` — all five payloads `"signerId": "borrower-a"` — and the loop exhausted its bound and threw. Elapsed since the fan-in armed (seq `18` `$maestro:currentTime` = `2026-10-28T11:42:03.306255094Z`) to the fifth consumption (`11:42:16.52217`): 13.2 s, against a configured sign timeout of 120 s (`ChaosConfig.java` `sampleTimeoutSeconds` default `120` → `MAESTRO_SAMPLE_SIGN_TIMEOUT`, `ChaosCluster.java:331` at `66cdf95`). There is no `SIGNAL_TIMEOUT` event at any `package.signed` step and `F/timers.json` has `"payload" : [ ]`.
- **Signal rows (`F/signals.json`)** — `package.signed`, all 71-byte payloads:

  | received_at | consumed | id | signerId |
  |---|---|---|---|
  | 2026-10-28 11:42:03.818222 | true | 5623630f-510f-4f32-90c2-98f018c1fc21 | borrower-a |
  | 2026-10-28 11:42:06.833547 | true | d63d76b5-192b-4607-ad93-1689ab04d0ff | borrower-a |
  | 2026-10-28 11:42:10.121398 | true | 00527dd5-6362-4743-80ac-fa00dacbe242 | borrower-a |
  | 2026-10-28 11:42:13.122323 | true | 8eff75bf-108c-4e55-a18f-7aa621cc8acb | borrower-a |
  | 2026-10-28 11:42:16.52103 | true | e5e23e34-d47a-4fcf-9087-5d0c03782ff2 | borrower-a |
  | 2026-10-28 11:42:19.52198 | false | fdb87de4-4b1f-424c-b751-84ef769bb1c2 | borrower-a |
  | 2026-10-28 11:42:23.020745 | false | 7ba730f0-1994-4b0c-a56c-29492be6f2e3 | borrower-a |
  | 2026-10-28 11:42:26.020935 | false | c517c203-2530-47ce-bcf6-d0173ee08776 | borrower-a |
  | 2026-10-28 11:42:29.619889 | false | cf7a30e4-9e5d-44a2-94b3-5fc2d9e0a5eb | borrower-a |
  | 2026-10-28 11:42:30.974132 | false | 48f20eac-6764-46d2-89fd-2da53e98afc0 | borrower-b |

  Byte-identical duplicates among consumed rows
  (`jq -r '.payload[] | select(.consumed=="true" and .signal_name=="package.signed") | .payload' signals.json | sort | uniq -cd`):
  `5 {"loanId": "chaos-11501689961368764082-1145", "signerId": "borrower-a"}`.
  All nine borrower-a rows fall inside outage #50; borrower-b's single row
  arrives 0.44 s after Valkey healed and 0.12 s after `completed_at`. The
  `document.uploaded` rows (`income-proof` at `2026-10-28 11:41:46.531869`,
  `proof-of-insurance` at `2026-10-28 11:42:03.243114`) are each consumed once
  and are not involved; there is no I4 violation in this run.
- **Mechanism (reconstructed from the dump; the inferred steps are marked):**
  1. The driver's `signAll` posts borrower-a once (its store check found 0 rows) via `WorkloadDriver.post()`, which tries each non-harassed loan node with a **3 s HTTP timeout** and parks `300 + 100·attempt` ms between rounds (`WorkloadDriver.java:702-731`, unchanged `66cdf95`→HEAD). `post()` re-POSTs on a timeout without re-checking the store.
  2. The sample's sign endpoint calls `WorkflowHandle.signal()` → `SignalManager.deliverSignal`, which **persists the row first** (`SignalManager.java:238` at `66cdf95`; `:322` at HEAD), unparks a local waiter, and then calls `signalNotifier.publish` **synchronously** (`:248` / `:332`). `ValkeySignalNotifier.publish` is `publishConnection.sync().publish(...)` (`:76` / `:86`) on a `RedisClient` created without a timeout (`ValkeyLockAutoConfiguration.java:67`), i.e. Lettuce's 60 s default. With Valkey paused, the publish — and so the HTTP request — blocks for the rest of the outage *(inferred: the server logs no per-request timing; but see 3–4)*.
  3. The row timestamps are the fingerprint of step 1 against step 2: gaps alternate 3.0 s and 3.3/3.4/3.5/3.6 s (`03.818 → 06.833 → 10.121 → 13.122 → 16.521 → 19.522 → 23.021 → 26.021 → 29.620`) — one 3 s timeout per node, then the 300/400/500/600 ms park after each two-node round. Every POST inserted a row and then timed out at the client.
  4. Corroboration *(node logs, not committed)*: no loan node logged `Failed to publish signal` between 11:42:03 and 11:42:30 (the four such warnings in the run are at 11:15:45 and 11:56:54), so the publishes did not fail — they waited. No `Started workflow` line on either live loan node (`loan-a-gen10.log`, `loan-b-gen8.log`) falls inside the outage, nine fall in the second after it, and 75 `Request processing failed: io.b2mash.maestro.core.exception.WorkflowAlreadyExistsException` servlet errors (41 + 34) fire at 11:42:30–31: the driver's create POSTs were re-sent the same way, and when Valkey healed the blocked duplicates all ran. For creates the `workflow_id` unique key absorbs the duplicates; for signals nothing does.
  5. The workflow consumed borrower-a duplicates as they arrived — instantly for the rows served by its own node *(inferred from the latencies)* (`03.818→03.819`, `10.121→10.122`, `16.521→16.522`: local unpark), within the 5 s wake-recheck for the peer node's rows (`06.833→08.824`, `13.122→15.126`: the cross-node pub/sub wake was down) — until the fifth, then threw `LoanRejectedException`. The saga released the rate lock (`COMPENSATION: released rate lock aed3f034-9d21-41ad-95c2-555a2760f764` at `2026-10-28T11:42:30.559Z`, `F/node-log-excerpts.txt`; the compensation itself took ~14 s, `11:42:16.525515` → `11:42:30.559Z`, ending as Valkey healed — *inferred* blocked on Valkey the same way) and the workflow went `FAILED`.
  6. `post()` only returned for borrower-a once Valkey healed and an in-flight request finally answered `202`; the driver then posted borrower-b (`11:42:30.974132`) — too late.
- **Classification:** class **8 (unmapped)** for both I1 rows — see § Classification and Concerns. Filed as **Issue 32** in `docs/open-issues.md`, task stub **Plan 04 Task 8**.

## Classification

| Violation | Workflows | Class | Maps to | Evidence line |
|---|---|---|---|---|
| I1 terminal status (run 40674015602, soak, `66cdf95`) | `loan-chaos-11501689961368764082-1145` | **8 — unmapped** | **Issue 32** (new) → Plan 04 Task 8 stub. Not class 1/2 (no `SignalTimeoutException`, no `SIGNAL_TIMEOUT` event, 13.2 s of a 120 s budget used); not 3 (`exceptionType` is the sample's `LoanRejectedException`, no OCC/sequence/compensation failure); not 4 (`WORKFLOW_FAILED` at seq `28002` is present). Nearest is class 5 — byte-identical rows satisfying an N-of-M fan-in — but class 5 is an I4 observation on `document.uploaded` whose duplicates come from transport redelivery; here the duplicates are created by the caller retrying a signal send that blocked on Valkey *after* the row was durable. | `F/events.json` seq `28002` `WORKFLOW_FAILED`, exceptionType `io.b2mash.maestro.samples.loan.application.exception.LoanRejectedException`; `F/signals.json` `uniq -cd` → `5 {"loanId": "chaos-11501689961368764082-1145", "signerId": "borrower-a"}`; `R/chaos-actions.jsonl` `"seq":50 … "action":"BACKEND_OUTAGE","target":"valkey"` |
| I1 output.status (same run) | `loan-chaos-11501689961368764082-1145` | **8** (consequence of the row above) | Issue 32 | `F/instance.json` `"status" : "FAILED"`, `"output"` `… only 1 of 2 borrowers signed …` |
| I1 / I4 in runs 31288365087, 31919632989, 33285262356, 33463346389, 34668175046, 35481047255, 37169821147 | see `ci-logs/*.log` | not classified | evidence expired; re-triage from the first Task 6 violation with a dump (Step 6) | `ci-logs/<run>.log` `VIOLATION [I…]` lines only |
| local reproduction at HEAD `2e0c29d` (2 PR-gate seeds × 3, soak seed × 1) | — | **blocked** (7 of 7 attempts failed at cluster boot, before any workload) | the cluster cannot boot at HEAD (see § Local reproduction, Concern 1) | `evidence/local/prgate-seed-3264688620830235656-attempt1.log`: `@DurableWorkflow bean 'underwritingWorkflow' … must be prototype-scoped` |

No class-2 threshold question arises (zero class-2 workflows), so nothing goes
to `docs/operations.md` §8.

## Local reproduction

Script: the brief's Step 3 script extended with one soak run on the CI soak
seed (`-Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120
-Dmaestro.chaos.seed=-6945054112340787534`, the CI job's own shape — soak
seeding is supported: `ChaosConfig.fromSystemProperties` reads
`maestro.chaos.seed` for every mode and `e2eTest` forwards it). Launched
detached; every log starts with a `# pwd= head= started= seed=` header and ends
with `# exit= finished=`.

All runs at HEAD `2e0c29d0a9dfe25c65e988af4adefc944b706367` (each log's
header). Logs are local-only (`evidence/local/`, gitignored: they embed the
failed container's log output).

| Mode | Seed | Attempt | Log (`evidence/local/`) | Started → finished (UTC) | exit | Outcome |
|---|---|---|---|---|---|---|
| PR-gate | `-3264688620830235656` | 1 | `prgate-seed-3264688620830235656-attempt1.log` | 12:51:53 → 12:55:46 | 1 | cluster boot failed; 0 `VIOLATION [I` lines |
| PR-gate | `-3264688620830235656` | 2 | `prgate-seed-3264688620830235656-attempt2.log` | 12:55:46 → 12:59:36 | 1 | same |
| PR-gate | `-3264688620830235656` | 3 | `prgate-seed-3264688620830235656-attempt3.log` | 12:59:36 → 13:03:35 | 1 | same |
| PR-gate | `-970181608425652116` | 1 | `prgate-seed-970181608425652116-attempt1.log` | 13:03:35 → 13:07:38 | 1 | same |
| PR-gate | `-970181608425652116` | 2 | `prgate-seed-970181608425652116-attempt2.log` | 13:07:38 → 13:11:42 | 1 | same |
| PR-gate | `-970181608425652116` | 3 | `prgate-seed-970181608425652116-attempt3.log` | 13:11:42 → 13:15:43 | 1 | same |
| SOAK (120 min) | `-6945054112340787534` | 1 | `soak-seed-6945054112340787534-attempt1.log` | 13:15:43 → 13:19:36 | 1 | same |

Each log shows the harness took the seed (`[chaos] mode=PR_GATE
seed=-3264688620830235656 …`, `[chaos] mode=SOAK seed=-6945054112340787534 …`),
then `org.testcontainers.containers.ContainerLaunchException` at boot, with the
underwriting container's own output ending in `@DurableWorkflow bean
'underwritingWorkflow'
(io.b2mash.maestro.samples.loan.underwriting.workflow.UnderwritingWorkflow) must
be prototype-scoped` (2 occurrences per log), and `7 tests completed, 1 failed,
3 skipped`. No `chaos-evidence/<runId>/failures/` tree was produced (seven
run directories, none with `failures/`). Not a reproduction and not a
non-reproduction: **blocked**.

Because the cluster cannot boot at HEAD, the local runs say nothing — either
way — about whether the cycle's fixes changed the I1 rate. The Issue 32
mechanism itself is untouched by the cycle: `SignalManager.deliverSignal`'s
synchronous `signalNotifier.publish`, `ValkeySignalNotifier.publish`'s
`sync().publish`, the timeout-less `RedisClient`, `WorkloadDriver.post()` and
`MAX_SIGNATURE_SIGNALS = 5` are all the same at `2e0c29d` as at `66cdf95`
(Plan 04 Task 6 bounded `subscribe`, not `publish`). A local non-reproduction
would therefore not have meant "fixed" (Review Focus 1); the defect is
expected to recur at HEAD whenever a Valkey outage overlaps a signature post.

## Concerns

1. **HEAD cannot boot the loan-origination underwriting service.** `UnderwritingSampleConfiguration.underwritingWorkflow()` registers `UnderwritingWorkflow` as a singleton `@Bean` (unchanged since `72bd289`); Plan 05's F1 fix round 1 (`6dede32`) made `WorkflowRegistrar` fail startup for any non-prototype `@DurableWorkflow` bean. Every chaos PR-gate and soak run fails at cluster boot on this branch (7 of 7 here); the loan-origination E2E boots the same underwriting jar and is expected to fail the same way (not run here). CI has not seen it because the nightly runs `main` (`66cdf95`). The error text names the fix ("Remove @Component (the starter registers the class itself) or add @Scope(\"prototype\")"); it belongs to Plan 05 and blocks Task 6's re-proof.
2. **The classification table had no row for this.** Classes 1–7 assume the I1 failure is an engine exception or a timeout; this one is a sample-level business rejection manufactured by duplicate signal rows. It is filed under class 8 rather than forced into class 5, whose proposed fix (consume-time dedup of byte-identical rows) would happen to mask it but would also silently drop legitimately repeated identical signals.
3. **Three layers had to line up**, and the issue names all three: the engine blocking a durable signal send on a best-effort notification (the defect), the harness re-POSTing without a store check (the amplifier — `signAll`'s own store check sits outside `post()`'s retry loop), and the sample's duplicate tolerance of five (the point of failure). Only the first is a library defect; changing the other two alone would hide it.
4. **`.gitignore` negation did not work as written by Task 1**: `evidence/` excludes the directory, and git cannot re-include files below an excluded directory, so `!evidence/**/failures/` matched nothing. Rewritten to `evidence/**` + `!evidence/**/` + `!evidence/**/failures/**`, with `chaos-actions.jsonl` re-included only for run 40674015602 (`git check-ignore` verified).
5. `instance.json` `"event_sequence" : "28001"` while the last event is `28002` (`WORKFLOW_FAILED`). Observed only; not a violation and not investigated here.
