# Maestro Demo — Runbook

Operator script for the loan-origination demo. Read `demo/DOMAIN-BRIEF.md` once
before your first rehearsal; this file is what you keep open on stage.

**All commands are run from the repository root**, in a terminal you leave open
for the whole session. Every scenario is numbered; the deck's `DO:` blocks
reference these numbers.

**The three rules**

1. Nothing on screen is trusted until the process-identity table matches
   (§0.2). A previous cycle demoed a stale JVM from an earlier run for four
   minutes before anyone noticed.
2. `demo/scripts/reset.sh` between rehearsals. Always. It also un-does the
   v1→v2 move.
3. Every scenario below has a **FALLBACK** line. Say it, move on, do not debug
   in front of the room.

| What | Where |
|---|---|
| Grafana | <http://localhost:3000> (anonymous, already Admin) |
| Prometheus | <http://localhost:9090> |
| Jaeger | <http://localhost:16686> |
| maestro-admin | <http://localhost:8080/admin> |
| loan-application | <http://localhost:8091> |
| verification-gateway | <http://localhost:8092> |
| underwriting | <http://localhost:8093> |
| Postgres / Valkey / Kafka | 5433 / 6380 / 29093 |

**The scripts, and when each one is the right answer**

| Script | Use it when |
|---|---|
| `preflight.sh` | Once, T-30. Cold start plus every gate. |
| `reset.sh` | Between rehearsals. Also un-does the v1→v2 move. |
| `drive-loan.sh <scenario>` | Every scenario. `happy`, `conditions`, `withdraw`, `crash`, `finish <id>`, `events <id>`. |
| `restart-loan-app.sh` | After scenario 2's `kill -9`, when only loan-application died. |
| `start-services.sh` / `stop-services.sh` | All four JVMs at once. Only when *none* of them is running. |
| `v1-to-v2-move.sh` | Deep dive D1. `RESTORE_V1=1` puts v1 back on 8091 alone. |

---

## §0 — T-30 pre-flight

### 0.1 One command

```bash
demo/scripts/preflight.sh
```

**What it does, in order:** checks host tools → checks all 11 ports are free
*before starting anything* → pulls images → builds every jar **including
`loan-application-v2.jar`** → `compose up -d` and waits for each container
healthy → verifies all 11 Kafka topics exist → starts the four host JVMs →
drives one throwaway loan end to end → prints the process-identity table.

**Timing:** 3–6 minutes on a truly cold machine (the image pull dominates).
**Measured warm, with `DEMO_SKIP_PULL=1`: 36 s**, including a full Gradle
build. It is safe to re-run: ports already published by this demo's own
containers are accepted, only a foreign listener fails it.

**Should appear:** the last line is

```
PREFLIGHT PASSED in <n>s. Now run: demo/scripts/reset.sh
```

Anything else is a hard stop — it exits non-zero and tells you which step
failed. Fix it now, not on stage.

**Common failures and the fix:**

| Symptom | Fix |
|---|---|
| `ports held by something that is not this demo: 5433 6380 29093` | The loan sample's *own* compose stack is up. `docker compose -f maestro-samples/sample-loan-origination/docker-compose.yml down`. The two stacks cannot both run. |
| `ports held by something that is not this demo: 8091 8092 8093 8080` | `demo/scripts/stop-services.sh` |
| `topics still missing after 120s` | `docker compose -f demo/docker-compose.yml logs kafka-init`. Topics are **never** auto-created (`KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"`); a missing topic makes `startWorkflow` hang, not fail. |
| `loan-application-v2.jar missing` | `./gradlew :maestro-samples:sample-loan-origination:loan-application-service:v2BootJar`. Deep dive D1 cannot run without it. |

### 0.2 Process identity — do this every single time

The last block `preflight.sh` prints:

```
    SERVICE                    PID      JAR-SHA256(12) JAR
    loan-application-service   12345    a1b2c3d4e5f6   loan-application-service-0.3.0-SNAPSHOT.jar
    verification-gateway-...   12346    ...            verification-gateway-service-0.3.0-SNAPSHOT.jar
    underwriting-service       12347    ...            underwriting-service-0.3.0-SNAPSHOT.jar
    maestro-admin              12348    ...            maestro-admin-0.3.0-SNAPSHOT.jar
```

**Check two things:** every PID is alive, and the `loan-application started
at:` timestamp underneath is from this session. To re-print it at any time
without a full preflight:

```bash
for f in demo/.run/*.pid; do n=$(basename "$f" .pid); p=$(cat "$f"); \
  printf '%-28s %-7s %s\n' "$n" "$p" "$(ps -o command= -p "$p" | tr ' ' '\n' | grep '\.jar$' | tail -1)"; done
```

After the v1→v2 move (D1) the loan-application row must read
`loan-application-v2.jar`. If it still reads
`loan-application-service-0.3.0-SNAPSHOT.jar`, the move did not take.

### 0.3 Reset between rehearsals

```bash
demo/scripts/reset.sh
```

Stops the four JVMs, truncates the Maestro tables in all three service
databases, clears `maestro_admin`, flushes Valkey, restarts the JVMs **from
the v1 jars**. Containers stay up, so Grafana history and Jaeger traces
survive on purpose. Idempotent; safe to run twice.

**Timing:** measured **17 s**. It prints `clean slate in <n>s`. If it takes
longer than 30 s, something is wedged — `docker compose -f demo/docker-compose.yml ps`.

Note: reset does not drain Kafka. A handful of `unknown workflow` warnings in
`demo/.run/*.log` right after a reset are stale messages for deleted
workflows, and are expected.

---

## §1 — Scenario 1: start a loan, follow it across three services

**RUN**

```bash
demo/scripts/drive-loan.sh happy
```

(Or `demo/scripts/drive-loan.sh happy alice-demo` to pick the id yourself —
the workflow id is always `loan-<applicationId>`.)

**POINT AT**

1. The terminal, while it waits: three verification providers answering on
   their own schedule.
2. <http://localhost:8080/admin/workflows> — the instance appears, status
   moves `RUNNING` → `WAITING_SIGNAL` → `COMPLETED`.
3. Click the workflow id. The **event log** is the whole point: sequence
   numbers, `ACTIVITY_COMPLETED` rows with the activity name, `SIGNAL_RECEIVED`
   rows with the signal name. This table *is* the workflow's memory.
4. The same event log is printed in your terminal at the end.

**SHOULD APPEAR**

- `3/3 verification.result signals recorded`
- `<id> is COMPLETED`
- Final JSON with `"status":"FUNDED"`, a `rateLockId` and a `disbursementId`.
- An event log ending `WORKFLOW_COMPLETED`.

**TIMING:** measured **15 s**. There is a hard ~8 s floor inside that: the
appraisal provider simulates 8 s of latency (`credit PT2S`, `employment PT5S`,
`appraisal PT8S`). Budget 30 s so you are never waiting on it.

**SAY, during the wait:** "The workflow is not running right now. It is a row
in Postgres. No thread is parked, no timer is held in memory — if I killed
this process during this pause you would not be able to tell afterwards."
(That is scenario 2.)

**Why nobody approves this one:** $200,000 on $100,000 income is DTI 2.0,
below the 3.0 auto-approve line, so the underwriting desk decides it without a
human. **Do not go looking for it in the pending queue — it will not be
there.** The scenarios that *do* queue for a human are `conditions` (DTI 4.5),
`withdraw` (DTI 4.0) and `crash` (DTI 3.5).

**FALLBACK:** *"The verification providers are simulating real-world latency
and one of them is being slow — I have a completed run of this exact flow in
the event log, let me show you that instead."* → switch to
<http://localhost:8080/admin/workflows> and open any earlier `COMPLETED`
instance, or run `demo/scripts/drive-loan.sh events <id>`.

---

## §2 — Scenario 2: `kill -9` and recover

Three commands, in order. Phase 1 parks a loan mid-flight and tells you
exactly what to type next.

**RUN — phase 1**

```bash
demo/scripts/drive-loan.sh crash
```

It ends by printing the id and the next two commands. Copy them.

**RUN — phase 2 (the moment)**

```bash
kill -9 $(cat demo/.run/loan-application-service.pid)
```

**POINT AT:** nothing yet. Say: *"No graceful shutdown. No drain. SIGKILL. The
process cannot run a shutdown hook even if it wanted to."*

Optionally show it is gone:

```bash
curl -s -o /dev/null -w '%{http_code}\n' --max-time 2 http://localhost:8091/actuator/health || true
```

→ `000`.

**RUN — phase 3**

```bash
demo/scripts/restart-loan-app.sh
demo/scripts/drive-loan.sh finish <id>
```

**Not `start-services.sh`.** It starts all four JVMs and refuses to run while
any of their ports is taken — and 8092, 8093 and 8080 are still perfectly
healthy, because you only killed one process. `restart-loan-app.sh` restarts
exactly the process that died, and tolerates a pid file pointing at a corpse.

**POINT AT**

- Phase 1 snapshotted the event rows to
  `demo/.run/crash-<id>-before-rows.txt`. Phase 2 re-reads the same rows after
  recovery and **diffs them**. An empty diff is the proof.
- The final event log: sequence numbers are contiguous and unique, and every
  activity that ran before the crash has exactly one row.

**SHOULD APPEAR**

```
==> PROOF 1 — the 12 rows recorded before the kill -9 are byte-identical now
    diff is empty. Nothing was rewritten, renumbered, or re-executed.

==> PROOF 2 — duplicate sequence numbers after the crash: 0  (must be 0)

==> DONE — <id> FUNDED across a kill -9. Nothing re-executed.
```

Both proofs are assertions: if either fails the script exits non-zero and
prints the offending diff.

**TIMING:** measured — phase 1 **17 s** (allow up to 35 s; it waits on three
simulated providers and on the underwriting desk), the kill is instant,
`restart-loan-app.sh` **5 s**, `finish` **40 s**. Budget **90 s** including
what you say. Evidence:
`demo/.evidence/task-4-fix-f1-scenario-2-after-poll-interval.log`.

### The one pause in this scenario — fill it, do not wait it out

Almost all of `finish`'s 40 s is a single wait: **33 s** between posting the
underwriter's decision and `the rate lock recorded`. It is the only silence in
the demo, it is on the demo's most important beat, and it is long enough that
standing quietly through it reads as a hang. Start talking as soon as
`waiting for the rate lock` appears:

**SAY, during the wait:** *"Watch what has to happen before that line appears.
The node that owned this workflow died holding a lock — so first that lock has
to expire; nobody can hand it over, because nothing got a chance to release it.
Then a recovery poll on another node has to notice the workflow is
unowned and take it. Then the workflow method is re-invoked from the very first
line — and every activity that already completed returns its stored result out
of Postgres instantly. No HTTP call. No Kafka publish. It runs the replay at
memory speed until it reaches the first step with no stored result, and only
then does real work happen again. Everything you are waiting for right now is
the engine deciding it is allowed to resume — the resume itself is
instantaneous."*

**SAY, when the rate lock line lands:** *"There it is. And the proof is on the
next screen: the twelve rows recorded before the kill are byte-identical."*

### If someone asks "how fast does it recover?"

Answer with the knob, not a number: *"As fast as you configure it to notice.
Here the recovery poll is 5 seconds; the shipped default is 60."* The demo sets
`-Dmaestro.recovery.poll-interval=5s` from `start-services.sh` and
`restart-loan-app.sh` so recovery is watchable. The samples' committed
`application.yml` is untouched and still takes the production-sane 60 s
default — at that default this same phase measured **250 s**
(`demo/.evidence/task-4-fix-f1-scenario-2-phase-timings.log`).

**What the remaining 33 s actually is — it is not Maestro's.** With the poll at
5 s the wait is dominated by **Kafka**: a `kill -9` leaves the broker still
believing the dead consumer is a live group member until its
`session.timeout.ms` (45 s by default) runs out, so the restarted node cannot
be assigned `loans.underwriting.decisions` and cannot see the decision until
the group rebalances. Measured: join requested `15:21:39`, joined `15:22:15`.
The same node rejoining after a *clean* restart takes 3 s. If asked, that is
the honest answer — the recovery mechanism was ready long before the message
bus was.

**FALLBACK:** *"Recovery is on a poll interval and we have just caught it
between polls — the important artefact is the event log, which is unchanged
by the crash."* → `demo/scripts/drive-loan.sh events <id>` and point at the
contiguous sequence numbers. If the service will not restart, `demo/scripts/reset.sh`
and move to scenario 3; do not retry the crash live.

---

## §3 — Scenario 3: the Jaeger trace

Run this **after** scenario 1 or 2 has completed. Jaeger needs roughly **30
seconds** after the workflow completes before the trace looks whole — spans
are batched and exported by three separate processes.

**RUN**

```bash
open http://localhost:16686/search?service=sample-loan-application-service
```

(Services are `sample-loan-application-service`,
`sample-verification-gateway-service`, `sample-underwriting-service`.)

Or fetch the trace ids for a specific loan:

```bash
curl -sS --get http://localhost:16686/api/traces \
  --data-urlencode 'service=sample-loan-application-service' \
  --data-urlencode 'tags={"maestro.workflow.id":"loan-<id>"}' \
  --data-urlencode 'lookback=2h' --data-urlencode 'limit=20' \
  | jq -r '.data[] | "\(.traceID)  spans=\(.spans|length)"'
```

**SAY during the 30 s flush:** *"Give the collector a moment — three separate
JVMs are batching their spans independently. While it lands: nothing in this
trace was hand-instrumented for the demo. It is the engine's own spans plus
Spring's."*

**POINT AT**

1. One trace, three service colours — the workflow crosses process boundaries
   and the trace does not break.
2. The gap in the middle: the workflow was **parked**, durably, awaiting a
   signal. Time passes inside a single trace with no thread held open.
3. Individual activity spans with their durations.

**SHOULD APPEAR:** for a `happy` loan, **one** trace of roughly 20–35 spans
across all three services (a rehearsal measured 31). A verified v1-shaped
example, archived: `b39b554f4c94c659f68468588295431c  spans=20`
(`demo/.evidence/task-3-jaeger-v1-vs-v2-traces.log`).

**TIMING:** 30 s of flush, then it is instant.

**Any JVM restart splits the trace into two.** This is not specific to the
v1→v2 move — a loan that lived through scenario 2's `kill -9` shows up as two
traces as well (a rehearsal measured 10 spans before the crash and 23 after).
So: run scenario 3 against a loan that did **not** get crashed, or say up
front that a restarted workflow produces two traces and show both. Do not
promise one trace for a loan you just killed a process under.

**Do not point at** the three verification *request* spans as evidence of
anything concurrent. The three-way **send** fan-out looks identical in v1 and
v2 (offsets 24 / 36 / 46 ms in the archived v1 trace). What changes in v2 is
what the workflow *waits on*, not what it sends. See D1.

**FALLBACK:** *"The collector hasn't flushed yet — here is the same trace from
this morning's run."* → open `demo/.evidence/task-2-jaeger-cross-service-trace.log`,
or search Jaeger for any earlier loan.

---

## §4 — Scenario 4: withdrawal after rate lock → saga compensation

**RUN**

```bash
demo/scripts/drive-loan.sh withdraw
```

This one *does* queue for a human underwriter (DTI 4.0), and the script posts
the approval for you.

**POINT AT**

1. The `Reserved rate lock ... at 5.25%` line the script echoes — **money is
   now committed**. This is the expensive point to change your mind.
2. The withdrawal going in *after* that.
3. The `COMPENSATION: released rate lock ...` line.
4. <http://localhost:8080/admin/failed> — the instance is `FAILED`, and its
   event log shows the compensation.

**SHOULD APPEAR**

```
    COMPENSATION: released rate lock <id> for loan <id>
    <id> is FAILED
```

**TIMING:** measured **18 s** (verifications 8 s, the underwriting round-trip,
then the signature fan-in). Budget 45 s.

**SAY:** *"The workflow author wrote no rollback path. They annotated
`reserveRateLock` with `@Compensate("releaseRateLock")` and threw an
exception. The engine unwound the compensation stack LIFO. If disbursement had
already happened it would have reversed that first, then the rate lock."*

**Honest detail if asked:** `reverseDisbursement` is wired but unreachable in
this sample — `disburse` is the last step, so nothing fails after it. It is
there to show the LIFO ordering, not because the sample exercises it.

**FALLBACK:** *"The signature fan-in is what pushes it into the second
withdrawal gate and it hasn't landed yet — here is a completed compensation."*
→ `grep -a COMPENSATION demo/.run/loan-application-service.log`.

---

## §5 — Scenario 5: Grafana under load

**RUN** — start three loans at once, then open the dashboard:

```bash
for i in 1 2 3; do demo/scripts/drive-loan.sh happy "load-$i" & sleep 1; done
open http://localhost:3000
```

(The dashboard is the Grafana home dashboard — no navigation needed. Direct
link: <http://localhost:3000/d/maestro-demo>.)

**POINT AT** — five panels, top to bottom:

| Panel | What it shows | What to say |
|---|---|---|
| **Workflows started / completed / failed** | rate over the three counters | "started and completed track each other; failures are flat" |
| **Workflows parked** | live gauge of `maestro_workflows_parked` | "this is the number that matters — parked workflows cost nothing" |
| **Activity duration p50 / p95 by activity** | per-activity histogram quantiles | "the appraisal provider's 8 s latency is visible, and it is not the engine's" |
| **Recovery adoptions** | `maestro_recovery_adopted_total` | non-zero only after scenario 2 or D6 |
| **Stand-downs by reason** | flat line at zero | see below |

**SHOULD APPEAR:** the parked-count panel rises to roughly **3× the number of
loans in flight** — each loan parks a workflow in the loan service, one in
verification-gateway and one in underwriting. A rehearsal with three loans
peaked at **9** and was back to **0** within 20 s. Do not promise the number
3; promise "it climbs and comes back to zero". Scrape interval is 5 s, so give
it two scrapes.

**TIMING:** measured — peak within 5 s of launching, back to 0 by 20 s.

**Two things to say before anyone asks:**

- **"Stand-downs by reason reads zero, and that is the correct answer."** The
  panel carries a deliberate `vector(0)` series so it draws a flat line
  instead of "No data". Zero stand-downs means no workflow hit an event it
  could not interpret. Caveat, stated honestly: *a misspelled metric name
  would look exactly the same* — the metric names on this dashboard were
  verified against a live scrape
  (`demo/.evidence/task-2-metric-names-scrape.log`).
- **maestro-admin is not on this dashboard and never will be.** Its jar
  carries no Micrometer Prometheus registry, so `:8080/actuator/prometheus`
  returns 404. It is a dashboard, not a scrape target
  (`demo/.evidence/task-2-admin-no-prometheus-endpoint-rerun.log`).

**FALLBACK:** *"Grafana is reading from Prometheus on a 5-second scrape and we
are inside the first interval."* → <http://localhost:9090/targets> to show the
three services `UP`, then query `maestro_workflows_parked` in Prometheus
directly.

---

## §D1 — Deep dive: rolling deploy, v1 → v2, with loans in flight

The strongest section. Everything is scripted.

**RUN**

```bash
demo/scripts/v1-to-v2-move.sh
```

**PREREQUISITE:** both jars must already exist — the script does **not**
build. `preflight.sh` builds them. It also hardcodes
`loan-application-service-0.3.0-SNAPSHOT.jar`; if the project version is
bumped, this script must be updated.

**What it does:** prints both jars' fingerprints (PIN 0) → starts a loan under
v1 and waits until it has recorded a verification result (PIN 1) → SIGTERMs
v1 and starts v2 on the same port and pid file (PIN 2) → drives the in-flight
loan to completion and asserts it stayed on the **sequential** path (PIN 3) →
starts a fresh loan and asserts it took the **parallel** path (PIN 4) → dumps
the parking-lot evidence (PIN 5) → prints both loans' Jaeger trace ids (PIN 6).

**POINT AT**

1. PIN 0's two jar SHAs and the `parallel-verification` string count: 0 in v1,
   non-zero in v2. Same class, same FQN, different jar.
2. PIN 3's assertion: the in-flight loan finished with `VERSION_MARKER == 0`
   and **zero events at sequence ≥ 1000**. It never saw the new code path.
3. PIN 4's assertion: the new loan has `VERSION_MARKER == 1` and events in the
   branch bands.
4. PIN 6's two trace ids, opened side by side.

**SHOULD APPEAR:** the script exits 0. Every PIN asserts; a violated
assertion exits non-zero and says which.

**TIMING:** measured **32–36 s** end to end
(`demo/.evidence/task-3-live-v1-to-v2-move.log`). The swap itself is ~7 s.

**PIN 6's span counts are undercounts — do not read them out.** The script
queries Jaeger the moment it finishes, before the collector has flushed. In a
rehearsal PIN 6 reported the v2 loan at 12 spans; re-querying 35 s later gave
**28**, which is exactly the archived reference. Wait, then re-run the query
from §3 with the loan id the script printed.

### The four things to say precisely

**1. The version gate.** Show the code as:

```java
int v = workflow.version("parallel-verification", WorkflowContext.DEFAULT_VERSION, 2);
```

Not `version("parallel-verification", 1, 2)`. `DEFAULT_VERSION` is the marker
for "this workflow started before the change existed". Writing the literal `1`
as the minimum makes **every in-flight loan fail** with
`UnsupportedWorkflowVersionException` — that is not a hypothetical, it is a
recorded RED test (`demo/.evidence/task-3-red-2-brief-literal-bounds-fail-inflight.log`).

**2. What actually gets pinned.** `version()` pins **every loan that has
already recorded its application** — not every loan that has been started. A
workflow with zero recorded events takes v2 on its first run. The version is
decided the first time the workflow *reaches the gate*, and that decision is
written to the event log as a `VERSION_MARKER` row.

**3. The v2 change is TWO branches, not three.** v2 runs *verification
collection* and *document collection* concurrently. It is **not** a three-way
verification fan-out — three concurrent branches awaiting the same signal name
are impossible on this engine, because the parking lot holds one waiter per
`workflowId:signal:name` key. The line to say is: **"documents now collect
while the verifications are still outstanding."**

**4. Two traces, not one.** The in-flight loan appears in Jaeger as **two
separate traces**, because restarting the JVM ends the first one. The archived
pre-swap fragment is `ec798a7ed25b754fae81371d77cdbcc1  spans=13` and the
post-swap continuation is `b39b554f4c94c659f68468588295431c  spans=20`; the
v2-native loan is a single `5c79c33935721eb3afe5f9ae8ca742f5  spans=28`
(`demo/.evidence/task-3-jaeger-v1-vs-v2-traces.log`). Say this *before* you
open Jaeger. A presenter who promises one trace and finds two looks wrong; a
presenter who predicts two looks like they know the system.

**AFTER D1, ALWAYS:**

```bash
RESTORE_V1=1 demo/scripts/v1-to-v2-move.sh    # restores just 8091, measured 5 s
# or
demo/scripts/reset.sh                          # restores everything, measured 17 s
```

Leaving v2 running poisons every later scenario's event log with branch-band
sequence numbers, and D2's walk-through will not match what you say.

**FALLBACK:** *"The deploy assertion is strict and it has caught something —
which is the point of the assertion. Here is the recorded run."* →
`demo/.evidence/task-3-live-v1-to-v2-move.log`, then
`RESTORE_V1=1 demo/scripts/v1-to-v2-move.sh` before continuing.

---

## §D2 — Deep dive: how memoization works

Walk one workflow's rows, then crash it and walk them again.

**RUN**

```bash
demo/scripts/drive-loan.sh events <id>          # any completed loan
```

**POINT AT** — a v1 event log reads, in order:

```
    1  ACTIVITY_COMPLETED    LoanActivities.recordApplication
    2  ACTIVITY_COMPLETED    LoanMessagingActivities.requestVerifications
    3  SIGNAL_RECEIVED       $maestro:awaitSignal:verification.result
   ...
       ACTIVITY_COMPLETED    FundingActivities.reserveRateLock
       SIGNAL_RECEIVED       $maestro:awaitSignal:package.signed
       ACTIVITY_COMPLETED    FundingActivities.disburse
       WORKFLOW_COMPLETED
```

**SAY**

- "One row per step. The sequence number is the step's address."
- "On recovery the method is re-invoked from the top. At each activity call
  the proxy asks Postgres: is there a row at this sequence number? If yes it
  returns the stored result and the activity body never executes. If no, it
  executes, persists, returns."
- "`SIDE_EFFECT` rows are `workflow.currentTime()` and `workflow.randomUUID()`
  — the non-deterministic bits, recorded once so replay sees the same values."
- "There is no snapshot, no serialised continuation, no thread state on disk.
  This table is the whole of it."

**The uniqueness guarantee:** `idx_wf_event_replay` is `UNIQUE (workflow_instance_id,
sequence_number)`. Two nodes racing on the same step cannot both persist a
result. Show it:

```bash
docker exec maestro-demo-postgres-1 psql -U maestro -d loan_application -c '\d maestro_workflow_event'
```

**Under v2** the numbers jump: parallel branches partition the sequence space
by the engine's `p*1000 + (i+1)*1000` rule. With the fork at parent sequence 4,
branch 0 allocates from **5001**, branch 1 from **6001**, and the parent
resumes at **7001**. If you see those bands, you are looking at v2 — which is
why D1 ends with a restore.

**TIMING:** as long as you talk. No moving parts.

**FALLBACK:** none needed — this is a table read. If Postgres is unreachable,
the same log is in your terminal scrollback from scenario 1.

---

## §D3 — Deep dive: the evidence

No live commands. One slide's worth of numbers, all from the project's own
chaos harness (soak of record, `tasks/todo.md`):

- **2-hour chaos window**, verdict PASS
- **2376 workflows** driven
- **0 invariant violations**
- **0 duplicate side effects**
- run id `20260801-214325--6973268155056049009`

**SAY:** *"The harness kills nodes, partitions the lock backend and delays
Kafka while workflows run, then checks two invariants: no workflow ends in an
inconsistent state, and no side effect happens twice. It found four engine
defects. They are fixed. That is why I am willing to run `kill -9` in front of
you."*

**Do not** run the chaos harness live. It is a two-hour job and it is out of
scope for this demo by design.

---

## §D4 — Deep dive: architecture

No live commands. `docs/maestro-architecture.md` has the diagram.

Four sentences:

1. **There is no server.** Maestro is a library in your service. There is no
   cluster to run, no control plane to upgrade, nothing to page someone about
   at 3am.
2. **Three SPIs:** `WorkflowStore`, `WorkflowMessaging`, `DistributedLock`.
   This demo runs Postgres + Kafka + Valkey; the same code runs
   Postgres-only (`sample-postgres-only`) with zero external dependencies.
3. **Postgres is truth, Valkey is optimisation.** Every lock is best-effort.
   If a lock is lost, the unique event index still prevents a duplicate
   *persisted* result — which is why activities must be idempotent, and why we
   say that out loud rather than claiming exactly-once.
4. **Orchestration within a service, choreography between them.** Each service
   owns its own workflows and its own database. What crosses the wire is
   domain events on Kafka.

Point at the running stack as proof of (1): `docker compose -f
demo/docker-compose.yml ps` — seven containers, none of them Maestro.

---

## §D5 — Deep dive: determinism

No live commands in the demo stack (the `DeterminismChecker` demo lives in the
engine's own tests).

**SAY:** *"Code between activity calls re-runs on every recovery. So it must be
deterministic. `Math.random()`, `LocalDateTime.now()`, `UUID.randomUUID()`, or
direct I/O in workflow code will make a replay diverge from the original run.
Maestro gives you `workflow.currentTime()` and `workflow.randomUUID()`, which
record their value as a `SIDE_EFFECT` row the first time and return the
recorded value on every replay."*

Point at the `SIDE_EFFECT` rows in the event log from D2 —
`$maestro:currentTime` — and say: *"That row exists so the second run of this
method sees the same clock the first run saw."*

**If asked "what happens if I get it wrong?"** — the engine has a
`DeterminismChecker` that detects a replay diverging from the recorded history
and fails the run loudly rather than silently corrupting state.

---

## §D6 — Deep dive: multi-node adoption

**Off by default.** Requires a restart with the two-node toggle, so decide
before you start, not mid-demo. It adds ~360 MiB.

**RUN**

```bash
demo/scripts/stop-services.sh
TWO_NODE=1 DEMO_SKIP_BUILD=1 demo/scripts/start-services.sh
```

A fifth JVM, `loan-application-service-b`, starts on **8094** and writes
`demo/targets/loan-application-b.json`, which Prometheus picks up by file
service-discovery within 5 s.

Then:

```bash
demo/scripts/drive-loan.sh crash                       # park a loan; note the id it prints

# confirm which node owns it before you kill anything.
# Grep for the START line, not for the id: the id appears in BOTH logs, because
# the non-owner logs "instance lock is held by another node" on every poll
# (measured: 4 hits in the owner's log, 25 in the peer's).
grep -l "Started workflow 'loan-<id>'" demo/.run/loan-application-service.log demo/.run/loan-application-service-b.log

kill -9 $(cat demo/.run/loan-application-service.pid)  # kill the owner (usually node A)

# node B is on 8094 — node A is gone, so drive the rest through B:
LOAN_URL=http://localhost:8094 demo/scripts/drive-loan.sh finish <id>
```

**The `LOAN_URL=` override is not optional.** `drive-loan.sh` talks to 8091 by
default and 8091 is the process you just killed. Forgetting it produces a
connection-refused, not an adoption failure.

**POINT AT**

- <http://localhost:9090/targets> — a fourth target appears and then goes away
  again, without editing any config.
- The Grafana **Recovery adoptions** panel incrementing.
- `demo/.run/loan-application-service-b.log` — node B logging that it adopted
  the instance.

**TIMING:** adoption waits for the dead owner's lock TTL to lapse — nothing can
release it, so it has to expire. Measured in this demo's own configuration:
**27 s** from the `kill -9` to `Resuming workflow 'loan-<id>'` in node B's log,
and **49 s** for the whole `finish` through node B
(`demo/.evidence/task-4-fix-f2-d6-adoption-latency.log`). The bound is the
30 s instance-lock TTL plus one 5 s recovery poll plus slack, so budget **up to
60 s** and fill it with the D4 architecture points.

Note the same Kafka effect as §2: node B cannot receive the underwriting
decision until the group rebalances away from the SIGKILLed member, so the
`finish` is longer than the adoption it is waiting on.

**Caveat to state:** `GET /underwriting/pending` is a node-local in-memory
view. In two-node mode a loan queued on one node may not appear on the other,
and it is not rebuilt after a restart. The *decision* endpoint is
store-backed and works from either node — only the read-side queue is local.

**AFTER D6:** `demo/scripts/stop-services.sh && DEMO_SKIP_BUILD=1
demo/scripts/start-services.sh` to drop back to one node
(`stop-services.sh` also removes the Prometheus file-SD target).

**FALLBACK:** *"Adoption is bounded by the lock TTL and we are inside it —
this is the one place in the demo where the honest answer is 'wait 30
seconds'."* Do **not** abandon early: 49 s is a normal, successful run here, so
give it **120 s** before you call it. Only then `demo/scripts/reset.sh` and
move on.

---

## §Q — Questions you should not have to improvise

**"Is the tracing across Kafka real, or did you special-case it?"**
It is real, and there is one honest caveat. Maestro's `maestroKafkaTemplate`
suppresses Boot's, so `spring.kafka.template.observation-enabled` is inert for
all users, and `@MaestroSignalListener` does not extract trace context on
domain topics. That is filed as **Issue 23 in `docs/open-issues.md`**. The
samples carry an explicit override — that is why you will see
`-Dspring.kafka.template.observation-enabled=true` on the JVM command line. It
is a library defect with a documented workaround, not a demo trick.

**"Exactly-once?"** No. At-least-once execution with exactly-once *persisted
results*. The unique index on `(workflow_instance_id, sequence_number)`
guarantees one stored result per step; it cannot guarantee your payment
provider was called once. Activities must be idempotent, and Maestro says so
in its own docs.

**"How many workflows per node?"** Parked workflows are rows, not threads.
Running ones are virtual threads. The demo box runs everything in `-Xmx256m`
per service.

**"Can I run this without Kafka?"** Yes — `sample-postgres-only` uses
Postgres for store, messaging and locking. Zero external dependencies.

---

## §T — Teardown

```bash
demo/scripts/stop-services.sh                          # four host JVMs
docker compose -f demo/docker-compose.yml down         # containers, keep the volume
```

To wipe the databases as well (next preflight will re-create them, and
`docker/init-demo-dbs.sh` only runs on a fresh volume — so this is the
*correct* way to get a truly clean start):

```bash
docker compose -f demo/docker-compose.yml down -v
```

Verify nothing is left holding a port:

```bash
lsof -nP -iTCP:3000,4318,5433,6380,8080,8091,8092,8093,9090,16686,29093 -sTCP:LISTEN
```

Empty output means you are done.
