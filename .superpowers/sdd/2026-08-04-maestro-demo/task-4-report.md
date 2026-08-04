# Task 4 report — the runbook and driver scripts

**Status: complete and rehearsed.** Branch `worktree-demo`, commits `f31782c..6e52b5b`
(8 commits). `maestro-core`, `maestro-samples/sample-loan-origination/e2e/` and the
chaos harness are untouched — verified with `git diff --name-only`.

## Delivered

| File | Lines | What |
|---|---|---|
| `demo/RUNBOOK.md` | 751 | T-30 pre-flight, process identity, reset, scenarios 1–5, deep dives D1–D6, a Q&A section, teardown |
| `demo/scripts/preflight.sh` | 153 | 9-step cold-start gate ending in a PID + jar-SHA256 identity table |
| `demo/scripts/reset.sh` | 75 | clean slate, measured 17 s |
| `demo/scripts/drive-loan.sh` | 297 | `happy` / `conditions` / `withdraw` / `crash` / `finish` / `events` |
| `demo/scripts/restart-loan-app.sh` | 80 | **not in the brief** — see "what the rehearsal changed" |
| `demo/.evidence/task-4-runbook-rehearsal.log` | 268 | final-pass transcript with an identity header |

Every scenario is a numbered section with **RUN / POINT AT / SHOULD APPEAR /
TIMING / FALLBACK**, and every TIMING is now a measured number, not an estimate.

## All nine context gotchas are honoured

1. **Auto-approve.** No step sends the presenter to the pending queue for a
   loan that will not be there. The DTI bands are stated (`< 3.0` auto-approve,
   `3.0–6.0` human, `> 6.0` auto-reject) and each scenario's parameters are
   chosen to land where the narrative needs them: `happy` DTI 2.0 (no human),
   `crash` 3.5, `withdraw` 4.0, `conditions` 4.5 (all genuinely queue).
2. **Two Jaeger traces across the move** — §D1 point 4, with the archived ids.
   The rehearsal found this generalises: see below.
3. **Jaeger's ~30 s flush** — §3, with a line for the presenter to say during it.
4. **`parallel()` is two branches** — §D1 point 3, with the `ParkingLot`
   one-waiter-per-key reason and the exact sentence to say.
5. **The three-way send fan-out is identical in v1** — §3 carries an explicit
   "do not point at this", with the 24/36/46 ms offsets.
6. **`version(changeId, DEFAULT_VERSION, 2)`** — §D1 point 1 shows the correct
   form and cites the RED evidence for why the literal `1` is wrong.
7. **What `version()` pins** — §D1 point 2, worded as "every loan that has
   already recorded its application", with the zero-event case called out.
8. **Stand-down reads zero by design** — §5, including the honest caveat that a
   misspelled metric would look identical, and the scrape evidence that rules it
   out. The rehearsal confirmed `maestro_standdown_total` returns 0 series.
9. **maestro-admin is not a scrape target** — §5, with the 404 reproduced in
   the evidence log.

Reference values are quoted only where greppable: trace ids and span counts from
`demo/.evidence/task-3-jaeger-v1-vs-v2-traces.log`, the branch bands 5001/6001/7001,
and the chaos numbers (2376 workflows, run `20260801-214325--6973268155056049009`)
from `tasks/todo.md`. Issue 23 is stated honestly in §Q as the answer to "is the
Kafka tracing real".

## What the rehearsal changed

The runbook was executed top to bottom. Four things broke, all now fixed and committed:

1. **`preflight.sh` was not re-runnable** (`83e3911`). Its port-free check failed
   on the demo's own containers. It now accepts ports published by this compose
   project and hard-fails only on a foreign listener, with the right teardown
   command per port class.
2. **`upload_doc` passed its arguments to curl as URLs** (`e95af5a`) —
   `Could not resolve host: tax-return`. The document still landed, so this would
   have shipped as visible garbage on stage.
3. **Scenario 2 could not recover as written** (`963b418`). After `kill -9` on
   loan-application only, `start-services.sh` aborts because 8092/8093/8080 are
   still healthy. Added `restart-loan-app.sh`, which restarts exactly the process
   that died and tolerates a pid file pointing at a corpse. Same commit replaced
   the weak "activity count" check in `finish` with a **byte-diff** of the
   pre-crash event rows against the same rows after recovery.
4. **Six documented claims were wrong** (`cd23863`), most consequentially:
   - **Any JVM restart splits the Jaeger trace**, not just the v1→v2 move. The
     crash-scenario loan is two traces (10 + 23 spans in the one archived run). A
     presenter promising one trace for a loan they just crashed looks wrong.
   - The parked panel peaks at **3× loans in flight** (9 for three loans), not at
     the loan count — each loan parks a workflow in all three services.
   - `v1-to-v2-move.sh`'s PIN 6 span counts are **pre-flush undercounts**: it
     reported 12 spans where a re-query 35 s later gave 28, matching the archive.
     The runbook now says not to read PIN 6 aloud.
   - D6 needs `LOAN_URL=http://localhost:8094` to drive node B; without it you get
     connection-refused, not an adoption failure.

## Measured timings (all in the runbook)

**Superseded — see "Fix round 1" below.** As written at the time: preflight 36 s
warm · reset 17 s · scenario 1 15 s · scenario 2 13 + 5 + 5 s · scenario 4 18 s ·
D1 32–36 s · `RESTORE_V1=1` 5 s · D6 adoption 27 s after the kill. Five of those
did not survive re-measurement; the current figures are in the fix-round table.

## Concerns for whoever picks this up

- `v1-to-v2-move.sh` hardcodes `loan-application-service-0.3.0-SNAPSHOT.jar`. A
  version bump silently breaks D1. Flagged in §D1 but not fixed — it is task 3's file.
- `restart-loan-app.sh` duplicates `start-services.sh`'s env and JVM options. Both
  files say so. A shared `demo/scripts/lib/jvm-env.sh` would be the elegant fix;
  it was not worth the blast radius this late.
- D6 was rehearsed but is off by default and adds ~360 MiB. Decide before the demo.
- The deck (task 5) should key its `DO:` blocks to §1–§5 and §D1–§D6.

---

# Fix round 1

Every item from `task-4-fixes.md`, one commit each. All figures below are
re-measured on this branch, not carried over.

## F1 (CRITICAL) — fixed at the cause, not documented

Re-measuring scenario 2 gave phase 3 at **250 s**, worse than the reviewer's
56 s and far worse than the runbook's 5 s
(`demo/.evidence/task-4-fix-f1-scenario-2-phase-timings.log`). Root cause:
`maestro.recovery.poll-interval` defaults to 60 s and no loan service overrides
it; the crash path crosses that poll more than once.

Per the coordinator ruling, the demo is tuned rather than the sample:
`-Dmaestro.recovery.poll-interval=5s` is now set from `start-services.sh` and
`restart-loan-app.sh` (override with `DEMO_RECOVERY_POLL_INTERVAL`). The
samples' `application.yml` is untouched — the loan e2e runs against it and the
committed default should stay production-sane. Binding is proved by each JVM's
own startup line (`recovery poller started (interval=PT5S)`).

**Re-measured scenario 2, end to end** — `demo/.evidence/task-4-fix-f1-scenario-2-after-poll-interval.log`:

| phase | before (60 s poll) | after (5 s poll) |
|---|---|---|
| phase 1 `crash` | 32 s | **17 s** |
| `kill -9` | instant | instant |
| `restart-loan-app.sh` | 4 s | **5 s** |
| phase 3 `finish` | **250 s** | **40 s** |

The runbook now carries these, a 90 s budget, a SAY line written for the one
remaining pause, and the knob as a teaching point: *"recovery is as fast as you
configure it to notice — 5 s here, 60 s by default."*

**What is still slow, named rather than padded (ruling point 4):** the 33 s
inside `finish` is **Kafka's**, not Maestro's. A `kill -9` leaves the broker
believing the dead consumer is still a group member until `session.timeout.ms`
(45 s default) lapses, so the restarted node cannot be assigned
`loans.underwriting.decisions`. Measured: join requested 15:21:39, joined
15:22:15. The same node rejoining after a clean restart takes 3 s. Left at the
default deliberately — it is a true statement about hard kills and message
buses, and shrinking it would buy ~30 s at the cost of another knob.

## F2 — D6 adoption re-measured in the demo's own config

The archived 49 s was measured under the **e2e's** 60 s poll, so quoting it in
a runbook that now polls at 5 s would have been wrong twice over. Measured D6
two-node end to end instead
(`demo/.evidence/task-4-fix-f2-d6-adoption-latency.log`): **adoption 27 s**,
whole `finish` through node B **49 s**. Bound is lock TTL 30 s + one 5 s poll +
slack. The fallback's abandon threshold went 90 s → **120 s**; at 90 s a
presenter would have killed a run that takes 49 s when it works.

**Found while measuring it:** D6's own ownership check, `grep -l <id>` across
both node logs, matches **both** — the non-owner logs *"instance lock is held by
another node"* every poll (4 hits in the owner's log against 25 in the peer's).
Now greps `Started workflow 'loan-<id>'`, verified to match the owner only.
Committed separately (`cee2c73`).

## F3 — the four unevidenced figures

`demo/.evidence/task-4-fix-f3-unevidenced-figures.log`, `…-f3-f4-preflight.log`.

| claim | measured | action |
|---|---|---|
| preflight 36 s warm | **36 s** | confirmed exactly; now cited |
| scenario 1 15 s | **20 s**, then **14 s** | stated as 14–20 s |
| §5 parked peak 9, zero by 20 s | peak **12** at t+6 s, zero at t+18 s | figures replaced; "do not promise a number" strengthened |
| `RESTORE_V1` 5 s | **6 s** | corrected |

The parked peak is the interesting one: 9 is the arithmetic (3 loans × 3
services) and 12 is what the gauge actually read, because the loans overlap and
a workflow parked by the previous scenario still counts.

## F4 — "safe to re-run" was false

Confirmed by running it: with the four demo JVMs healthy, `preflight.sh` exits
**1 in ~2 s** naming 8080/8091/8092/8093. Replaced with what re-running
actually requires (`stop-services.sh` first; the containers can stay up).

## Minors

PIN 6's undercount is 11 spans not 12 · the §2 diff is phase 3 not phase 2 ·
SHOULD APPEAR no longer hardcodes "12 rows" · §5 is five panels in three
two-column rows, parked beside started/completed/failed · the `conditions`
scenario is now labelled as having no section · the 10 + 23 span split is
described as the one archived run, not "reproduced twice".

## Not fixed, per the ruling

`v1-to-v2-move.sh`'s hardcoded jar name and `restart-loan-app.sh`'s duplication
of `start-services.sh` — both post-demo.

## Concerns

- `restart-loan-app.sh` now duplicates one more thing from `start-services.sh`:
  the poll-interval flag. Both files carry a warning comment, and the flag is
  the one that scenario 2 depends on, so a future edit to one and not the other
  reintroduces exactly the 250 s failure this round fixed. The shared
  `demo/scripts/lib/jvm-env.sh` in the earlier concerns list is now worth more
  than it was.
- Scenario 1 varies 14–20 s and phase 1 varies 17–32 s across runs; both are
  dominated by simulated provider latency and by how fast the underwriting desk
  queues. The runbook states ranges, not points.
- Every figure above is one or two runs on one laptop. They are honest
  measurements, not distributions.
