# Task 3 report — the live v1 → v2 versioned redeploy

Branch `worktree-demo`. The move was performed against the running demo stack
(6 demo containers + 4 host JVMs) and is reproducible via
`demo/scripts/v1-to-v2-move.sh`. Evidence:

| file | what it is |
|---|---|
| `demo/.evidence/task-3-live-v1-to-v2-move.log` | the clean end-to-end run — all six pins |
| `demo/.evidence/task-3-jaeger-v1-vs-v2-traces.log` | full span dumps of the three traces |
| `demo/.evidence/task-3-live-move-run1-aborted-underwriting-autoapprove.log` | first run, aborted on a driver bug, with post-mortem |
| `demo/.run/loan-application-service-v2.log` | the v2 JVM's live log (engine at DEBUG). **Untracked** — `demo/.gitignore` excludes `.run/`; the lines that matter are grepped into the move log at PIN 5 |

---

## 1. THE BOUNDS CORRECTION — read this before writing the runbook or the slides

**The task brief's literal `workflow.version(PARALLEL_VERIFICATION, 1, 2)` is
wrong and must never appear in Task 4's runbook or Task 5's slides.** The
shipped call is:

```java
int v = workflow.version(PARALLEL_VERIFICATION, WorkflowContext.DEFAULT_VERSION,
        PARALLEL_VERIFICATION_VERSION);   // minSupported = DEFAULT_VERSION (-1), max = 2
...
if (v < PARALLEL_VERIFICATION_VERSION) { /* v1: sequential */ } else { /* v2: parallel */ }
```

Why: a history that predates the change carries no `VERSION_MARKER`, so
`version()` resolves it to `WorkflowContext.DEFAULT_VERSION`, which is `-1`.
With `minSupported = 1`, every loan in flight at the moment of the deploy falls
below the floor and throws `UnsupportedWorkflowVersionException` — failing the
loan and running its saga compensations. That is the precise opposite of what
the demo promises. RED-proven before the fix in
`demo/.evidence/task-3-red-2-brief-literal-bounds-fail-inflight.log`.

The floor may be raised to `2` (and `awaitAllVerifications()`'s sequential call
site deleted) only once no pre-change loan can still be running.

**Second brief correction.** The brief describes the v2 change as "three
verification branches in parallel". It is not, and cannot be on this engine.
The fan-out is **two** `workflow.parallel()` branches — *verification fan-in*
∥ *document collection* — because concurrent branches must await **different
signal names**: `ParkingLot.register` keys on `workflowId + ":signal:" +
signalName` and throws `"Parking key … already occupied"` on a second park at
the same key, and a pre-arrived signal would be handed to all three branches
identically. Both failure modes are pinned by `LoanApplicationWorkflowV2Test`.
The *three verification requests* do fan out three ways — but they do so in v1
too, so they are not what changed. Slides must say **"documents collect while
verifications are outstanding"**, not "three parallel verifications".

---

## 2. The four pins

### Pin A — the two jars are genuinely different code at the same class name

```
v1 jar sha256: 0023c6f46fd8dc368c34a872bd2a67eb8c0e1b31cc32826d5402aa914b423b02
v2 jar sha256: 6672cd95b3b54a4f7a9dfd0ed3b5a5ec1e5bfe7b06fa7faa3b2ff5a609b4f885
BOOT-INF/classes/…/workflow/LoanApplicationWorkflow.class
  loan-application-service-0.3.0-SNAPSHOT.jar: 70117aba5ac2343a8ccc3ab4777368d2bd6c8c547313953259f3ee00d0edb513
  loan-application-v2.jar:                     c8caa962d45ec22797a5ce00e957f09de2a8f938b7965c0fd020f927db6a09fb
occurrences of the change id 'parallel-verification' in v2's packaged class: 1
occurrences of the change id 'parallel-verification' in v1's packaged class: 0
```

### Pin B — the swap was a real rolling deploy, and the in-flight loan survived it

`loan-inflight-1785841603` was started under v1 and had consumed its first
verification result when the v1 JVM was stopped:

```
1 | ACTIVITY_COMPLETED | LoanActivities.recordApplication              | 11:06:44.150
2 | ACTIVITY_COMPLETED | LoanMessagingActivities.requestVerifications  | 11:06:44.205
3 | SIGNAL_RECEIVED    | $maestro:awaitSignal:verification.result      | 11:06:46.318
VERSION_MARKER rows in its history: 0
```

```
pid 4679 exited on SIGTERM (a real rolling deploy, not kill -9)
in-flight loan survived the shutdown as: WAITING_SIGNAL
started loan-application-v2.jar as pid 4803
healthy on 8091
```

Graceful SIGTERM, not `kill -9`: a parked workflow must come through a routine
deploy as `WAITING_SIGNAL`, never `FAILED`.

### Pin C — the in-flight loan completed on the SEQUENTIAL path

Not "it completed" — the event log says so. Its full history is 22 events,
sequence 1..22, contiguous, and:

```
VERSION_MARKER rows: 0   (must be 0 — history predates the change)
events in parallel branch bands (sequence >= 1000): 0   (must be 0 — sequential path)
output: {"status": "FUNDED", …, "applicationId": "inflight-1785841603", …}
```

The ordering in that log is the v1 shape: all three
`awaitSignal:verification.result` events (seq 3, 4, 5) complete **before** the
first `awaitSignal:document.uploaded` (seq 8). Under v2's code path those two
would have been concurrent and in separate sequence bands. `version()` peeked
sequence slot 1, found `ACTIVITY_COMPLETED / recordApplication` rather than a
marker, resolved to `DEFAULT_VERSION` *without consuming the slot* (the old log
is unshifted — sequence 1 is still `recordApplication`), and took the old
branch.

### Pin D — a new loan under v2 fans out

`loan-newloan-1785841603`, created after the swap:

```
1    | VERSION_MARKER     | $maestro:version:parallel-verification    | 11:07:00.585
       payload: {"version": 2, "changeId": "parallel-verification"}
2    | ACTIVITY_COMPLETED | LoanActivities.recordApplication          | 11:07:00.589
3    | ACTIVITY_COMPLETED | LoanMessagingActivities.requestVerifications | 11:07:00.619
4    | SIDE_EFFECT        | $maestro:parallel                        | 11:07:00.628
5001 | SIGNAL_RECEIVED    | $maestro:awaitSignal:verification.result | 11:07:06.346
5002 | SIGNAL_RECEIVED    | $maestro:awaitSignal:verification.result | 11:07:06.355
5003 | SIGNAL_RECEIVED    | $maestro:awaitSignal:verification.result | 11:07:11.349
6001 | SIDE_EFFECT        | $maestro:currentTime                     | 11:07:00.635
6002 | SIDE_EFFECT        | $maestro:currentTime                     | 11:07:00.641
6003 | SIGNAL_RECEIVED    | $maestro:awaitSignal:document.uploaded   | 11:07:04.137
6004 | SIDE_EFFECT        | $maestro:currentTime                     | 11:07:04.144
6005 | SIGNAL_RECEIVED    | $maestro:awaitSignal:document.uploaded   | 11:07:04.675
7001 | SIGNAL_TIMEOUT     | $maestro:awaitSignal:application.withdrawn | 11:07:12.362
…
7012 | WORKFLOW_COMPLETED |                                          | 11:07:14.741
```

The marker is at sequence 1 — the gate resolves before the first step, so it
pins *every* already-started loan, not merely those that had got as far as
verification. The band bases confirm the documented partitioning exactly: the
fork is at parent sequence `p = 4`, so branch 0 allocates from
`4*1000 + 1*1000 = 5000` and branch 1 from `4*1000 + 2*1000 = 6000`; the parent
resumes above both, at 7001. Total events in branch bands: 20.

---

## 3. The two Jaeger trace IDs — the money slide

```
new loan  (v2, fans out)   5c79c33935721eb3afe5f9ae8ca742f5   28 spans
in-flight (v1, sequential) b39b554f4c94c659f68468588295431c   20 spans
                           ec798a7ed25b754fae81371d77cdbcc1   13 spans
```

(The move log's PIN 6 reports the new loan's trace as 11 spans; that query ran
seconds after the loan completed, before the OTLP batch exporter had flushed
the rest. The counts above are the settled ones, re-queried in
`task-3-jaeger-v1-vs-v2-traces.log`. Worth knowing before a live demo: give
Jaeger ~30s before opening the trace, or the fan-out looks half-missing.)

**Use `b39b554f4c94c659f68468588295431c` next to
`5c79c33935721eb3afe5f9ae8ca742f5`.** The contrast is in the shape of the
`maestro.workflow.run` segments, and it is stark (offsets from trace start,
`maestro.admin.events` spans elided):

*v1 in-flight — a staircase of short segments, documents strictly after
verifications:*

```
    0ms  dur=11ms  maestro.workflow.run  verification.result
  427ms  dur=13ms  maestro.workflow.run  verification.result
 1125ms  dur=9ms   maestro.workflow.run  document.uploaded
 1144ms  dur=6ms   maestro.workflow.run  document.uploaded
 2172ms  dur=38ms  maestro.workflow.run
```

*v2 new loan — ONE 10.8-second run segment spanning the whole parallel phase,
with the three verification requests fanning out beneath it:*

```
    0ms  dur=10770ms  maestro.workflow.run
    0ms  dur=2ms      maestro.activity  LoanActivities.recordApplication
    5ms  dur=29ms     maestro.activity  LoanMessagingActivities.requestVerifications
    6ms  dur=6ms      loans.verification.requests send
   13ms  dur=7ms      loans.verification.requests process   (verification-gateway)
   13ms  dur=8ms      loans.verification.requests send
   22ms  dur=7ms      loans.verification.requests send
   23ms  dur=9ms      loans.verification.requests process   (verification-gateway)
   36ms  dur=4ms      loans.verification.requests process   (verification-gateway)
11781ms  dur=22ms     maestro.workflow.run                  ← parallel() has joined
```

Under v1 the workflow stands down at every await, so each resumption is its own
short segment and the four of them are strictly ordered. Under v2 the run never
stands down through the parallel phase: one wide bar, the three verification
workflows underneath it, and the two document uploads landing inside it (event
log 6003 at +3.5s, 6005 at +4.0s).

**Caveat to state honestly on the slide:** the in-flight loan is *two* traces,
not one — `ec798a7e…` is its pre-swap life under v1 (13 spans, including its
own three-way verification request fan-out) and `b39b554f…` is its post-swap
resumption. The JVM restart breaks the trace; the durable workflow is not
broken, and the `maestro.workflow.id` tag ties both traces to the same loan.
Don't let an audience read the split as data loss.

---

## 4. Do the three parallel branches genuinely park concurrently?

**Yes — the two branches park concurrently, and it is proven, not assumed.**
(Two branches, not three — see the second brief correction in §1.)

`ParkingLot.unpark` distinguishes the two cases in its DEBUG log: it logs
`"Unparked workflow at key '<k>'"` only when a waiter was *actually registered*
at that key, and `"No parked workflow at key '<k>' — stored wake permit"` when
there was none. So a successful unpark timestamps a live park. From
`demo/.run/loan-application-service-v2.log`:

```
13:07:04.132  Unparked workflow at key 'loan-newloan-1785841603:signal:document.uploaded'
13:07:04.668  Unparked workflow at key 'loan-newloan-1785841603:signal:document.uploaded'
13:07:06.344  Unparked workflow at key 'loan-newloan-1785841603:signal:verification.result'
13:07:11.345  Unparked workflow at key 'loan-newloan-1785841603:signal:verification.result'
```

The verification branch registered its waiter when the fork ran (11:07:00.63
local / 13:07:00.6 in the JVM's +02:00 log) and its waiter was still there to
be woken at 13:07:04's successor, 13:07:06.344 — it had consumed nothing before
then (first `verification.result` event is 5001 at 11:07:06.346). The document
branch's waiter was demonstrably alive and woken at 13:07:04.132 and again at
13:07:04.668. **Both keys therefore held live parked waiters at 13:07:04.132.**

The durable event log corroborates it independently of any log line: branch
1's events 6003 (11:07:04.137) and 6005 (11:07:04.675) are recorded *between*
the fork (seq 4, 11:07:00.628) and branch 0's first event (5001,
11:07:06.346). Branch 1 made durable progress while branch 0 had made none
since the fork.

This is the exact shape of the `InstanceStatusWriter` optimistic-lock defect
fixed earlier this month — two branches racing to write the instance's
`WAITING_SIGNAL` status. Under the real stack it is clean:

```
InstanceStatusWriter version-conflict lines: 0
status-write give-ups ("Could not write status"): 0
ParkingLot "already occupied" errors: 0
```

Worth recording that the count is **0**, not merely "retried successfully": at
this concurrency the two branches' status writes did not even collide. The
guard is there for when they do; it was not exercised here.

---

## 5. Incidental findings (for Task 4's runbook)

1. **`UnderwritingWorkflow` auto-approves these loan parameters.** 250k against
   90k income is a clear-cut `AUTO_APPROVE` on the DTI rules, so no decision
   ever reaches `GET /underwriting/pending`. The first run of the driver
   aborted after 120s polling a queue nothing would ever enter
   (`task-3-live-move-run1-aborted-underwriting-autoapprove.log`). The runbook
   must not tell a presenter to "approve the loan in the underwriting queue"
   for these amounts. Driver now waits for the automatic decision and only
   falls back to a manual POST.
2. **Re-running the demo:** `RESTORE_V1=1 demo/scripts/v1-to-v2-move.sh` puts
   the v1 jar back on 8091 without disturbing the other three JVMs.
3. **The v2 JVM keeps the same pid file** (`demo/.run/loan-application-service.pid`)
   and logs to `demo/.run/loan-application-service-v2.log`, so
   `demo/scripts/stop-services.sh` still stops everything and the v1 log
   survives for comparison.
4. **Engine DEBUG is required for the parking evidence.** The v2 JVM is started
   with `LOGGING_LEVEL_IO_B2MASH_MAESTRO_CORE_ENGINE=DEBUG`; at INFO the
   `ParkingLot` lines that carry the concurrency proof do not exist.

## 6. Current stack state

The v2 jar (pid 4803) is running on 8091; verification-gateway, underwriting
and maestro-admin are untouched from Task 2's run. Both demo loans are
`COMPLETED`/`FUNDED`.
