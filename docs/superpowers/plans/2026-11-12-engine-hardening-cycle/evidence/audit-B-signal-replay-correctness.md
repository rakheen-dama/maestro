# Audit B: signal, replay and retry correctness (handover §3.2, 3.3, 3.7, 3.8, 3.10)

Re-verified against `main` @ `cbf5a2a` (worktree `agent-aa3bbb625c6ede6fe`), 2026-11-12.
Line numbers are for that commit.

What each label means:
- **RAN**: a throwaway test was run in this worktree. The test source and its actual output are in the appendix.
- **TRACED**: I read the code path end to end but did not execute it.
- **INFERRED**: a reasoned consequence that was not executed.

Throwaway tests added (not committed):
- `maestro-core/src/test/java/io/b2mash/maestro/core/engine/AuditB32WakePermitLeakTest.java`
- `maestro-core/src/test/java/io/b2mash/maestro/core/engine/AuditB33RetryCaughtFailureTest.java`
- `maestro-test/src/test/java/io/b2mash/maestro/test/AuditB38FingerprintProbeTest.java`
- `maestro-test/src/test/java/io/b2mash/maestro/test/AuditB38ReplayFingerprintTest.java`

All four fail against current code. Each one asserts the correct behaviour, so each will turn green when its finding is fixed.

| # | Verdict | Fix in a phrase | Size |
|---|---|---|---|
| 3.2 | **REPRODUCED**, and wider than the handover says (timer fires leak too) | store a permit only for a workflow that is running on this node | S |
| 3.3 | **REPRODUCED** | delete only the failure memos that ended the run (nothing recorded after them in their lane); share the attribution with Issue 33 | M (minimal version: S) |
| 3.7 | **CONFIRMED** (traced) | poll due timers on the database clock, and warn on JVM/DB clock skew | S |
| 3.8 | **REPRODUCED**, and wider than suspected | the live path returns the same round-tripped value that replay returns; the JDBC store parses decimals exactly | S (+S/M for decimals) |
| 3.10 | **CONFIRMED** (traced) | remove the activity lock | S |

---

## 3.2 Wake-permit leak: REPRODUCED (and wider than reported)

### (a) Evidence
- `SignalManager.deliverSignal` always calls `parkingLot.unpark(parkKey, signalPayload)` after persisting (`SignalManager.java:512-514`). It does not check whether this node runs the workflow.
- When no local future exists, `ParkingLot.unpark` stores `pendingWakes.put(key, payload)` (`ParkingLot.java:246-266`). The stored value is the **serialized signal payload** (`JsonNode`). This is not a marker, so the whole payload stays on the heap.
- Only four calls remove an entry:
  - a later `park` on the same key on the same node (`:110`, `:138`);
  - `abandonWorkflow`, which `terminateWorkflow` calls only when the run is local (`WorkflowExecutor.java:1297-1301`);
  - `clearPending` at local run end (`WorkflowExecutor.java:2567`);
  - `shutdown()`.

  A node that never runs the workflow never reaches any of them.
- **Second source, missed by the handover.** The timer poller leader leaks too. `WorkflowExecutor.fireTimer` → `wakeTimerWaiter` → `parkingLot.unpark(workflowId + ":timer:" + timerId, null)` (`WorkflowExecutor.java:1067-1076`, `1144-1150`) runs for every due `sleep-*` row, and runs on the leader whether or not the leader owns the workflow. These keys are unique per sleep (`sleep-<seq>`), so this leak grows with **every sleep fired for a workflow owned elsewhere**, not with `(workflowId, signalName)`. The existing test `AwaitTimerStrayPermitTest.fireSleepTimer_stillUnparksTheTimerKey` asserts this permit (count 1) for a workflow that is not running, so it currently pins the leak as intended behaviour.
- `onRemoteSignal` (`SignalManager.java:1257`) is not a leak source on non-owners. `PostgresWakeListener.dispatch` drops notifications that have no local subscriber (`PostgresWakeListener.java:275-279`).

**RAN.** `AuditB32WakePermitLeakTest` uses two executors on one store. 50 workflows ran and completed on node A while their signals arrived on node B, plus 2 extra deliveries. Results:
- `node A pendingWakes=0 node B pendingWakes=51` (50, plus one for a never-started id; the late duplicate for `leak-0` overwrote its key).
- Timer variant: `node B pendingWakes=50` after the leader fired 50 sleep rows owned elsewhere.

### (b) Harm
- **Kafka (traced):** a signal is consumed on whichever node owns the partition, so with N replicas about (N−1)/N of signals land on a non-owner. Each one leaves `key + latest payload` on that node until the JVM restarts.
- **Timers:** the poller leader keeps one entry for every fired sleep of every workflow owned elsewhere.
- This is unbounded heap growth proportional to throughput and payload size. A long-lived pod that never restarts eventually OOMs (INFERRED).
- No correctness harm. A stale permit only causes a spurious wake, and every await and sleep re-reads the store after a wake (`SignalManager.awaitOutcome` step 1, `DefaultWorkflowOperations.parkForTimer`).

### (c) Fix design
The permit exists only to close the register-vs-wake race on the node that will park. So store it only on that node:
- In `WorkflowExecutor`, gate both wake sources on `runningWorkflows.containsKey(workflowId)`: the executor's `deliverSignal` path, via a predicate handed to `SignalManager`, and `wakeTimerWaiter`.
- Alternatively, give `ParkingLot` an `isLocal` predicate and skip the `pendingWakes.put` when it is false. Completing an existing future stays unconditional.

**Correctness:**
- `runningWorkflows` is populated before the run's thread starts.
- Every await re-reads the store after registering (`awaitOutcome` registers `awaitWakeKeys` and then reads the signals at the top of each pass). Every sleep re-reads its row each interval.
- So a delivery that misses the gate is always seen by the store read. Postgres stays the source of truth, and the permit stays an optimisation, as CLAUDE.md requires.

**Residual race:** a delivery checks `isLocal == true`, then the run ends (`remove` → `clearPending`), then the permit is put. That leaks one entry. Close it like the existing reverse race: after `put`, re-check `isLocal` and remove the entry if it is now false.

**Other properties:**
- No SPI, schema or config change.
- No determinism, fencing or control-flow `Error` impact, because nothing new is thrown or written.
- Mixed-version fleets are unaffected, since this is purely in-memory.

**Tests:**
- The two repro tests above, turned green.
- Rewrite `AwaitTimerStrayPermitTest.fireSleepTimer_stillUnparksTheTimerKey` to use a workflow that is running locally.
- A race pin: on the owning node, a signal delivered while the run is `RUNNING` and has not yet parked must still wake it immediately. Assert that it wakes in well under `wakeRecheckInterval`, with the interval set to 30 s.
- A pin that the permit is not left behind when the run ends concurrently.

### (d) Size
S. No overlaps with the other findings.

---

## 3.3 Admin retry deletes caught `ACTIVITY_FAILED` memos: REPRODUCED

### (a) Evidence
- `WorkflowExecutor.retryWorkflow` calls `store.deleteFailureEvents` (`WorkflowExecutor.java:1506`).
- The SPI requires that it delete "exactly the events whose type is `ACTIVITY_FAILED` or `WORKFLOW_FAILED`" (`WorkflowStore.java:380-463`).
- The JDBC store does exactly that by type (`AbstractJdbcWorkflowStore.java:910-918`), and so does `InMemoryWorkflowStore.deleteFailureEvents` (`InMemoryWorkflowStore.java:401+`).

**RAN.** `AuditB33RetryCaughtFailureTest` runs `try { primary() } catch { fallback() }; finish(q)`, where primary and finish both fail during an outage. The outage is then fixed and Retry pressed. Output:
```
history before retry:  1 ACTIVITY_FAILED Acts.primary | 2 ACTIVITY_COMPLETED Acts.fallback | 3 ACTIVITY_FAILED Acts.finish | 4 WORKFLOW_FAILED
history after retry + one recovery pass:  2 ACTIVITY_COMPLETED Acts.fallback | 1 ACTIVITY_COMPLETED Acts.primary
status=RUNNING standDowns=[NONDETERMINISTIC_HISTORY seq=2 expected=Acts.finish stored=Acts.fallback, (same again on the recovery pass)]
primaryCalls=2 fallbackCalls=1 finishCalls=1
```

### (b) Harm (RAN, plus INFERRED where noted)
- The retried run executes `primary` live, which is a real side effect the original run recorded as failed, on top of the fallback's side effect.
- It then stands down on every recovery pass. The instance is `RUNNING`, so a second Retry returns `NOT_FAILED`. The history now holds both a primary success and a fallback success at consecutive slots.
- Terminate is the only exit. The workflow can never complete (INFERRED from `retryWorkflow:1479`).
- The trigger is the most ordinary resilience pattern (try primary, catch, fall back) followed by any later uncaught failure.

### (c) Fix design
**Invariant.** Deleting failure memo `s` is safe only if nothing recorded after `s` was derived from that failure:
- **(i) Lane check.** No memo follows `s` in its own sequence lane, so `s+1` is empty or holds `WORKFLOW_FAILED`. Lanes are contiguous: main line `1,2,3…`, branch `p*1000+(i+1)*1000+k`.
- **(ii) Propagation check.** The failure is the one that propagated and ended the run, not one that was caught. This rules out a branch whose *last* op caught a failure: the branch's lane looks empty after it, but the main line consumed its fallback value.

**Minimal fix (S, store-only, no payload change).** Implement only (i):
- JDBC: `DELETE … WHERE event_type='ACTIVITY_FAILED' AND NOT EXISTS (SELECT 1 FROM maestro_workflow_event n WHERE n.workflow_instance_id = e.workflow_instance_id AND n.sequence_number = e.sequence_number + 1 AND n.event_type <> 'WORKFLOW_FAILED')`, plus the existing `WORKFLOW_FAILED` delete.
- Apply the same rule in `InMemoryWorkflowStore` and the core test `VersionedInMemoryStore`, and rewrite the SPI Javadoc.
- This fixes the reproduced case and is never worse than today. The one case it gets wrong (a caught last op in a branch) is already wrong today.
- One honest regression: if a workflow catches, runs another activity (an alert), then rethrows, the attributed memo is followed by the alert. It is kept, and Retry fails again at once with the same error. That is visible and re-retryable, instead of a permanent stand-down.

**Full fix (M, recommended, shared with Issue 33, whose own proposed fix is the same mechanism).**
- `ActivityExecutionException` and `SignalTimeoutException` carry their sequence. This is additive: a new field and constructor, set in `ActivityInvocationHandler` live and on `reconstructFailure`.
- `failAndCompensate` walks the cause chain and the suppressed exceptions of the escaping throwable and records `failedSequences: [...]` in the `WORKFLOW_FAILED` payload.
- `deleteFailureEvents` deletes a failure memo only if it is in `failedSequences` **and** passes the lane check. If the field is absent (legacy rows, or a workflow that threw a new exception without a cause), it falls back to the lane check alone.
- The same field replaces Issue 33's "highest-sequenced `SIGNAL_TIMEOUT`" heuristic.

**Risks:**
- **Mixed-version fleets.** An old node performing a retry ignores the field and keeps today's delete-all behaviour, which is no worse. Old nodes reading a `WORKFLOW_FAILED` payload with an extra field are fine: Jackson 3's default does not fail on unknown properties, which the 3.8 probe's Sub→Base case confirms (RAN).
- **Control flow.** `failAndCompensate` never sees a control-flow `Error` or a latched `StoreUnavailableException`; those take the abandon paths. The walk must still skip them defensively.
- **No schema change.**
- **Determinism improves:** the retry replays the recorded fallback path.

**Tests:**
- The repro turned green: completes, `primaryCalls == 1`, no stand-down.
- A parallel-branch shape where a branch's last op caught a failure and the main line failed later. The full fix keeps the branch memo.
- Rethrow-after-alert: retry fails again with the same error and does not stand down.
- A legacy `WORKFLOW_FAILED` with no field, using the lane-check fallback.
- A JDBC IT in `maestro-store-postgres` pinning the SQL.
- Issue 33's two shapes.
- A second-retry pin.

### (d) Size
M for the full fix, S for the minimal one.
- **Overlaps with Issue 33:** same code path and the same payload field, so fix them together.
- **Overlaps with 3.8:** same end state ("stands down forever, only terminate exits"). Both argue for an operator escape hatch, such as a reset to a sequence, which is out of scope here.

---

## 3.7 Timers use JVM clocks: CONFIRMED (TRACED, not run)

### (a) Evidence
- `sleep()` arms `fire_at = instantSource.instant().plus(duration)` on the scheduling node's clock (`DefaultWorkflowOperations.java:414`, which is `InstantSource.system()` in production).
- `awaitSignal` does the same for its deadline (`SignalManager.java:712`).
- `TimerPoller` polls `store.getDueTimers(Instant.now(), batchSize)` with the leader's JVM clock (`TimerPoller.java:186`). The Postgres SQL compares `fire_at <= ?` (`PostgresWorkflowStore.java:67-74`).
- The owner's `awaitOutcome` also self-fires on its own clock (`SignalManager.java:926`).
- Locks use SQL `now()` (`PostgresDistributedLock.java:72-90`), so the claim holds as stated.

### (b) Harm
- A `sleep` wakes early or late by the skew between the poller leader and the scheduling node, and that shift changes whenever leadership moves.
- An await times out at the earlier of two clocks: the owner's self-fire, or the leader's fire. After adoption, the owner's self-fire compares a new node's clock with the old node's memoized deadline.
- Under NTP (milliseconds of skew) this is negligible next to the poll interval. With a drifting VM clock (seconds to minutes) it shifts every timer.
- It never breaks determinism, because outcomes are memoized. Severity is low.

### (c) Fix design (S)
1. Add a `WorkflowStore.getDueTimers(int batchSize)` default method that delegates to `getDueTimers(Instant.now(), batchSize)`. Postgres overrides it with `fire_at <= now()`, and `TimerPoller` calls it. Keep the `Instant` overload, because the test kit's `advanceTime` drives it (`TestWorkflowEnvironment.java:680`).
2. Add a startup and periodic skew probe (`SELECT now()` against `Instant.now()`) that logs a WARN above about 1 s and exposes a gauge. This catches the real-world cause cheaply.

Arming `fire_at` from DB time is possible but M-sized: the deadline is memoized in `AwaitSignalDetail` and compared on the owner's clock for self-fire. I don't recommend it now.

**Risks:**
- An additive SPI default method. Third-party stores keep JVM-time behaviour.
- No schema change.
- Mixed-version fleets are harmless: only the leader polls, and either clock is "a clock".

**Tests:**
- A Postgres IT: insert a row with `fire_at = now() + 2 s` and poll through a store whose JVM clock is skewed via an `InstantSource`-injected poller. The row must not be returned early.
- A unit test for the skew-probe threshold.

### (d) Size
S. No overlaps (it is the S1.4 scenario).

---

## 3.8 Live result returned raw, replayed result round-tripped: REPRODUCED

### (a) Evidence
- The live path persists `serializer.serialize(result)` but returns the raw `result` (`ActivityInvocationHandler.java:709-725`).
- Replay returns `serializer.deserialize(payload, method.getGenericReturnType())` (`:542-544`, `:563-568`).
- A later call fingerprints its arguments with `ArgumentFingerprint.of(serializer, args)` (`:470`). On replay it compares them with `ReplayGuard.requireArgumentsMatch` (`:491`).
- The canonical form sorts object keys, strips trailing zeros and keeps **array order** (`ArgumentFingerprint.java:106-185`).
- The JDBC store reads payloads with the host mapper's plain `readTree` (`AbstractJdbcWorkflowStore.java:1679-1685`). That mapper is the host's, injected via `PostgresStoreAutoConfiguration:144` / `MaestroAutoConfiguration:74`, and it parses floats as `double`.

**RAN: probe** (`AuditB38FingerprintProbeTest`, default `JsonMapper`, Jackson 3.0.1). "Live" is the fingerprint of the raw value. "in-memory" is the replayed value from the same `JsonNode`. "jdbc-text" goes through `writeValueAsString`/`readTree`, which simulates the JDBC store; this is not run against Postgres.

| Result type (realistic example) | in-memory | jdbc-text | why |
|---|---|---|---|
| record with `Set<String>` built from `TreeSet` or `Set.of` | **DIFFERS** | **DIFFERS** | deserialized as `HashSet`, so the array order changes |
| `OffsetDateTime` with offset `+02:00` | **DIFFERS** | **DIFFERS** | Jackson normalizes to UTC: `10:00+02:00` becomes `08:00Z` |
| `ZonedDateTime Europe/Berlin` | **DIFFERS** | **DIFFERS** | same, the zone is lost |
| declared supertype, live subtype with an extra field | **DIFFERS** | **DIFFERS** | the extra field is dropped on replay |
| `BigDecimal` with ≥17 significant digits (`1.123456789012345678`) | same | **DIFFERS** | `readTree` gives a `DoubleNode`, so the replayed value is `1.1234567890123457` |
| `Float` inside `Map<String,Object>` / `List<Object>` | same | **DIFFERS** | the live `FloatNode` canonicalizes as `0.10000000149011612`, the replay as `0.1` |
| `BigDecimal 10.50`, `Instant` with nanos, `OffsetDateTime` UTC, `LinkedHashSet`, `Long` in `Map<String,Object>` (5 and 2^60) | same | same | |

With `USE_BIG_DECIMAL_FOR_FLOATS` enabled, the BigDecimal rows become "same". Everything else is unchanged.

**RAN: end to end** on the shipped test kit (`AuditB38ReplayFingerprintTest`). The workflow is `reserveSlot()` → `confirm(slot)` → `finish(...)`, then crash after 2 activities and recover:
```
OffsetDateTime: status=RUNNING standDowns=[NONDETERMINISTIC_HISTORY seq=2 step=Booking.confirm argsHash expected=3d04a79d stored=e2e5ec50]
TreeSet:        status=RUNNING standDowns=[NONDETERMINISTIC_HISTORY seq=2 step=Booking.pick    argsHash expected=77ec0bde stored=f5a02cf2]
```

### (b) Harm
- An unchanged, deterministic workflow stands down after any recovery. That includes every rolling deploy for a parked run, because parked runs are abandoned on shutdown and replayed on adoption.
- It stands down again on every pass, and terminate is the only exit.
- Beyond the fingerprint, the replayed **value** differs from the live one. A different offset, a different `Set` iteration order, a lost subtype field, or a truncated decimal can silently diverge workflow logic (for example `slot.start().getHour()` or `skus.iterator().next()`), even with `maestro.replay.verify-arguments=false` (INFERRED).
- The BigDecimal precision loss on Postgres is silent **data corruption** of replayed results. That is a sub-finding the handover did not have (simulated, not run against Postgres; `jsonb` numerics are arbitrary-precision, so the loss is in `readTree`).
- `docs/concepts.md:204` documents "make result types round-trip cleanly" as an author obligation. `docs/pitfalls.md` does not list it. The in-memory kit cannot reveal the decimal variant (`docs/testing.md:432`).

### (c) Fix design
**(S) Engine.**
- In `executeLiveHoldingSlot`, compute `payload = serializer.serialize(result)`, then `replayed = deserializeResult(payload, method)`.
- Append `payload`, then return `replayed` instead of `result`.
- To also match the JDBC text path, normalise first: `payload = serializer.normalize(serializer.serialize(result))`, where `normalize` is `readTree(writeValueAsString(node))` with the same reader the store uses.
- Do the deserialize **before** the append. A result that cannot be read back then fails the live run as an ordinary serialization failure, which matches the existing rule that live-path serialization failures are workflow failures. Today that case appears only later, as an `UNKNOWN_EVENT_PAYLOAD` stand-down that is misattributed to "a newer node".

**(S/M, separate decision) Decimal precision.**
- Have the JDBC store parse payloads with a store-private reader that has `USE_BIG_DECIMAL_FOR_FLOATS` enabled, and use the same reader in `normalize`.
- Risk: untyped targets (`Object`, `Map<String,Object>`) get `BigDecimal` where they got `Double` on the JDBC path. Fingerprints are value-based, so hashes agree, but author casts such as `(Double)` would break. This needs a release note.

**Other properties:**
- Determinism improves, because the live value equals the replay value.
- Mixed-version fleets: new nodes record hashes of round-tripped values, and old nodes replaying round-trip too, so they match. Histories recorded before the fix still carry hashes of raw values and will still stand down. The fix cannot heal them. `verify-arguments=false` is the documented escape hatch, and the release note should say so.
- No SPI or schema change for the S part.
- No control-flow `Error` or `StoreUnavailableException` path is touched.
- Cost: one `treeToValue` (plus one text round trip if normalizing) per live activity, which is negligible next to the insert.

**Tests:**
- Both repros turned green.
- The probe turned into a parameterized pin over the table above, including the jdbc-text column.
- A Postgres IT for a 19-digit `BigDecimal` that survives replay.
- A pin that an unreadable result type fails live (not later on replay).
- Remove the author caveat from `docs/concepts.md:204` once fixed.

### (d) Size
S (engine), plus S/M for decimals.
- Overlaps with 3.3: same terminal state.
- Overlaps with the V7 args-hash work: this is the gap that work left.

---

## 3.10 The activity lock prevents nothing: CONFIRMED (TRACED); recommendation: REMOVE

### (a) Evidence
- `ActivityInvocationHandler.acquireLock` (`:1013-1033`) logs a WARN when `tryAcquire` returns empty, then returns `null`, and execution proceeds (`:663`). A backend exception is handled the same way.
- `releaseLock` (`:1038-1046`) deletes the row.
- The Postgres lock is an `INSERT … ON CONFLICT … WHERE expires_at < now()` plus a `DELETE` (`PostgresDistributedLock.java:72-90`).
- `docs/configuration.md:1076` still calls it "a best-effort guard against concurrent duplicate execution". It guards nothing.

### (b) Harm
- Two statements per live activity on the host's pool, plus rows for the lock cleaner, plus a misleading WARN.
- It gives zero deduplication.

### (c) Why "make it mean something" cannot work
- Since V6 fencing, the only way two runners reach the same `(workflowId, seq)` is the lost-instance-lock window, and the stale runner's outcome append is then fenced: it can never be persisted.
- So the new owner must re-execute that step whatever the lock says.
- Making the lock blocking (wait, or stand down until the lock expires) only delays the duplicate by up to `startToClose + 10 s`. It cannot prevent the duplicate, because the stale runner's side effect has already started or finished.
- Real dedup needs idempotent activities and idempotency keys, which are already required.

### Removal plan
- Delete `acquireLock`/`releaseLock` and the call at `:663`.
- Keep the `distributedLock` constructor parameters of `ActivityInvocationHandler`/`ActivityProxyFactory` as ignored and `@Deprecated`, or drop them in the Issue 37 builder pass, since nothing is published yet.
- Update `ActivityInvocationHandlerTest` (lock-key assertions around `:479`, `:504`) to a pin that no lock call is made per activity.
- Update the docs: CLAUDE.md lock-key list, `docs/configuration.md:405,1062,1076`, `docs/maestro-architecture.md:211,474`.

**Risks:**
- Mixed-version fleets: old nodes keep taking the activity lock, and new nodes never contend, so correctness is unchanged.
- No schema change, because the table is shared with the instance lock.
- No fencing or control-flow impact.

### (d) Size
S. No overlaps.

---

## Cross-cutting observations
- Both 3.3 and 3.8 put a healthy workflow into a permanent `NONDETERMINISTIC_HISTORY` stand-down loop that only terminate can end. Retry refuses, because the status is `RUNNING`. An operator escape hatch for this state is missing; it is outside this audit's scope.
- The in-memory store keeps `JsonNode`s by reference, so the test kit is structurally blind to text-path differences such as the decimal precision loss in 3.8. A cheap remedy is an opt-in "render payloads to text" mode in `InMemoryWorkflowStore`.

---

## Appendix: repro test sources and output


### `maestro-core/src/test/java/io/b2mash/maestro/core/engine/AuditB32WakePermitLeakTest.java`

```java
package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.model.TimerStatus;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.model.WorkflowTimer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AUDIT-B 3.2 (throwaway repro). Two nodes share one store. The workflow runs
 * on node A; the signal (and the timer fire) arrive on node B, as they do when
 * B owns the Kafka partition or holds timer-poller leadership. Expected after
 * the run finished on A: no wake permit left on B. Actual: one per delivery.
 */
class AuditB32WakePermitLeakTest {

    private static final Duration RECHECK = Duration.ofMillis(100);

    private VersionedInMemoryStore store;
    private WorkflowExecutor nodeA;
    private WorkflowExecutor nodeB;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        var serializer = new PayloadSerializer(JsonMapper.builder().build());
        nodeA = new WorkflowExecutor(store, null, null, null, serializer, "svc",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, Duration.ofSeconds(30), false,
                Duration.ofSeconds(5), RECHECK);
        nodeB = new WorkflowExecutor(store, null, null, null, serializer, "svc",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, Duration.ofSeconds(30), false,
                Duration.ofSeconds(5), RECHECK);
    }

    @AfterEach
    void tearDown() {
        nodeA.shutdown();
        nodeB.shutdown();
    }

    @Test
    void signalDeliveredOnNonOwningNode_leavesPermitAfterRunEnds() throws Exception {
        var method = AwaitOnce.class.getMethod("run");
        for (int i = 0; i < 50; i++) {
            var id = "leak-" + i;
            nodeA.startWorkflow(id, "AwaitOnce", "default", null, AwaitOnce::new, method);
            TestTerminalWait.awaitStatus(store, id, WorkflowStatus.WAITING_SIGNAL, Duration.ofSeconds(5));
            nodeB.deliverSignal(id, "go", "payload-" + i);    // B consumed the Kafka record
            TestTerminalWait.awaitStatus(store, id, WorkflowStatus.COMPLETED, Duration.ofSeconds(5));
        }
        // Late duplicates for finished workflows, and signals for ids no run exists for.
        nodeB.deliverSignal("leak-0", "go", "late-duplicate");
        nodeB.deliverSignal("never-started", "go", "x");

        System.out.println("AUDIT-B 3.2 node A pendingWakes=" + parkingLot(nodeA).pendingWakeCount()
                + " node B pendingWakes=" + parkingLot(nodeB).pendingWakeCount());
        assertEquals(0, parkingLot(nodeB).pendingWakeCount(),
                "every run finished on node A; node B should hold no wake permits");
    }

    @Test
    void sleepTimerFiredByNonOwningLeader_leavesPermitForever() throws Exception {
        for (int i = 0; i < 50; i++) {
            var row = new WorkflowTimer(UUID.randomUUID(), UUID.randomUUID(), "remote-" + i, "sleep-3",
                    Instant.now(), TimerStatus.PENDING, Instant.now());
            store.saveTimer(row);
            nodeB.fireTimer(row.workflowId(), row.timerId(), row.id());   // B is the poller leader
        }
        System.out.println("AUDIT-B 3.2 timer: node B pendingWakes=" + parkingLot(nodeB).pendingWakeCount());
        assertEquals(0, parkingLot(nodeB).pendingWakeCount(),
                "none of these workflows runs on node B");
    }

    private static ParkingLot parkingLot(WorkflowExecutor executor) throws Exception {
        var field = WorkflowExecutor.class.getDeclaredField("parkingLot");
        field.setAccessible(true);
        return (ParkingLot) field.get(executor);
    }

    @DurableWorkflow(name = "AwaitOnce")
    public static class AwaitOnce {
        @WorkflowMethod
        public String run() {
            return WorkflowContext.current().awaitSignal("go", String.class, Duration.ofSeconds(30));
        }
    }
}
```

### `maestro-core/src/test/java/io/b2mash/maestro/core/engine/AuditB33RetryCaughtFailureTest.java`

```java
package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.TestTerminalWait;
import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.RecordingEngineObserver;
import io.b2mash.maestro.core.observe.StandDownReason;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUDIT-B 3.3 (throwaway repro). The workflow catches a primary activity's
 * failure and takes a fallback; a LATER activity fails uncaught. The operator
 * fixes the outage and presses Retry. Expected: the retried run replays the
 * recorded path (primary failed, fallback) and re-executes only the step that
 * failed the workflow, then completes. Actual: retry deletes the CAUGHT
 * primary failure too, primary runs live, succeeds, the code takes the other
 * branch, meets the fallback's memo, and stands down — on every recovery pass.
 */
class AuditB33RetryCaughtFailureTest {

    private VersionedInMemoryStore store;
    private RecordingEngineObserver observer;
    private WorkflowExecutor executor;
    private PayloadSerializer serializer;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        observer = new RecordingEngineObserver();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        executor = new WorkflowExecutor(store, null, null, null, serializer, "svc",
                WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, Duration.ofSeconds(30), false,
                Duration.ofSeconds(5), Duration.ofSeconds(30), observer);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void retryAfterCaughtFailure_completesOnRecordedPath() throws Exception {
        var impl = new ActsImpl();
        var acts = new ActivityProxyFactory().createProxy(Acts.class, impl, store, null, null,
                RetryPolicy.noRetry(), Duration.ofSeconds(5), serializer, new RetryExecutor());
        var method = FallbackWorkflow.class.getMethod("run");
        var registration = new WorkflowRegistration("FallbackWorkflow", "default",
                () -> new FallbackWorkflow(acts), method);

        executor.startWorkflow("fallback-1", "FallbackWorkflow", "default", null,
                () -> new FallbackWorkflow(acts), method);
        TestTerminalWait.awaitStatus(store, "fallback-1", WorkflowStatus.FAILED, Duration.ofSeconds(5));
        var id = store.getInstance("fallback-1").orElseThrow().id();
        System.out.println("AUDIT-B 3.3 history before retry:");
        store.getEvents(id).forEach(e -> System.out.println("  " + e.sequenceNumber() + " "
                + e.eventType() + " " + e.stepName()));

        impl.outageOver.set(true);   // the operator's "dependency is back"
        assertEquals(WorkflowExecutor.RetryOutcome.RETRIED, executor.retryWorkflow("fallback-1", registration));

        await().atMost(Duration.ofSeconds(5)).until(() ->
                store.getInstance("fallback-1").orElseThrow().status() == WorkflowStatus.COMPLETED
                        || !observer.standDowns().isEmpty());
        // A second recovery pass: what every node does every poll interval.
        await().atMost(Duration.ofSeconds(5)).until(() -> !executor.isRunning("fallback-1"));
        executor.recoverWorkflows(Map.of("FallbackWorkflow", registration));
        await().pollDelay(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).until(() -> true);

        System.out.println("AUDIT-B 3.3 history after retry + one recovery pass:");
        store.getEvents(id).forEach(e -> System.out.println("  " + e.sequenceNumber() + " "
                + e.eventType() + " " + e.stepName()));
        System.out.println("AUDIT-B 3.3 status=" + store.getInstance("fallback-1").orElseThrow().status()
                + " standDowns=" + observer.standDowns()
                + " primaryCalls=" + impl.primaryCalls.get() + " fallbackCalls=" + impl.fallbackCalls.get()
                + " finishCalls=" + impl.finishCalls.get());

        assertTrue(observer.standDowns().stream()
                        .noneMatch(s -> s.reason() == StandDownReason.NONDETERMINISTIC_HISTORY),
                "retry must not turn a caught failure into replay drift");
        assertEquals(WorkflowStatus.COMPLETED, store.getInstance("fallback-1").orElseThrow().status());
    }

    @Activity
    public interface Acts {
        String primary();

        String fallback();

        String finish(String quote);
    }

    static final class ActsImpl implements Acts {
        final AtomicBoolean outageOver = new AtomicBoolean();
        final AtomicInteger primaryCalls = new AtomicInteger();
        final AtomicInteger fallbackCalls = new AtomicInteger();
        final AtomicInteger finishCalls = new AtomicInteger();

        @Override
        public String primary() {
            primaryCalls.incrementAndGet();
            if (!outageOver.get()) {
                throw new IllegalStateException("pricing service down");
            }
            return "primary-quote";
        }

        @Override
        public String fallback() {
            fallbackCalls.incrementAndGet();
            return "cached-quote";
        }

        @Override
        public String finish(String quote) {
            finishCalls.incrementAndGet();
            if (!outageOver.get()) {
                throw new IllegalStateException("ledger down");
            }
            return "done:" + quote;
        }
    }

    @DurableWorkflow(name = "FallbackWorkflow")
    public static class FallbackWorkflow {
        private final Acts acts;

        public FallbackWorkflow(Acts acts) {
            this.acts = acts;
        }

        @WorkflowMethod
        public String run() {
            String quote;
            try {
                quote = acts.primary();
            } catch (RuntimeException e) {
                quote = acts.fallback();
            }
            return acts.finish(quote);
        }
    }
}
```

### `maestro-test/src/test/java/io/b2mash/maestro/test/AuditB38FingerprintProbeTest.java`

```java
package io.b2mash.maestro.test;

import io.b2mash.maestro.core.engine.ArgumentFingerprint;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUDIT-B 3.8 (throwaway probe). For each result type: the fingerprint of the
 * LIVE value (what the run that recorded the next activity hashed) versus the
 * fingerprint of the REPLAYED value (what recovery hashes), where "replayed"
 * is deserialize(serialize(live)) exactly as ActivityInvocationHandler does —
 * once with the JsonNode kept in memory (InMemoryWorkflowStore) and once
 * through JSON text (the JDBC store's writeValueAsString / readTree).
 */
class AuditB38FingerprintProbeTest {

    public record Tags(Set<String> tags) { }
    public record Slot(OffsetDateTime at) { }
    public record ZSlot(ZonedDateTime at) { }
    public record Quote(BigDecimal amount) { }
    public record At(Instant at) { }
    public static class Base { public String a = "a"; }
    public static class Sub extends Base { public String b = "b"; }

    /** Generic return types, read the way deserializeResult reads them. */
    public interface Shapes {
        Tags tags();
        Slot slot();
        ZSlot zslot();
        Quote quote();
        At at();
        Base base();
        Map<String, Object> map();
        List<Object> list();
        Set<String> set();
    }

    private static Type type(String method) throws Exception {
        return Shapes.class.getMethod(method).getGenericReturnType();
    }

    @Test
    void probe() throws Exception {
        var failures = new ArrayList<String>();
        for (var mapperName : List.of("default", "bigDecimalForFloats")) {
            ObjectMapper mapper = mapperName.equals("default")
                    ? JsonMapper.builder().build()
                    : JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
            var ser = new PayloadSerializer(mapper);
            var cases = new LinkedHashMap<String, Object[]>();
            cases.put("record Set<String> from Set.of", new Object[]{
                    new Tags(Set.of("delta", "alpha", "charlie", "bravo", "echo")), type("tags")});
            cases.put("record Set<String> from TreeSet", new Object[]{
                    new Tags(new TreeSet<>(List.of("delta", "alpha", "charlie", "bravo", "echo"))), type("tags")});
            cases.put("record Set<String> from LinkedHashSet", new Object[]{
                    new Tags(new LinkedHashSet<>(List.of("zeta", "alpha", "mid"))), type("tags")});
            cases.put("Set<String> (top-level) from TreeSet", new Object[]{
                    new TreeSet<>(List.of("delta", "alpha", "charlie", "bravo", "echo")), type("set")});
            cases.put("OffsetDateTime +02:00", new Object[]{
                    new Slot(OffsetDateTime.parse("2026-11-12T10:00:00+02:00")), type("slot")});
            cases.put("ZonedDateTime Europe/Berlin", new Object[]{
                    new ZSlot(ZonedDateTime.of(2026, 11, 12, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"))), type("zslot")});
            cases.put("OffsetDateTime UTC", new Object[]{
                    new Slot(OffsetDateTime.parse("2026-11-12T10:00:00Z")), type("slot")});
            cases.put("BigDecimal 10.50", new Object[]{new Quote(new BigDecimal("10.50")), type("quote")});
            cases.put("BigDecimal 18dp (1.123456789012345678)", new Object[]{
                    new Quote(new BigDecimal("1.123456789012345678")), type("quote")});
            cases.put("BigDecimal 20 digits (12345678901234567.89)", new Object[]{
                    new Quote(new BigDecimal("12345678901234567.89")), type("quote")});
            cases.put("Instant with nanos", new Object[]{
                    new At(Instant.parse("2026-11-12T10:00:00.123456789Z")), type("at")});
            cases.put("declared Base, live Sub (extra field)", new Object[]{new Sub(), type("base")});
            cases.put("Map<String,Object> Long 5", new Object[]{Map.of("n", 5L), type("map")});
            cases.put("Map<String,Object> Long 2^60", new Object[]{Map.of("n", 1L << 60), type("map")});
            cases.put("Map<String,Object> Float 0.1f", new Object[]{Map.of("f", 0.1f), type("map")});
            cases.put("Map<String,Object> BigDecimal 10.50", new Object[]{Map.of("d", new BigDecimal("10.50")), type("map")});
            cases.put("Map<String,Object> BigDecimal 18dp", new Object[]{
                    Map.of("d", new BigDecimal("1.123456789012345678")), type("map")});
            cases.put("List<Object> Float 0.1f", new Object[]{List.of(0.1f), type("list")});

            for (var entry : cases.entrySet()) {
                var live = entry.getValue()[0];
                var t = (Type) entry.getValue()[1];
                var liveHash = ArgumentFingerprint.of(ser, new Object[]{live});
                String memResult;
                String textResult;
                try {
                    var node = ser.serialize(live);
                    var memReplay = ser.deserialize(node, t);
                    var memHash = ArgumentFingerprint.of(ser, new Object[]{memReplay});
                    memResult = (liveHash != null && liveHash.equals(memHash)) ? "same" : "DIFFERS";
                    var textNode = mapper.readTree(mapper.writeValueAsString(node));
                    var textReplay = ser.deserialize(textNode, t);
                    var textHash = ArgumentFingerprint.of(ser, new Object[]{textReplay});
                    textResult = (liveHash != null && liveHash.equals(textHash)) ? "same" : "DIFFERS";
                    var detail = "live=" + mapper.writeValueAsString(live)
                            + " | mem=" + mapper.writeValueAsString(memReplay)
                            + " | text=" + mapper.writeValueAsString(textReplay);
                    System.out.printf("AUDIT-B 3.8 [%s] %-45s in-memory:%-8s jdbc-text:%-8s %s%n",
                            mapperName, entry.getKey(), memResult, textResult, detail);
                    if (memResult.equals("DIFFERS") || textResult.equals("DIFFERS")) {
                        failures.add(mapperName + ": " + entry.getKey());
                    }
                } catch (RuntimeException e) {
                    System.out.printf("AUDIT-B 3.8 [%s] %-45s threw %s%n", mapperName, entry.getKey(), e);
                }
            }
        }
        assertTrue(failures.isEmpty(), "fingerprint changes across replay: " + failures);
    }
}
```

### `maestro-test/src/test/java/io/b2mash/maestro/test/AuditB38ReplayFingerprintTest.java`

```java
package io.b2mash.maestro.test;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.annotation.ActivityStub;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUDIT-B 3.8 (throwaway repro), end to end on the shipped test kit. Activity
 * one returns a value; activity two takes it as an argument. The node crashes
 * after both completed; recovery replays both. No code changed, no
 * nondeterminism in the workflow — yet replay stands down, because the live
 * run fingerprinted the RAW result and the replay fingerprints the
 * Jackson round-tripped one.
 */
class AuditB38ReplayFingerprintTest {

    private TestWorkflowEnvironment env;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.create();
        env.registerActivities(Booking.class, new BookingImpl());
    }

    @AfterEach
    void tearDown() {
        env.shutdown();
    }

    @Test
    void offsetDateTimeResult_survivesRecovery() throws Exception {
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(SlotWorkflow.class, "order-1");
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();
        Object status;
        try {
            status = handle.awaitTerminal(Duration.ofSeconds(5));
        } catch (java.util.concurrent.TimeoutException e) {
            status = handle.getStatus() + " (not terminal after 5s)";
        }
        System.out.println("AUDIT-B 3.8 OffsetDateTime: status=" + status + " standDowns=" + handle.standDowns());
        assertTrue(handle.standDowns().isEmpty(), "replay of an unchanged workflow must not stand down");
    }

    @Test
    void sortedSetResult_survivesRecovery() throws Exception {
        var crash = env.crashAfter(2);
        var handle = env.startWorkflow(TagsWorkflow.class, "order-2");
        crash.awaitCrash(Duration.ofSeconds(10));
        crash.recover();
        Object status;
        try {
            status = handle.awaitTerminal(Duration.ofSeconds(5));
        } catch (java.util.concurrent.TimeoutException e) {
            status = handle.getStatus() + " (not terminal after 5s)";
        }
        System.out.println("AUDIT-B 3.8 TreeSet: status=" + status + " standDowns=" + handle.standDowns());
        assertTrue(handle.standDowns().isEmpty(), "replay of an unchanged workflow must not stand down");
    }

    public record DeliverySlot(String id, OffsetDateTime start) { }

    public record Allowed(Set<String> skus) { }

    @Activity
    public interface Booking {
        DeliverySlot reserveSlot(String orderId);

        String confirm(DeliverySlot slot);

        Allowed allowedSkus(String orderId);

        String pick(Allowed allowed);

        String finish(String x);
    }

    public static final class BookingImpl implements Booking {
        @Override
        public DeliverySlot reserveSlot(String orderId) {
            // e.g. a courier API answering in the warehouse's local offset
            return new DeliverySlot("slot-7", OffsetDateTime.parse("2026-11-12T10:00:00+02:00"));
        }

        @Override
        public String confirm(DeliverySlot slot) {
            return "confirmed " + slot.id();
        }

        @Override
        public Allowed allowedSkus(String orderId) {
            return new Allowed(new TreeSet<>(List.of("sku-delta", "sku-alpha", "sku-charlie", "sku-bravo")));
        }

        @Override
        public String pick(Allowed allowed) {
            return "picked " + allowed.skus().size();
        }

        @Override
        public String finish(String x) {
            return x + " done";
        }
    }

    @DurableWorkflow(name = "slot-workflow")
    public static class SlotWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Booking booking;

        @WorkflowMethod
        public String run(String orderId) {
            var slot = booking.reserveSlot(orderId);
            var confirmation = booking.confirm(slot);
            return booking.finish(confirmation);
        }
    }

    @DurableWorkflow(name = "tags-workflow")
    public static class TagsWorkflow {
        @ActivityStub(startToCloseTimeout = "PT10S")
        private Booking booking;

        @WorkflowMethod
        public String run(String orderId) {
            var allowed = booking.allowedSkus(orderId);
            var picked = booking.pick(allowed);
            return booking.finish(picked);
        }
    }
}
```

### Raw output (run 2026-11-12, worktree @ cbf5a2a)

```
    AUDIT-B 3.8 OffsetDateTime: status=RUNNING (not terminal after 5s) standDowns=[StandDown[reason=NONDETERMINISTIC_HISTORY, detail=seq=2 step=Booking.confirm argsHash expected=3d04a79d stored=e2e5ec50]]
AuditB38ReplayFingerprintTest > offsetDateTimeResult_survivesRecovery() FAILED
    org.opentest4j.AssertionFailedError: replay of an unchanged workflow must not stand down ==> expected: <true> but was: <false>
    AUDIT-B 3.3 history before retry:
      1 ACTIVITY_FAILED Acts.primary
      2 ACTIVITY_COMPLETED Acts.fallback
      3 ACTIVITY_FAILED Acts.finish
      4 WORKFLOW_FAILED null
    AUDIT-B 3.3 history after retry + one recovery pass:
      2 ACTIVITY_COMPLETED Acts.fallback
      1 ACTIVITY_COMPLETED Acts.primary
    AUDIT-B 3.3 status=RUNNING standDowns=[StandDownCall[reason=NONDETERMINISTIC_HISTORY, workflowId=fallback-1, detail=seq=2 expected=Acts.finish stored=Acts.fallback type=ACTIVITY_COMPLETED], StandDownCall[reason=NONDETERMINISTIC_HISTORY, workflowId=fallback-1, detail=seq=2 expected=Acts.finish stored=Acts.fallback type=ACTIVITY_COMPLETED]] primaryCalls=2 fallbackCalls=1 finishCalls=1
AuditB33RetryCaughtFailureTest > retryAfterCaughtFailure_completesOnRecordedPath() FAILED
    org.opentest4j.AssertionFailedError: retry must not turn a caught failure into replay drift ==> expected: <true> but was: <false>
    AUDIT-B 3.2 timer: node B pendingWakes=50
AuditB32WakePermitLeakTest > sleepTimerFiredByNonOwningLeader_leavesPermitForever() FAILED
    org.opentest4j.AssertionFailedError: none of these workflows runs on node B ==> expected: <0> but was: <50>
    AUDIT-B 3.2 node A pendingWakes=0 node B pendingWakes=51
AuditB32WakePermitLeakTest > signalDeliveredOnNonOwningNode_leavesPermitAfterRunEnds() FAILED
    org.opentest4j.AssertionFailedError: every run finished on node A; node B should hold no wake permits ==> expected: <0> but was: <51>
> Task :maestro-core:test FAILED
    AUDIT-B 3.8 TreeSet: status=RUNNING (not terminal after 5s) standDowns=[StandDown[reason=NONDETERMINISTIC_HISTORY, detail=seq=2 step=Booking.pick argsHash expected=77ec0bde stored=f5a02cf2]]
AuditB38ReplayFingerprintTest > sortedSetResult_survivesRecovery() FAILED
    org.opentest4j.AssertionFailedError: replay of an unchanged workflow must not stand down ==> expected: <true> but was: <false>
    AUDIT-B 3.8 [default] record Set<String> from Set.of                in-memory:DIFFERS  jdbc-text:DIFFERS  live={"tags":["charlie","delta","bravo","alpha","echo"]} | mem={"tags":["bravo","alpha","delta","echo","charlie"]} | text={"tags":["bravo","alpha","delta","echo","charlie"]}
    AUDIT-B 3.8 [default] record Set<String> from TreeSet               in-memory:DIFFERS  jdbc-text:DIFFERS  live={"tags":["alpha","bravo","charlie","delta","echo"]} | mem={"tags":["bravo","alpha","delta","echo","charlie"]} | text={"tags":["bravo","alpha","delta","echo","charlie"]}
    AUDIT-B 3.8 [default] record Set<String> from LinkedHashSet         in-memory:same     jdbc-text:same     live={"tags":["zeta","alpha","mid"]} | mem={"tags":["zeta","alpha","mid"]} | text={"tags":["zeta","alpha","mid"]}
    AUDIT-B 3.8 [default] Set<String> (top-level) from TreeSet          in-memory:DIFFERS  jdbc-text:DIFFERS  live=["alpha","bravo","charlie","delta","echo"] | mem=["bravo","alpha","delta","echo","charlie"] | text=["bravo","alpha","delta","echo","charlie"]
    AUDIT-B 3.8 [default] OffsetDateTime +02:00                         in-memory:DIFFERS  jdbc-text:DIFFERS  live={"at":"2026-11-12T10:00:00+02:00"} | mem={"at":"2026-11-12T08:00:00Z"} | text={"at":"2026-11-12T08:00:00Z"}
    AUDIT-B 3.8 [default] ZonedDateTime Europe/Berlin                   in-memory:DIFFERS  jdbc-text:DIFFERS  live={"at":"2026-11-12T10:00:00+01:00"} | mem={"at":"2026-11-12T09:00:00Z"} | text={"at":"2026-11-12T09:00:00Z"}
    AUDIT-B 3.8 [default] OffsetDateTime UTC                            in-memory:same     jdbc-text:same     live={"at":"2026-11-12T10:00:00Z"} | mem={"at":"2026-11-12T10:00:00Z"} | text={"at":"2026-11-12T10:00:00Z"}
    AUDIT-B 3.8 [default] BigDecimal 10.50                              in-memory:same     jdbc-text:same     live={"amount":10.50} | mem={"amount":10.50} | text={"amount":10.5}
    AUDIT-B 3.8 [default] BigDecimal 18dp (1.123456789012345678)        in-memory:same     jdbc-text:DIFFERS  live={"amount":1.123456789012345678} | mem={"amount":1.123456789012345678} | text={"amount":1.1234567890123457}
    AUDIT-B 3.8 [default] BigDecimal 20 digits (12345678901234567.89)   in-memory:same     jdbc-text:DIFFERS  live={"amount":12345678901234567.89} | mem={"amount":12345678901234567.89} | text={"amount":12345678901234568}
    AUDIT-B 3.8 [default] Instant with nanos                            in-memory:same     jdbc-text:same     live={"at":"2026-11-12T10:00:00.123456789Z"} | mem={"at":"2026-11-12T10:00:00.123456789Z"} | text={"at":"2026-11-12T10:00:00.123456789Z"}
    AUDIT-B 3.8 [default] declared Base, live Sub (extra field)         in-memory:DIFFERS  jdbc-text:DIFFERS  live={"a":"a","b":"b"} | mem={"a":"a"} | text={"a":"a"}
    AUDIT-B 3.8 [default] Map<String,Object> Long 5                     in-memory:same     jdbc-text:same     live={"n":5} | mem={"n":5} | text={"n":5}
    AUDIT-B 3.8 [default] Map<String,Object> Long 2^60                  in-memory:same     jdbc-text:same     live={"n":1152921504606846976} | mem={"n":1152921504606846976} | text={"n":1152921504606846976}
    AUDIT-B 3.8 [default] Map<String,Object> Float 0.1f                 in-memory:same     jdbc-text:DIFFERS  live={"f":0.1} | mem={"f":0.1} | text={"f":0.1}
    AUDIT-B 3.8 [default] Map<String,Object> BigDecimal 10.50           in-memory:same     jdbc-text:same     live={"d":10.50} | mem={"d":10.50} | text={"d":10.5}
    AUDIT-B 3.8 [default] Map<String,Object> BigDecimal 18dp            in-memory:same     jdbc-text:DIFFERS  live={"d":1.123456789012345678} | mem={"d":1.123456789012345678} | text={"d":1.1234567890123457}
    AUDIT-B 3.8 [default] List<Object> Float 0.1f                       in-memory:same     jdbc-text:DIFFERS  live=[0.1] | mem=[0.1] | text=[0.1]
    AUDIT-B 3.8 [bigDecimalForFloats] record Set<String> from Set.of                in-memory:DIFFERS  jdbc-text:DIFFERS  live={"tags":["charlie","delta","bravo","alpha","echo"]} | mem={"tags":["bravo","alpha","delta","echo","charlie"]} | text={"tags":["bravo","alpha","delta","echo","charlie"]}
    AUDIT-B 3.8 [bigDecimalForFloats] record Set<String> from TreeSet               in-memory:DIFFERS  jdbc-text:DIFFERS  live={"tags":["alpha","bravo","charlie","delta","echo"]} | mem={"tags":["bravo","alpha","delta","echo","charlie"]} | text={"tags":["bravo","alpha","delta","echo","charlie"]}
    AUDIT-B 3.8 [bigDecimalForFloats] record Set<String> from LinkedHashSet         in-memory:same     jdbc-text:same     live={"tags":["zeta","alpha","mid"]} | mem={"tags":["zeta","alpha","mid"]} | text={"tags":["zeta","alpha","mid"]}
    AUDIT-B 3.8 [bigDecimalForFloats] Set<String> (top-level) from TreeSet          in-memory:DIFFERS  jdbc-text:DIFFERS  live=["alpha","bravo","charlie","delta","echo"] | mem=["bravo","alpha","delta","echo","charlie"] | text=["bravo","alpha","delta","echo","charlie"]
    AUDIT-B 3.8 [bigDecimalForFloats] OffsetDateTime +02:00                         in-memory:DIFFERS  jdbc-text:DIFFERS  live={"at":"2026-11-12T10:00:00+02:00"} | mem={"at":"2026-11-12T08:00:00Z"} | text={"at":"2026-11-12T08:00:00Z"}
    AUDIT-B 3.8 [bigDecimalForFloats] ZonedDateTime Europe/Berlin                   in-memory:DIFFERS  jdbc-text:DIFFERS  live={"at":"2026-11-12T10:00:00+01:00"} | mem={"at":"2026-11-12T09:00:00Z"} | text={"at":"2026-11-12T09:00:00Z"}
    AUDIT-B 3.8 [bigDecimalForFloats] OffsetDateTime UTC                            in-memory:same     jdbc-text:same     live={"at":"2026-11-12T10:00:00Z"} | mem={"at":"2026-11-12T10:00:00Z"} | text={"at":"2026-11-12T10:00:00Z"}
    AUDIT-B 3.8 [bigDecimalForFloats] BigDecimal 10.50                              in-memory:same     jdbc-text:same     live={"amount":10.50} | mem={"amount":10.50} | text={"amount":10.50}
    AUDIT-B 3.8 [bigDecimalForFloats] BigDecimal 18dp (1.123456789012345678)        in-memory:same     jdbc-text:same     live={"amount":1.123456789012345678} | mem={"amount":1.123456789012345678} | text={"amount":1.123456789012345678}
    AUDIT-B 3.8 [bigDecimalForFloats] BigDecimal 20 digits (12345678901234567.89)   in-memory:same     jdbc-text:same     live={"amount":12345678901234567.89} | mem={"amount":12345678901234567.89} | text={"amount":12345678901234567.89}
    AUDIT-B 3.8 [bigDecimalForFloats] Instant with nanos                            in-memory:same     jdbc-text:same     live={"at":"2026-11-12T10:00:00.123456789Z"} | mem={"at":"2026-11-12T10:00:00.123456789Z"} | text={"at":"2026-11-12T10:00:00.123456789Z"}
    AUDIT-B 3.8 [bigDecimalForFloats] declared Base, live Sub (extra field)         in-memory:DIFFERS  jdbc-text:DIFFERS  live={"a":"a","b":"b"} | mem={"a":"a"} | text={"a":"a"}
    AUDIT-B 3.8 [bigDecimalForFloats] Map<String,Object> Long 5                     in-memory:same     jdbc-text:same     live={"n":5} | mem={"n":5} | text={"n":5}
    AUDIT-B 3.8 [bigDecimalForFloats] Map<String,Object> Long 2^60                  in-memory:same     jdbc-text:same     live={"n":1152921504606846976} | mem={"n":1152921504606846976} | text={"n":1152921504606846976}
    AUDIT-B 3.8 [bigDecimalForFloats] Map<String,Object> Float 0.1f                 in-memory:same     jdbc-text:DIFFERS  live={"f":0.1} | mem={"f":0.10000000149011612} | text={"f":0.1}
    AUDIT-B 3.8 [bigDecimalForFloats] Map<String,Object> BigDecimal 10.50           in-memory:same     jdbc-text:same     live={"d":10.50} | mem={"d":10.50} | text={"d":10.50}
    AUDIT-B 3.8 [bigDecimalForFloats] Map<String,Object> BigDecimal 18dp            in-memory:same     jdbc-text:same     live={"d":1.123456789012345678} | mem={"d":1.123456789012345678} | text={"d":1.123456789012345678}
    AUDIT-B 3.8 [bigDecimalForFloats] List<Object> Float 0.1f                       in-memory:same     jdbc-text:DIFFERS  live=[0.1] | mem=[0.10000000149011612] | text=[0.1]
> Task :maestro-test:test FAILED
AuditB38FingerprintProbeTest > probe() FAILED
    org.opentest4j.AssertionFailedError: fingerprint changes across replay: [default: record Set<String> from Set.of, default: record Set<String> from TreeSet, default: Set<String> (top-level) from TreeSet, default: OffsetDateTime +02:00, default: ZonedDateTime Europe/Berlin, default: BigDecimal 18dp (1.123456789012345678), default: BigDecimal 20 digits (12345678901234567.89), default: declared Base, live Sub (extra field), default: Map<String,Object> Float 0.1f, default: Map<String,Object> BigDecimal 18dp, default: List<Object> Float 0.1f, bigDecimalForFloats: record Set<String> from Set.of, bigDecimalForFloats: record Set<String> from TreeSet, bigDecimalForFloats: Set<String> (top-level) from TreeSet, bigDecimalForFloats: OffsetDateTime +02:00, bigDecimalForFloats: ZonedDateTime Europe/Berlin, bigDecimalForFloats: declared Base, live Sub (extra field), bigDecimalForFloats: Map<String,Object> Float 0.1f, bigDecimalForFloats: List<Object> Float 0.1f] ==> expected: <true> but was: <false>
BUILD FAILED in 11s
```
