# Task 6 report — `workflow.version()` (memoized change-branching)

Worktree: `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/release-hardening`
Branch: `worktree-release-hardening` · start HEAD `108cd73` · commits `7338957`, `1816708`
All logs archived under `.superpowers/sdd/release-hardening/evidence/` with the
standard identity header (pwd / branch / HEAD / timestamp / command).

## Files read (grounding)

- `.superpowers/sdd/release-hardening-plan/task-6-brief.md`
- `.superpowers/sdd/release-hardening/observability-versioning-design.md` §5 (5.1–5.4), §8.4–8.5, §§10–11
- `docs/release-hardening-spec.md` §§C1–C2
- `maestro-core`: `context/WorkflowContext.java`, `engine/WorkflowOperations.java`,
  `engine/DefaultWorkflowOperations.java` (sleep / parallel / currentTime / randomUUID / appendEvent),
  `engine/WorkflowExecutor.java:1301-1396` (`launchWorkflow` — per-run operations instance),
  `engine/ActivityInvocationHandler.java:255` (the only `switch` over `EventType`),
  `model/EventType.java`, `model/WorkflowEvent.java`, `exception/MaestroException.java`
- `maestro-test/src/main/.../DeterminismChecker.java` (`decisions(...)`)
- `maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java:270,629` (event-type write/read)
- `maestro-store-postgres/src/main/resources/db/migration/V1__create_maestro_schema.sql:30`

## RED phases — verbatim from the archived logs

Every pin was run against code that could not satisfy it, in three rounds. The
naive stub and the plausible-but-wrong stub were applied to
`DefaultWorkflowOperations.version` only; nothing else differed.

### RED-0 — the pins cannot even be named at HEAD (`task-6-red0-compile.log`, exit 1)

```
timestamp: 2026-08-02T20:08:44Z   HEAD: 108cd73f3e3630a09ddc1637a7c6447859e80f42
maestro-test/src/test/java/io/b2mash/maestro/test/DeterminismCheckerVersionMarkerTest.java:56: error: cannot find symbol
  symbol:   variable VERSION_MARKER
maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowVersionTest.java:4: error: cannot find symbol
  symbol:   class UnsupportedWorkflowVersionException
maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowVersionParallelBranchTest.java:70: error: cannot find symbol
  symbol:   method version(String,int,int)
```

That is also the RED for the Postgres round-trip pin: pre-0.4.0 code has no
`VERSION_MARKER` constant for `EventType.valueOf` to map a stored row onto.

### RED-1 — naive stub, `return maxSupported;` (`task-6-red1.log`, exit 1)

```
> Task :maestro-core:test FAILED
workflow.version() — memoized change-branching > a live call returns maxSupported and records a VERSION_MARKER at the consumed sequence FAILED
workflow.version() — memoized change-branching > replay returns the recorded version even when the code's maxSupported has moved on FAILED
workflow.version() inside a parallel branch > the marker lands in the branch's own sequence block (p*1000 + (i+1)*1000) FAILED
A recorded version survives a redeploy that raises maxSupported > the replay returns the recorded version, appends no second marker, and takes the old branch FAILED
13 tests completed, 13 failed

> Task :maestro-test:test FAILED
DeterminismChecker and VERSION_MARKER > a version marker is recorded as a decision in the event log FAILED
DeterminismChecker and VERSION_MARKER > a workflow that resolves a different changeId per run is caught, and the marker names the divergence FAILED
3 tests completed, 2 failed
```

13/13 core pins and 2/3 checker pins observe the missing durable decision. The
third checker test (`a workflow whose version decision is stable passes the
check`) is a deliberate positive-control and passes in every phase.

### RED-2 — plausible-but-wrong stub: consume-unconditionally like `currentTime()`, no per-run cache, no min-guard (`task-6-red2.log`, exit 1)

```
> Task :maestro-core:test FAILED
workflow.version() — memoized change-branching > a history written before the change replays byte-identically and yields DEFAULT_VERSION FAILED
    io.b2mash.maestro.core.exception.DuplicateEventException at WorkflowVersionTest.java:131
workflow.version() — memoized change-branching > a recorded version below minSupported raises the typed error naming changeId and both bounds FAILED
workflow.version() — memoized change-branching > repeated calls with the same changeId in one run return the same value and write once FAILED
workflow.version() — memoized change-branching > a replayed run resolves a repeated changeId from the record, not from the new max FAILED
workflow.version() — memoized change-branching > invalid arguments are a coding error, not a workflow outcome FAILED
workflow.version() — memoized change-branching > the min-guard failure is a MaestroException — catchable, retryable, not a control-flow Error FAILED
13 tests completed, 7 failed
```

The `DuplicateEventException` is the peek-don't-consume symptom made concrete:
the wrong implementation consumed the old history's slot and then tried to write
its marker on top of an existing `(instance, sequence)` row.

### RED-1b / RED-2b — the one fixture that had to be corrected

`defaultVersionBelowMin_raisesTypedError` initially seeded a one-event history,
so `version()`'s peek landed on the *live frontier* (empty slot) rather than on a
pre-change event, and the GREEN implementation correctly recorded rather than
threw. The fixture was fixed to seed two events (the peeked slot must hold an
event for the "history predates the change" branch to apply), and then re-run
against **both** stubs to prove the corrected fixture is still RED:

```
task-6-red1b-corrected-fixture.log (2026-08-02T20:13:44Z, exit 1)
  ... a pre-change history fails the guard when the code no longer carries the old branch FAILED
task-6-red2b-corrected-fixture.log (2026-08-02T20:13:57Z, exit 1)
  ... a pre-change history fails the guard when the code no longer carries the old branch FAILED
        Caused by: io.b2mash.maestro.core.exception.DuplicateEventException at WorkflowVersionTest.java:183
```

(These two logs carry timestamps *after* `task-6-green-targeted.log` because the
stubs were re-applied deliberately, post-GREEN, to re-RED the corrected fixture;
the production file was restored from a byte-identical backup afterwards, and
the full GREEN + `build` runs below are both later than them.)

## Algorithm as implemented vs design §5

`DefaultWorkflowOperations.version(String, int, int)` — implemented exactly as
design §5.2's seven steps, in that order:

| Design §5.2 step | Implementation |
|---|---|
| argument validation | blank `changeId`, `maxSupported < 0`, `minSupported > maxSupported` → `IllegalArgumentException` |
| 1. per-run cache hit | `versionCache.get(changeId)` → `guardVersion(...)` → return; no sequence consumed, no write |
| 2. PEEK | `peekSeq = ctx.currentSequence() + 1`; `store.getEventBySequence(instanceId, peekSeq)` |
| 3. marker for this changeId | `ctx.nextSequence()` (consume), version from payload |
| 4. any other stored event | `DEFAULT_VERSION`, **slot not consumed** |
| 5. live frontier (empty) | `ctx.nextSequence()`, `setReplaying(false)`, append `VERSION_MARKER` / `$maestro:version:{changeId}` / `{"changeId":…,"version":maxSupported}`, return `maxSupported` |
| 6. min-guard | `resolved < minSupported` → `UnsupportedWorkflowVersionException(workflowId, changeId, resolved, min, max)` |
| 7. cache + return | `versionCache.put(changeId, checked)` |

Deviations: **none**. Two decisions §5 left open, resolved conservatively and
documented in-code:

1. **Match predicate for step 3** (`recordedVersion(event, changeId)`): a stored
   `VERSION_MARKER` counts as *this* change's marker only if its payload is
   non-null, its `changeId` matches, and its `version` field is numeric.
   Anything else (another change's marker, an unreadable payload) falls to step
   4 — `DEFAULT_VERSION`, slot not consumed. Chosen over throwing so a malformed
   marker cannot manufacture a workflow failure at an unrelated call site; a
   genuinely stranded marker still surfaces loudly one step later, when the
   activity proxy hits `Unexpected event type VERSION_MARKER` at its own slot
   (`ActivityInvocationHandler:265`).
2. **Guard-before-cache** ordering: the guard runs on cached hits too (§5.2 step
   1 says so explicitly), and nothing is cached when the guard throws — so a
   retried run re-resolves from the durable marker rather than from memory.

Per-run cache lifetime (§5.3) verified against the code rather than assumed:
`WorkflowExecutor.launchWorkflow` constructs a fresh `DefaultWorkflowOperations`
per launch (`WorkflowExecutor.java:1347`), and parallel branches share that one
instance (`DefaultWorkflowOperations.java:592` passes `DefaultWorkflowOperations.this`
into each branch context) — which is exactly why the cache is a
`ConcurrentHashMap` and why the "resolve before forking" rule is documented on
the public API.

Store awareness: **none needed**, as the brief predicted. `event_type` is a
plain `VARCHAR(50)` with no check constraint or enum
(`V1__create_maestro_schema.sql:30`), so no Flyway migration; the JDBC mapper is
`EventType.valueOf(...)` in both directions. `ActivityInvocationHandler:255` is
the only `switch` over `EventType` in the codebase and it has a `default`, so
nothing else needed touching.

Exception type: `UnsupportedWorkflowVersionException extends MaestroException`
(added to the sealed `permits` list) — deliberately **not** an `Error`, unlike
the engine's control-flow signals. Pinned by
`minGuardFailure_isAMaestroException`.

## The `DeterminismChecker` claim — verified, not assumed

Design §5.1 claims markers are treated as decisions with **no checker change**.
Verified: `DeterminismChecker.decisions(...)` fingerprints
`sequenceNumber:eventType:stepName`, and the marker is an ordinary event carrying
step name `$maestro:version:{changeId}`. The claim holds and `DeterminismChecker`
is **unmodified** (`git show --stat` on both commits touches no file under
`maestro-test/src/main`).

Pinned three ways in `DeterminismCheckerVersionMarkerTest`:

- the marker is in the log at all, with the changeId-bearing step name
  (`assertEquals("$maestro:version:shipping-v2", markers.getFirst().stepName())`);
- a stable version decision passes the check;
- a workflow that resolves a *different* changeId per run is caught, and the
  failure message contains both `VERSION_MARKER` and `$maestro:version:` —
  i.e. the divergence is reported *at the marker*, which is what "treated as a
  decision" has to mean operationally. (The branch-racing fixture §8.4 suggests
  was deliberately not used: it is inherently racy. Resolving a different
  changeId at the same slot exercises the same fingerprint property
  deterministically.)

The one checker-visible property markers do **not** carry is the recorded
*version number* (the fingerprint has no payload). That is correct — the version
value is a memoized fact, not a per-run decision; a run that recorded a different
number at the same slot is a different history, not a nondeterministic path.

## Test counts (GREEN)

`task-6-green.log` — `2026-08-02T20:14:07Z`, `BUILD SUCCESSFUL in 50s`, `exit-code: 0`,
command `./gradlew :maestro-core:test :maestro-test:test :maestro-store-postgres:test`.

| Module | tests | skipped | failures | errors |
|---|---|---|---|---|
| `maestro-core` | 354 | 0 | 0 | 0 |
| `maestro-test` | 55 | 0 | 0 | 0 |
| `maestro-store-postgres` | 58 | 0 | 0 | 0 |

New pins (17): `WorkflowVersionTest` 10, `WorkflowVersionParallelBranchTest` 2,
`WorkflowVersionRecoveryTest` 1, `DeterminismCheckerVersionMarkerTest` 3,
`PostgresWorkflowStoreTest$EventTests.versionMarker_roundTrips` 1.

Every core assertion is on the persisted log — which event, at which sequence,
with which payload — not on "the workflow completed":

- **live record**: marker at `seq 1`, step `$maestro:version:shipping-v2`,
  payload `{"changeId":"shipping-v2","version":3}`;
- **replay under a changed max**: end-to-end through a node replacement —
  node A runs `new VersionedWorkflow(1)`, parks on a signal, shuts down; node B
  recovers with `new VersionedWorkflow(3)` and the instance still returns
  `branch-v1:ship` with exactly one marker recording `version=1`;
- **pre-change history**: the whole event log before and after (sequence, type,
  step, payload) is compared for byte-equality, and the two replayed
  `randomUUID()` values equal the originals — the slot was not consumed;
- **min-guard**: typed exception with all five accessors plus message-complete
  assertions (`shipping-v2`, `version 1`, `[2..4]`);
- **repeated calls**: same value, `currentSequence()` unchanged across the second
  call, exactly one marker;
- **branch allocation**: markers at `seq 2001` and `seq 3001` for a fork at
  parent seq 1 (blocks `[2000..2999]`, `[3000..3999]`);
- **Postgres**: `VERSION_MARKER` round-trips through `getEventBySequence` and
  `getEvents` with its payload intact, and survives `deleteFailureEvents` (so
  admin Retry replays the same recorded version).

## Full build

`task-6-build.log` — `2026-08-02T20:16:16Z`, command `./gradlew build`:

```
> Task :maestro-integration-tests:check
> Task :maestro-integration-tests:build
BUILD SUCCESSFUL in 1m 54s
134 actionable tasks: 50 executed, 84 up-to-date
exit-code: 0
```

`:maestro-core:javadoc` and `:maestro-core:javadocJar` both ran clean. The 9
compiler warnings in the log are pre-existing Testcontainers deprecations in
`maestro-integration-tests`, untouched by this task.

## Notes for downstream tasks

- **Task 7** now has `EventType.VERSION_MARKER` to build on; per RULING 1 its
  permanent stand-down fixture uses `EVT_FROM_A_NEWER_MAESTRO`, and the SHOULD
  about injecting a `VERSION_MARKER` against a pre-Task-6 binary is discharged
  by that ruling. Design §5's stand-down interplay note ("if the peeked event's
  type is `UNKNOWN`, step 4 must NOT interpret it as 'predates the change'") is
  **not yet implementable** — `EventType.UNKNOWN` does not exist until Task 7.
  Task 7 must add that guard ahead of step 4's classification in
  `DefaultWorkflowOperations.version` (the peek is one of the engine's
  history-read sites), or an unreadable history at a version slot will silently
  resolve to `DEFAULT_VERSION` instead of standing down.
- **Task 8** (docs): `docs/concepts.md` gained a "Versioning Workflow Code"
  section, an API-reference row, and a cross-link from the determinism rule. The
  0.4.0 "upgrade all nodes together" line is stated there too; release notes and
  any `EventType` enumerations elsewhere are still Task 8's to sweep.
