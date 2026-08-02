# Task 7 chaos evidence index

All artifacts carry embedded identity (pwd, git HEAD, branch, timestampUtc,
seed, mode, runId) as line 1 (JSONL), `#`-line (CSV) or `_identity` (JSON).

| Run dir | Mode/seed | Verdict | Role in the record |
|---|---|---|---|
| 20260731-210207-golden-calibration | GOLDEN, seed -1651200627141855760 | PASS | I3(d)/side-effect calibration of all four paths (design §14.2) |
| 20260731-211603-101 | PR_GATE 1-min shakeout, seed 101 | FAIL | Caught **Issue 18** (pre-fix): I1/I3b dumps for loan-chaos-101-{8,9,10} |
| 20260731-214427-101 | PR_GATE 1-min shakeout, seed 101 | FAIL | Post-Issue-18-fix: I1/I3 clean; residual I4 (pre-Ruling-3) + census substring bug visible |
| 20260731-215043-101 | PR_GATE 1-min shakeout, seed 101 | FAIL | Census fixed (0 dups); single I4 hit with consumedTwin=true → Ruling 3 evidence |
| 20260731-220454-202 | SOAK smoke 1-min + 30s tail, seed 202 | FAIL | I3(d) FAILED-path bound correction evidence (§14.6) |
| 20260731-221119-203 | SOAK smoke, seed 203 | FAIL | Webhook-fallback TOCTOU near-duplicate (consumedTwin=false) → fallback removed |
| 20260731-221641-204 | SOAK smoke, seed 204 | **PASS** | Full pipeline + benchmark tail (6→3 nodes) green; redelivery finding surfaced |
| 20260731-222409--3853687087028584946 | PR_GATE full, fresh seed | FAIL | Drain 120s missed a tail-end child's double-timeout by 2s → drain calibrated 240s (§14.7) |
| 20260731-223448-7919582353711659295 | PR_GATE full, fresh seed | **PASS** | Streak run A (1/3): census clean, 1 redelivery finding, 16m33s |
| 20260731-225143--825499340287642346 | PR_GATE full, fresh seed | FAIL | **NEW DEFECT (proposed Issue 19)**: divergent replay of a timed-out-gate gap after a graceful rolling restart — dumps for loan-chaos-…-37 |
| 20260731-232429--4025274072392725999 | GOLDEN re-calibration (post-Issue-19) | **PASS** | All four paths: gaps=[] — SIGNAL_TIMEOUT memoization confirmed end-to-end (§14.8). Not mirrored into this tree — run dir lives only in transient `maestro-integration-tests/build/chaos-evidence/` (findings recorded in task-7-report.md §14.8) |
| e2e-scenario7-issue19/ | Loan E2E `E2E_ONLY=7` | **PASS** | Re-derived empty missing-set green (18 contiguous events); adoption probe log; first-attempt flake kept for the record |
| 20260731-234107-3430218812008443518 | PR_GATE full, fresh seed | **PASS** | **Streak 1/3**: 74 wf, census 0 dups, 0 redeliveries, 9m10s |
| 20260731-235041--200961534721746905 | PR_GATE full, fresh seed | **PASS** | **Streak 2/3**: 75 wf, census 0 dups, 2 redelivered findings (Ruling 3), 9m18s |
| 20260801-000014-886868793817033505 | PR_GATE full, fresh seed | **PASS** | **Streak 3/3**: 62 wf, census 0 dups, 1 redelivered finding, 9m58s |
| soak-console.log | SOAK attempt 3 console (started 23:14 SAST, binary @`b2b5c65`) | BUILD FAILED / soak **PASSED** | Current contents: the aborted PR-gate collision run (`…211407…`, below) followed by the **PASSing soak of record** (`…214325…`, below). `SOAK_EXIT=1` comes from the PR-gate's `@Timeout` abort, not from the soak — evaluate from the soak run dir's own verdict. From 23:43 SAST onward the console is polluted by the aborted PR-gate's leaked checker/sampler threads (`CHECKER BLIND … Mapped port` / `execInContainer` spam; the binary predates the `eac200e` teardown fix). Earlier consoles at this path: the original 2h OOM failure of record (seed 762567188933648406, report §10.1) was overwritten by filename reuse — its analysis survives in the report; the attempt-2 driver-bug failure and the reboot-killed console are archived below |
| 20260801-013535-777-BEFORE | SOAK smoke 8-min, seed 777 (pre-leak-fix @7ed16d8) | **ABORTED (degraded)** | BEFORE baseline: log-consume backpressure → 1048s/1007s/923s sampler gaps, 18m27s PAUSE_RESUME heal, 16m36s container start; never reached verdict (report §10.1) |
| 20260801-035607-910203 | SOAK smoke 8-min, seed 910203 (post-fix @da9142d) | FAIL (host contention) | Control run: executed beside a stray 2h-soak gradle worker + containers; own heap bounded (≤60MB) but Docker starvation stretched chaos windows ~15× → 12 I1 sample-timeout failures (report §10.3) |
| 20260801-073814-558112 | SOAK smoke 8-min, seed 558112 (post-fix, clean host) | **PASS** | **AFTER validation 1/2**: 168 wf, 0 dups, 2 redelivered findings, heap peak 58MB/2g, checker-blind 0/22 cycles, benchmark tail 28+30 wf (report §10.3) |
| 20260801-080018-558112 | SOAK smoke 8-min, seed 558112 (same JVM, 2nd run) | **PASS** | **AFTER validation 2/2**: 168 wf, 0 dups, 1 redelivered finding, heap peak 234MB/2g plateau, checker-blind 0/22, benchmark tail 28+30 wf |
| 20260801-093053-661901 (+ prgate-postfix.log) | PR_GATE full, seed 661901 (post-fix) | **FAIL** | **NEW FINDING (proposed Issue 20)**: 39s `PARTITION UW_A` → HikariCP 30s connectionTimeout → `UncheckedSqlException` from `standDownIfTerminated`'s `getInstance` inside `awaitSignal`'s wake-recheck → generic `catch (Exception)` durably FAILED two healthy parked workflows (I3d gap = the burned await slot). Census/I1/I2/I4/I5 clean; heap bounded. Dumps in `failures/`, node-log window in `uw-a-partition-window-errors.log` (report §10.4) — BLOCKED on coordinator ruling |
| full-build-issue20.log | `./gradlew build` post-Issue-20-fix | **PASS** | Full-tree build green on the Issue 20 fix (`d13444e`): BUILD SUCCESSFUL, 147 tasks, `BUILD_EXIT=0` (report §11) |
| 20260801-110028-661901 (+ prgate-661901-postissue20.log) | PR_GATE full, seed 661901 (post-Issue-20-fix) | **PASS** | **Issue 20 re-proof with THE failing seed**: same 39s `PARTITION UW_A` scenario, now `VERDICT: PASS`, I1–I5 clean (I3d at the strict zero-gap bound), census 0 dups / 0 missing comp, 65 wf, 1 Ruling-3 redelivered finding, 9m36s (report §11) |
| 20260801-122240--1716270716099710392 (+ prgate-fresh-postissue20.log) | PR_GATE full, fresh seed (post-Issue-20-fix) | **PASS** | **Issue 20 re-proof, fresh seed**: `VERDICT: PASS`, violations `[]`, census 0 dups / 0 missing comp, 69 wf, 1 Ruling-3 redelivered finding, 5 chaos actions, 9m35s (report §11) |
| soak-console-FAILED-driverbug-attempt2.log | SOAK attempt 2 console | **FAILED (pacer runaway)** | The soak attempt whose failure triggered the driver-fix wave: one swallowed interrupt → hot-loop workload runaway (soak-driver-fix-report.md; root cause later attributed to the PR-gate `@Timeout` collision, §8) |
| soak-console-KILLED-reboot-20260801-1723.log | SOAK attempt console | **KILLED (host reboot)** | Aborted by a host reboot at ~17:23 SAST — no verdict; kept for the record |
| red-driverfix-unit-tests.log / green-driverfix-unit-tests.log | chaos unit tests RED@`9494b4b` → GREEN@`b2b5c65` | RED→GREEN | Driver-fix wave pins: the RED run reproduces the pacer runaway in miniature (63,798 scripts from one interrupt → 512m unit-JVM OOM); GREEN 5/5 post-fix (fix report §2-3) |
| green-fixloop1-unit-tests.log / green-fixloop2-unit-tests.log | chaos unit tests GREEN @`eac200e` / @`8cd2754` | GREEN | Fix-loop rounds 1-2 (teardown/back-pressure), run in an isolated worktree while attempt 3 soaked here (fix report §6-7) |
| fixloop3-suite-selection.log | suite-selection RED@`11b744c` → GREEN@`d4720ca` | RED→GREEN | Round 3: dedicated soak/golden/smoke invocations select only their dedicated class; selection proven at execution time (fix report §8) |
| 20260801-202918-558112 | SOAK-flag smoke 8-min, seed 558112 (post-driverfix, @`b2b5c65`) | **PASS** | AFTER-smoke run 1 — actually `ChaosPrGateE2EIT` resolved to SOAK mode by the pre-`d4720ca` both-classes selection (fix report §4 correction): 168 wf, 0 dups, no runaway (cap 580 never approached); console soak-after-smoke-postdriverfix.log |
| 20260801-205118-558112 | SOAK smoke 8-min, seed 558112 (same JVM, run 2) | **PASS** | AFTER-smoke run 2 — `ChaosSoakE2EIT`: 168 wf, 0 dups, benchmark tail 28+30 wf; same console |
| 20260801-211407--4033192168645226995 | `ChaosPrGateE2EIT` under the soak flag (pre-`d4720ca` selection bug) → SOAK mode, 120-min | **ABORTED (@Timeout 25 min)** | The interrupter caught red-handed: JUnit's timeout interrupt → `GENERATION INTERRUPTED at seq 503` (~25 min at 20/min — the ~24-min knee of soak attempts 1-2, finally attributed). No verdict artifacts (aborted). Fixed by `d4720ca`; its leaked checker/sampler threads pollute the rest of the shared console (binary predates `eac200e`) |
| 20260801-214325--6973268155056049009 | SOAK 120-min @ 20/min, seed -6973268155056049009 | **PASS** | **The soak of record** (fills Issue 11/12 PENDING-SOAK in `docs/open-issues.md`): 2,376 wf (HAPPY 974 / CONDITIONS_LOOP 454 / SIGNAL_TIMEOUT 472 / SAGA_WITHDRAWAL 476), `violations []`, census 0 dups / 0 missing comp, 476 compensations = exactly the SAGA_WITHDRAWAL count, 13 redelivered groups all `consumedTwin=true` (Ruling 3), checker 245 cycles / 1 unreachable / max streak 1, drain 76s vs 240s SLA, benchmark tail 23 wf @ 6 nodes + 27 wf @ 3 nodes (stopped LOAN_B/VERIFY_B/UW_B). Identity stamp `gitHead 7113e06`; binary compiled @`b2b5c65` (see docs/open-issues.md Issue 11 provenance caveats) |

**PR-gate verdict: GREEN 3× CONSECUTIVELY** (fresh seeds; every run's evidence
carries embedded identity). Issue 12 metrics: `metrics.csv` per run; Issue 11
duplicate counts: `side-effects.json` per run (0 duplicates in all three
streak runs — the Issue 18 stand-down makes the loser lose fast; the
redelivered-signal findings are the Ruling 3 mandatory report).

**Soak verdict: PASS** (`20260801-214325--6973268155056049009`, the run of
record above) — 0 duplicates across 2,376 further workflows, taking the
cycle's measured total to 0/2,587.

**What is committed vs local-only.** This evidence tree is gitignored;
files are force-added selectively. For the soak-of-record run dir, the files
the docs cite are committed: `run-summary.json`, `side-effects.json`,
`benchmark-tail.json`, `metrics.csv`, `chaos-actions.jsonl`,
`heap-samples.csv`. **Not committed** (local-only, size): its `logs/` subdir
(37 MB of node logs) and `ledger.jsonl` (~0.9 MB). Other run dirs remain
local-only unless individually noted.
