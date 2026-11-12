# WP1 Replay Fidelity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A live activity call returns the value a replay of that call will return, so an unchanged workflow never stands down or diverges after a crash because a result did not survive the store. A result that cannot be read back never fails the run (D9). A typed `BigDecimal` replays exactly from Postgres (C1). `docs/pitfalls.md` gains rule 12.

**Architecture:** After the outcome append succeeds, the handler deserializes the payload exactly as replay does: same function, same generic return type. It returns that value instead of the activity's own object. Task 7 makes "the payload" mean the store's own stored form: a new default SPI method, which Postgres implements with `INSERT … RETURNING payload` and no extra statement. `PayloadSerializer` reads every payload through JSON text, so untyped numbers come back as a text store returns them. The JDBC store parses payload columns with exact decimals, if Task 1 proves the defect. `currentTime()` gets the same read-back. Nothing the engine writes changes. The one exception is Task 6's `SIGNAL_RECEIVED` decimal copy, which the mixed-version section covers.

**Tech Stack:** Java 25, `maestro-core` (no Spring), `maestro-store-jdbc`, `maestro-store-postgres` (jsonb, Testcontainers `postgres:16-alpine`), `maestro-test` kit, `maestro-spring-boot-starter` (Micrometer), Jackson 3 (`tools.jackson`), JUnit 5.

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md` (WP1 row, §2 anti-goals, §6, D7, D9, C1). Evidence: `evidence/audit-B-signal-replay-correctness.md` § 3.8 and appendix; `evidence/repro-tests/AuditB38FingerprintProbeTest.java.txt` and `AuditB38ReplayFingerprintTest.java.txt`. Siblings: `wp00-executor-builder.md` ("Adding an option later"; the executor is built only with `WorkflowExecutor.builder`) and `wp09-schema-safety.md` (`SeededHistory`, `Seeds.WP1_*`).

## Added and changed public surface

**Added**

| Surface | Exact shape | Task |
|---|---|---|
| SPI method | `default @Nullable JsonNode WorkflowStore.appendEventReturningPayload(WorkflowEvent event, RunLease lease)`. Default body: `appendEvent(event, lease); return event.payload();`. Contract: the payload exactly as a later read of this store returns it. Exceptions are those of `appendEvent(event, lease)`. If the stored form cannot be parsed, it returns `event.payload()`, because the write already succeeded. A decorating store should forward it. One that does not degrades to the default. | 7 |
| JDBC hook | `protected boolean AbstractJdbcWorkflowStore.returnsInsertedPayload()`. Default `false`: the store returns the text round trip of the payload it sent, which is exactly what a text column reads back. `PostgresWorkflowStore` overrides it to `true`, which appends ` RETURNING payload` to the same fenced `INSERT … SELECT`. | 7 |
| Observer callback | `default void EngineObserver.activityResultUnreadable(ActivityInfo a, String exceptionType) {}`. It is fired live only, on the workflow thread, after the outcome append and before `activityCompleted`. `exceptionType` is the class of the deserialization root cause and must never be a tag. `CompositeEngineObserver` forwards it. | 2 |
| Meter | `maestro.activity.result.unreadable` (Prometheus `maestro_activity_result_unreadable_total`). Counter. Tags: `workflow` (workflow type) and `activity` (step name, `<ActivityName>.<method>`). Both values are bounded by code. Source: `MicrometerEngineObserver.activityResultUnreadable`. | 3 |
| WARN log line | The fixed copy is under Task 2 Step 4. It is logged once per activity method per proxy and at DEBUG after that. It never contains a payload value. | 2 |
| Doc rule | `docs/pitfalls.md` rule 12, "Your workflow gets an activity's result as the store gives it back, not the object the activity returned". The rule count becomes twelve wherever it is stated (Task 9 lists the sites). | 9 |

**Changed behaviour** (no new type, property, exception or migration)

- A live activity call returns `deserialize(storedPayload, method.getGenericReturnType())`, not the activity's object (D7). A result that cannot be read back is returned raw (D9).
- `WorkflowContext.currentTime()` returns the read-back of the `Instant` it recorded (Task 4).
- `PayloadSerializer.deserialize` (both overloads) reads the node through JSON text with the same mapper. A `null` or `NullNode` yields the target type's Jackson null value: `null` for ordinary references, `Optional.empty()` for `Optional` (Task 5).
- `AbstractJdbcWorkflowStore` parses payload columns with `DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS`. Typed `BigDecimal` targets become exact; `Object` and `Map<String, Object>` targets still get `Double` (C1, Task 6). This applies only if Task 1 proves the defect.
- Package-private only: `UnknownHistoryGuard.redactedCause` becomes package-private; `StoreFaultGuard.RETRIED` gains `appendEventReturningPayload`; the kit's `NodeStoreView` offers that method to the crash policy.

## Decisions

### D-1. Where the read-back happens

The order in `ActivityInvocationHandler.executeLiveHoldingSlot`:

1. **Serialize.** `payload = serializer.serialize(result)`, as today. A `SerializationException` here is still an ordinary live failure, which is today's rule.
2. **Append.** Append the payload through the guarded store, exactly as today. Task 7 switches this call to `appendEventReturningPayload`, which `StoreFaultGuard` retries like `appendEvent` and refuses once the run is latched. `DuplicateEventException`, `StoreUnavailableException` and `FencedWriteException` keep today's handling. The read-back never runs after any of them, so a store fault still latches `STORE_UNAVAILABLE` and is never recorded as a failure.
3. **Read back.** Read back the node the append returned (until Task 7, the node that was sent) with the existing `deserializeResult(node, method)`. That is the very function replay calls. It returns `null` for `void`/`Void`, and the target type is `method.getGenericReturnType()`. The `try` around the read-back contains no store call. It catches `SerializationException` only, so an `Error` (a control-flow signal included) and a `StoreUnavailableException` cannot be swallowed there.
4. **On `SerializationException` (D9).** Call `observer.activityResultUnreadable`, log the WARN, and return the raw `result`. The event is durable. A replay of the step stands down as `UNKNOWN_EVENT_PAYLOAD`, which is today's outcome.
5. **Then** the lifecycle event, `observer.activityCompleted`, and return the read-back value. `registerCompensationIfAnnotated` in `invoke` already receives the returned value, so a `@Compensate` method that takes the result gets the read-back too.

**Why after the append and not before (the audit's design):** a pre-append read-back failure could only fail the run, which breaches D9 and anti-goal 1, or carry on anyway. So the order buys nothing, and after the append the value is by construction the deserialization of exactly what was persisted.

### D-2. The other live-versus-replay paths

Line numbers refer to the WP0 checkout.

| Path | Today | Verdict |
|---|---|---|
| Activity result | Live returns the raw object (`ActivityInvocationHandler.java:709-725`); replay deserializes (`:542-544`, `:563-568`) | **In:** Tasks 2, 5, 6, 7 |
| `@Compensate` argument taken from the result | Live registers the raw `result` (`:514-516`); replay registers the deserialized one (`:500-505`) | **In:** fixed by Task 2 and pinned there |
| `Optional.empty()` result | Live returns `Optional.empty()`; replay returns `null`, because `PayloadSerializer.java:69,93` short-circuits `NullNode` | **In:** Task 5 |
| `currentTime()` | Live returns the raw `now` (`DefaultWorkflowOperations.java:961-965`); replay deserializes (`:947-957`). No effect with Jackson 3's default ISO-8601 form. Diverges when the host mapper writes timestamps as millis, which truncates the replay. | **In:** Task 4 |
| `randomUUID()`, `version()` | A `String` and an `int` that round-trip exactly (`:975-992`; `VersionDetail`) | Not affected |
| `awaitSignal` / `collectSignals` | Both paths deserialize a stored node: live reads the signal row (`SignalManager.java:1395-1397`), replay reads the `SIGNAL_RECEIVED` copy of it (`:753-755`, copy written `:1360-1365`). `jsonb` normalization is idempotent. | Not affected. Signal decimals become exact on both paths in Task 6. |
| `parallel()` results | Computed by branch code (`DefaultWorkflowOperations.java:815, 930-935`). Only `ParallelDetail(branchCount)` is memoized (`:778`). | Not affected directly; fixed transitively by Task 2 |
| `@QueryMethod` results | Invoke the live object (`WorkflowExecutor.java:3121-3140`); nothing is memoized | Not affected |
| `ACTIVITY_FAILED` | Live throws the original cause object, with its subclass, fields and cause chain (`:681-703`). Replay throws `FailureReconstruction.reconstruct(type, message)`, which keeps type and message only (`:546-549`, `:577-597`). Workflow code that reads a custom field of the cause diverges after recovery. `docs/concepts.md:159` and rule 2 document only the type part. | **New issue** `Issue N1` (Task 9). Making live throw the reconstruction changes what authors catch, so it needs its own decision. |
| Workflow input on Postgres | The first run deserializes the node it serialized (`WorkflowExecutor.java:596` → `:2438-2441`); recovery reads the `jsonb` row | Decimals and untyped numbers are fixed by Tasks 5–6. Object key order and exponent numbers remain: **new issue** `Issue N2` (Task 9). The likely fix is Task 7's pattern on `createInstance`. |

### D-3. What is written is unchanged

- **Activity payloads.** The appended payload is still `serializer.serialize(result)`, and Task 7 inserts the same value. Pinned by `LiveResultReadBackTest.liveReadBackLeavesPayloadsAsBefore` (Task 2), `PostgresPayloadFidelityTest.returningInsertStoresTheSameJsonbAsAppendEvent` (Task 7) and `SeededHistoryReplayFidelityIT.thisBuildWritesTheBytesCbf5a2aWrote` (Task 8, against bytes written at `cbf5a2a`).
- **The args hash.** `ArgumentFingerprint.java` is not touched; the reviewer checks `git diff <base> -- '**/ArgumentFingerprint.java'` is empty. A later call's hash changes only when its argument derives from a result that did not round-trip, and it then equals what replay computes: that is the fix. For round-trip-stable results (most records, `List`, `String`, UTC `Instant`, 2-dp `BigDecimal`), the downstream hash equals today's. Pinned by `liveReadBackLeavesPayloadsAsBefore`'s stable rows.
- **The one exception (Task 6).** A `SIGNAL_RECEIVED` memo copies the signal row's payload as read. With the exact reader, a ≥17-digit decimal is copied exactly instead of double-rounded. An old node replays such a copy as lossily as it replays any decimal, so this is no new hazard.

### D-4. Mixed fleets and runs in flight across the upgrade

- **(a) Healed.** The old build ran a result live, then crashed or parked before the consumer ran. The new build replays the result and runs the consumer live with the same value. `seed-wp1-*` pin this on real `cbf5a2a` history (Task 8).
- **(b) Not healed, not worse.** The old build recorded a later activity whose argument came from a raw, non-round-tripping value. On the new build the replay hashes the read-back value and stands down as `nondeterministic_history`, which is exactly what the old build does after any restart. Nothing fails or compensates. The escape hatch is `maestro.replay.verify-arguments=false`: the recorded step then replays and the run continues with the read-back value. Pinned by `RawArgumentHistoryStandDownIT` (Task 7).
- **(c) New, narrow (C1).** Requires all of these:
  - an activity returned a `BigDecimal` with ≥17 significant digits (or one later passed through `toString()`, which is scale-sensitive);
  - the old build *recovered* the run after that activity, which replays the decimal lossily;
  - a later activity was recorded with an argument derived from it;
  - the run is then replayed on the new build.

  The new build replays the exact value and stands down (a stand-down, never a failure). The release note names it, with the same escape hatch.
- **Old node replaying new history.** New live values equal replay values on both builds, except ≥17-digit decimals: an old node replays those lossily and stands down, exactly as it does today on its own history. A new node then adopts the run.

### D-5. D9's meter and WARN

- **Meter and callback** are as in the surface table. Documented in `docs/observability.md` (counters table, counts) and in the `docs/operations.md` §10.4 alert table. It is a leading indicator: every run counted will stand down as `unknown_event_payload` at its next recovery.
- **The WARN** names the workflow id, the step, the sequence, the declared type and the *redacted* cause (`UnknownHistoryGuard.redactedCause`: class plus structural JSON path). It never includes the Jackson message, which quotes stored values.

### D-6. C1's exact scope

The store reader produces `DecimalNode`s, and `PayloadSerializer` then parses JSON text with the host mapper's own features.

- **Exact:** every deserializer that asks for a `BigDecimal`: a `BigDecimal` at top level, as a field, an element or a typed map value, and `java.time` types read from numeric timestamps.
- **Unchanged:** every untyped `Object`, `Map<String, Object>` or `List<Object>` position gets `Double`; `double` and `float` fields get the same value as today; integers get `Integer`, `Long` or `BigInteger` as today.
- **Host's choice:** a host mapper with `USE_BIG_DECIMAL_FOR_FLOATS` already on gets `BigDecimal` everywhere, both live and on replay.
- **Proof:** the Task 6 matrix, and Task 5's `textPathEqualsTreePathForEveryNodeTheJdbcStoreReads` proves the text path alone moves no value read from history.

### D-7. The test kit

No "render payloads to text" mode is added to `InMemoryWorkflowStore`:

- After Task 5, every value the engine hands workflow code is parsed from JSON text. The kit therefore already returns what a text store returns: `Double` and `Integer` for untyped numbers, base64 strings for binary inside untyped maps, exact decimals, and copies instead of aliases of the stored node.
- What a text round trip still could not show is `jsonb`'s own normalization: keys shortest-first, exponent numbers rewritten. Only emulating Postgres internals would show that.
- After Task 7, live and replay agree on each store. So the kit's map order can differ from production's, but it cannot hide a stand-down.
- `docs/testing.md:432` says this (Task 9).

### D-8. Performance

Each live activity costs one extra deserialization, plus a text render and parse per deserialization (live and replay). On Postgres, each outcome insert also returns its payload bytes. **No statement is added**, so WP11's `gate.statementsPerWorkflow` must not move. The release note says this. WP11's `perf/run.sh s1-throughput` (scenario 1, 5 activities per workflow) is where the cost shows; no benchmark is run in this package.

## Global Constraints

- Spec anti-goals (§2), verbatim in effect:
  - no fix makes a run fail or compensate where today it would stand down or be abandoned;
  - history written by the current build keeps replaying (mixed fleets during a rolling deploy must keep working);
  - **an existing test is changed only as a named step** (the list is under "Existing tests changed").
  - An implementer who meets a failing test that encodes the old raw-return behaviour and is not named here stops and asks.
- `maestro-core` never imports Spring. Jackson 3 (`tools.jackson`) only; `com.fasterxml.jackson.annotation` is allowed. JSpecify `@Nullable` on every nullable parameter and return. Javadoc on every public member, with thread-safety notes. No Lombok.
- **Control-flow `Error`s** (`CLAUDE.md` "Exceptions"): no new `catch` may intercept a `MaestroControlFlowError`. Every new `catch` names `SerializationException` only. Anywhere an outcome is unwrapped, check `instanceof Error` before `Exception`.
- **`StoreUnavailableException`** is a store fault, never a workflow failure. No new `catch` may record it, swallow it or turn it into a returned value. The read-back `try` contains no store call.
- No log line or meter tag carries a payload value, a workflow id or a run id. Meter tags are only `workflow` and `activity`.
- **Branch and worktree.** Branch `fix/replay-fidelity`, cut from `origin/main` **after PR #50 (WP0) has merged**, in the worktree `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/replay-fidelity`. Before the first edit run `git -C <worktree> status` and `git -C /Users/rakheendama/Projects/2026/maestro status`. Never `cd` into the main checkout. This plan is read from the main checkout's absolute path. Task 8 needs WP9 on `main`, and the PR merges only after WP9 has.
- **Gradle.** Always `./gradlew --no-watch-fs`, run from the worktree root. Never two Gradle runs on one module at once. No `git stash`.
- **Commits.** Commit at every checkpoint named below. Chain git steps with `&&`. Pass messages as `-m "$(cat <<'EOF' … EOF)"`. Never run git-mutating commands in parallel. Every message ends with `Co-Authored-By: Claude Fable 5.2 <noreply@anthropic.com>`.
- **Long runs.** Any run longer than ~5 minutes (`build`, the whole `:maestro-integration-tests:test`, `e2eTest`, the soak) is a **coordinator gate**. The coordinator launches it detached, with a log whose header records `pwd`, `git rev-parse HEAD` and a timestamp. Implementers do not start them.
- **Red tests.**
  - Every new red test is run against the unfixed code first and must fail on a positive fact: a value, a class, a status. It must not fail on an absence or on a `ConditionTimeoutException`.
  - Paste the FAIL lines into the commit body.
  - Each fix gets at least one mutation check. The mutation is reverted in the same step.
- **Repro ports.** Neither repro constructs an executor, so WP0's migration rule has nothing to rewrite. Any executor a new test builds is `WorkflowExecutor.builder(store, serializer, "svc")` with only the setters it needs (WP0 "Adding an option later"). The evidence files are not edited.
- **PR gates (spec §6):** Build & Test; nightly E2E; chaos PR-gate ×3 (`./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks`, three consecutive runs); the 2 h soak (`./gradlew --no-watch-fs :maestro-integration-tests:e2eTest --rerun-tasks -Dmaestro.chaos.soak=true -Dmaestro.chaos.durationMinutes=120`). The maintainer merges.

## Review Focus

1. **A result Jackson can write but not read** (an interface with no type info) must complete live with the activity's own object, be counted and WARNed, and stand down later as `UNKNOWN_EVENT_PAYLOAD`, never `FAILED`. → Task 2 `unreadableResult_completesLiveWithTheRawValueAndIsReported`, plus the unchanged `ReplayPayloadTest`.
2. **A store fault or an ambiguous commit on the outcome append** must latch `STORE_UNAVAILABLE` or stand down `STALE_RUN`, and must never reach the read-back. → Task 2 `outcomeAppendOutage_latchesAndNeverReadsBack`; Task 7 `StoreFaultGuardTest` additions.
3. **`null`, `void` and `Optional.empty()` results, and primitive returns.** The live value must equal the replay value with no NPE, and a SQL `NULL` must come back from `RETURNING`. → Task 5 kit row `OPTIONAL_EMPTY`; Task 7 IT rows `NULL_RESULT`, `OPTIONAL_EMPTY`; the existing `liveExecutionVoid`.
4. **A host mapper with non-default features** (`USE_BIG_DECIMAL_FOR_FLOATS` on, numeric timestamps) must keep live and replay equal under the host's choice. → Task 6 host-mapper row; Task 4 millis-timestamp test.
5. **The kit's crash points** must keep firing on activity completions once the engine calls the new SPI method; otherwise `crashAfter(n)` silently stops counting. → Task 7 `NodeStoreViewTest.appendEventReturningPayloadIsOfferedToTheCrashPolicy`, plus every ported `crashAfter` test.

## Existing tests changed (named; the only allowed edits)

| File | Edit | Task |
|---|---|---|
| `maestro-core/src/test/java/io/b2mash/maestro/core/observe/RecordingEngineObserver.java` (test support) | Add `record UnreadableResult(ActivityInfo info, String exceptionType)`, its list, the callback and the accessor `unreadableResults()` | 2 |
| `maestro-core/src/test/java/io/b2mash/maestro/core/observe/CompositeEngineObserverTest.java` | `invokeEveryCallback` calls `activityResultUnreadable`; `everyCallbackFansOut` asserts `recorder.unreadableResults().size() == 1` | 2 |
| `maestro-test/src/test/java/io/b2mash/maestro/test/NodeStoreViewTest.java:47-51` | `WRITES` gains `"appendEventReturningPayload"` (forced by `everyStoreMethodIsClassifiedAsAReadOrAWrite`) | 7 |
| `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/KafkaTracePropagationContractTest.java:246-250` | `case "appendEventReturningPayload" -> ((WorkflowEvent) args[0]).payload();` | 7 |
| `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/SeededHistory.java` (WP9, class Javadoc only) | Replace the sentence that `seed-wp1-offset`/`-set` "are expected to stand down" with: they park before their consumer activity, so they complete on every build; WP1 pins this in `SeededHistoryReplayFidelityIT` | 8 |

**Existing test files that only gain new test methods:** `StoreFaultGuardTest`, `PayloadSerializerTest`, `MicrometerEngineObserverTest` and `MaestroObservabilityAutoConfigurationTest`.

**Reviewer check (whole hunks, not lines):**
1. Run `BASE=$(git merge-base origin/main HEAD); git diff --name-only --diff-filter=M "$BASE" HEAD -- '*/src/test/*'`. It must print exactly the five named files plus the four add-only files.
2. For each add-only file, `git diff "$BASE" HEAD -- <file> | grep -c '^-[^-]'` must print `0`. Any edit to an existing statement, a continuation line included, shows up as a removed line.
3. For each named file, read every hunk with `git diff "$BASE" HEAD -- <file>` and confirm it is the named edit.

A grep for changed `assert` lines is not used, because it misses the continuation lines of multi-line assertions (found in WP0).

## File structure

| File | Responsibility |
|---|---|
| `maestro-core/.../engine/ActivityInvocationHandler.java` | The read-back and its D9 fallback |
| `maestro-core/.../engine/PayloadSerializer.java` | Text-path deserialization; Jackson null values |
| `maestro-core/.../engine/DefaultWorkflowOperations.java` | `currentTime()` read-back |
| `maestro-core/.../engine/StoreFaultGuard.java`, `UnknownHistoryGuard.java` | Retry the new append; share the redaction |
| `maestro-core/.../spi/WorkflowStore.java` | The new default SPI method |
| `maestro-core/.../observe/EngineObserver.java`, `CompositeEngineObserver.java` | The D9 callback |
| `maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java`, `maestro-store-postgres/.../PostgresWorkflowStore.java` | The exact-decimal reader; `RETURNING` |
| `maestro-test/.../NodeStoreView.java` | Crash classification of the new method |
| `maestro-spring-boot-starter/.../observe/MicrometerEngineObserver.java` | The meter |
| New tests | `PostgresPayloadFidelityTest`, `LiveResultReadBackTest`, `CurrentTimeReadBackTest`, `ActivityResultReadBackTest` (kit), `LiveResultMatchesReplayIT`, `RawArgumentHistoryStandDownIT`, `SeededHistoryReplayFidelityIT` |

---

### Task 1: Settle the decimal question on real Postgres

**Files:**
- Create: `maestro-store-postgres/src/test/java/io/b2mash/maestro/store/postgres/PostgresPayloadFidelityTest.java` (extends `PostgresTestSupport`)

**Interfaces:**
- Produces: `private JsonNode roundTrip(JsonNode payload)`. It creates an instance, `store.appendEvent`s an `ACTIVITY_COMPLETED` at the next sequence and returns `store.getEventBySequence(...).orElseThrow().payload()`. Also `serializer = new PayloadSerializer(objectMapper)` and the record `Quote(BigDecimal amount)`. Tasks 6 and 7 add tests to this class.

- [ ] **Step 1: Create the worktree.**

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin && \
git -C /Users/rakheendama/Projects/2026/maestro log origin/main --oneline -1 --grep='executor-builder\|#50' && \
git -C /Users/rakheendama/Projects/2026/maestro worktree add .claude/worktrees/replay-fidelity -b fix/replay-fidelity origin/main && \
git -C /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/replay-fidelity status
```

Expected: the log line shows the WP0 merge, and the worktree is clean on `fix/replay-fidelity`. If the log shows nothing, stop: WP0 has not merged.

- [ ] **Step 2: Write the tests.**

```java
@ParameterizedTest @ValueSource(strings = {"12345678901234567890.123456789", "1.123456789012345678", "10.50"})
void aTypedBigDecimalReadsBackExactly(String digits) {
    var read = roundTrip(serializer.serialize(new Quote(new BigDecimal(digits))));
    assertEquals(new BigDecimal(digits), serializer.deserialize(read, Quote.class).amount());   // equals is scale-sensitive
}

@Test void anUntypedDecimalReadsBackAsADouble() {          // C1 guard: must stay green through Task 6
    var read = roundTrip(serializer.serialize(Map.of("d", new BigDecimal("1.123456789012345678"))));
    var d = ((Map<?, ?>) serializer.deserialize(read, Object.class)).get("d");
    assertEquals(Double.class, d.getClass());
    assertEquals(1.1234567890123457d, d);
    // the same two assertions through a Map<String, Object> generic type and through List<Object> ([1.123456789012345678])
}

@Test void jsonbReordersObjectKeysShortestFirst() {         // characterization: justifies Task 7
    var read = roundTrip(objectMapper.createObjectNode().put("aa", 1).put("b", 2));
    assertNotEquals(List.of("aa", "b"), propertyNames(read), "jsonb kept insertion order");
    assertEquals(List.of("b", "aa"), propertyNames(read));
}

@Test void jsonbRewritesAWholeExponentNumberAsAnInteger() {  // characterization: justifies Task 7
    var read = roundTrip(serializer.serialize(Map.of("x", 1.0E7)));
    assertTrue(read.get("x").isIntegralNumber(), read.toString());
    assertEquals(Integer.class, ((Map<?, ?>) serializer.deserialize(read, Object.class)).get("x").getClass());
}
```

- [ ] **Step 3: Run it on the unfixed code.** Run `./gradlew --no-watch-fs :maestro-store-postgres:test --tests 'io.b2mash.maestro.store.postgres.PostgresPayloadFidelityTest'`.

Expected: the three `aTypedBigDecimalReadsBackExactly` cases FAIL (for example `expected: <10.50> but was: <10.5>`), and the other three tests PASS.

**Decision rule** (record the outcome verbatim in the commit body):
- If any `aTypedBigDecimalReadsBackExactly` case **passes**, the defect is disproved for that input. If all three pass, delete Task 6 from this branch, keep the test as a regression pin, and tell the coordinator.
- If a characterization test fails, stop and report what Postgres returned: Task 7's premise would be wrong. If only the exact-order assertion fails (`["b","aa"]`) while `assertNotEquals` holds, report it too.
- Do not adjust an expectation in either case.

- [ ] **Step 4: Commit** the test with the red cases disabled. Run `@Disabled("red until Task 6: 3.8 decimal defect proven on Postgres — see commit body")` on the parameterized method only. Commit message: `test(store): prove the 3.8 decimal defect on a real jsonb column`. Task 6 removes the `@Disabled`. The commit body must hold the FAIL lines.

---

### Task 2: A live activity returns the read-back value; an unreadable result returns raw (D7, D9)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/ActivityInvocationHandler.java`
  - `:707-725` (the append and the return);
  - class Javadoc: a new `<h2>Live results are read back</h2>` before "Idempotency";
  - a new field `private final Set<Method> warnedUnreadable = ConcurrentHashMap.newKeySet();`.
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/UnknownHistoryGuard.java:134` (`private static` → `static`)
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/observe/EngineObserver.java` (after `activityFailed`, `:126`), `CompositeEngineObserver.java` (after `:161`)
- Modify (named): `RecordingEngineObserver.java`, `CompositeEngineObserverTest.java`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/LiveResultReadBackTest.java`
- Create: `maestro-test/src/test/java/io/b2mash/maestro/test/ActivityResultReadBackTest.java` (ports both `AuditB38*` repros)

**Interfaces:**
- Produces:
  - `EngineObserver.activityResultUnreadable(ActivityInfo, String)`;
  - `RecordingEngineObserver.unreadableResults()`;
  - `static String ActivityInvocationHandler.unreadableResultWarning(String workflowId, String stepName, int seq, Type declared, Throwable cause)` (package-private);
  - `private @Nullable Object readBack(@Nullable JsonNode stored, Method method, @Nullable Object raw, ActivityInfo info, WorkflowContext ctx, int seq, String stepName)`. Task 7 passes it the store's node.

- [ ] **Step 1: Write the kit tests** (`ActivityResultReadBackTest`, kit default mapper). The fixtures are the repro's `Booking`, `SlotWorkflow` and `TagsWorkflow`, with one change: `confirm` returns `"confirmed " + slot.id() + " at " + slot.start()`.

```java
@Test void theLiveRunSeesTheStoredFormOfAnOffsetDateTime() throws Exception {
    var handle = env.startWorkflow(SlotWorkflow.class, "order-0");
    assertEquals("confirmed slot-7 at 2026-11-12T08:00Z done", handle.getResult(String.class, Duration.ofSeconds(10)));
}
@Test void offsetDateTimeResult_survivesRecovery()   // repro 1: crashAfter(2), recover; the status string as in the repro
    // assertEquals(WorkflowStatus.COMPLETED, status)   — the repro's absence assertion is replaced by this positive one
@Test void sortedSetResult_survivesRecovery()        // repro 2: same, TagsWorkflow

@ParameterizedTest @EnumSource(ProbeCase.class)
void liveValueIsTheReplayedValue(ProbeCase c) throws Exception {
    // ProbeWorkflow.run(caseName): v = probes.<case>(); Seen.record(workflowId, v); return sink.take(v);
    // crashAfter(2) (after sink's event), recover, await COMPLETED.
    var seen = Seen.of(id);                                     // [live value, replayed value]
    assertEquals(2, seen.size());
    assertEquals(ArgumentFingerprint.of(serializer, new Object[]{seen.get(1)}),
                 ArgumentFingerprint.of(serializer, new Object[]{seen.get(0)}), c.name());
    assertEquals(seen.get(1) == null ? null : seen.get(1).getClass(), seen.get(0) == null ? null : seen.get(0).getClass());
    assertEquals(c.comparable(seen.get(1)), c.comparable(seen.get(0)));   // Set rows compare List.copyOf(set): iteration order
}
```

- `ProbeCase` holds the probe's 18 cases (`AuditB38FingerprintProbeTest`, default mapper), each with its own typed method on `Probes`.
- `serializer` is `new PayloadSerializer(JsonMapper.builder().build())`, the same default mapper the kit uses.
- `Seen` is a static `ConcurrentHashMap<String, List<Object>>`. The test clears it in `@BeforeEach`.

- [ ] **Step 2: Write the core tests** (`LiveResultReadBackTest`). Each test builds an executor with `WorkflowExecutor.builder(store, serializer, "svc").observer(observer).build()`. Its proxies come from the 13-argument `ActivityProxyFactory.createProxy(..., observer, TaskQueueGovernor.UNBOUNDED, LifecycleEventSink.NOOP, true)`. `store` is a `FaultyStore`, pre-wrapped with `StoreFaultGuard.wrap(store, List.of(Duration.ofMillis(1), Duration.ofMillis(1)))`.

```java
@Test void unreadableResult_completesLiveWithTheRawValueAndIsReported() {
    // Decider.decideBare() declares BareDecision (sealed interface, no type info) and returns BareApproved("bob");
    // the workflow returns decision.getClass().getSimpleName()
    assertEquals(COMPLETED, awaitStatus(id));
    assertEquals("BareApproved", result(id));                      // the raw object reached workflow code
    assertEquals(1, observer.unreadableResults().size());
    assertEquals("Decider.decideBare", observer.unreadableResults().getFirst().info().activityName());
    assertEquals("tools.jackson.databind.exc.InvalidDefinitionException", observer.unreadableResults().getFirst().exceptionType());
    // (if Jackson names another DatabindException subtype, use the class the first green run reports, and say so in the commit)
    var names = observer.callbackNames();
    assertTrue(names.indexOf("activityResultUnreadable") < names.indexOf("activityCompleted"), names.toString());
}

@Test void unreadableResultWarning_namesStepTypeAndCauseClass_neverAPayloadValue() {
    var cause = jacksonFailureQuoting("4111-1111-1111-1111");   // a real DatabindException whose message quotes the value
    var line = ActivityInvocationHandler.unreadableResultWarning("wf-1", "Pay.card", 3, BareDecision.class, cause);
    assertTrue(line.contains("wf-1") && line.contains("Pay.card") && line.contains("sequence 3")
            && line.contains(BareDecision.class.getTypeName()) && line.contains(cause.getClass().getName()), line);
    assertFalse(line.contains("4111"), line);
}

@Test void compensationReceivesTheReadBackResult() {
    // @Saga: reserve() returns Slot(OffsetDateTime +02:00) with @Compensate("release"), release(Slot); then fail() throws
    assertEquals(FAILED, awaitStatus(id));
    assertEquals(ZoneOffset.UTC, impl.released.get().start().getOffset());
    assertEquals(ArgumentFingerprint.of(serializer, new Object[]{impl.released.get()}), argsHashOf(id, "Acts.release"));
}

@Test void outcomeAppendOutage_latchesAndNeverReadsBack() {
    store.failWhen("appendEvent", args -> ((WorkflowEvent) args[0]).eventType() == EventType.ACTIVITY_COMPLETED);
    // await the run's abandon; the recorder's AbandonCall list for id holds exactly one entry
    assertEquals(AbandonReason.STORE_UNAVAILABLE, onlyAbandonCall(observer, id).reason());
    assertEquals(WorkflowStatus.RUNNING, store.getInstance(id).orElseThrow().status());
    assertEquals(0, observer.unreadableResults().size());
    assertEquals(1, impl.calls.get());
}

@ParameterizedTest @MethodSource("stableAndUnstableResults")
void liveReadBackLeavesPayloadsAsBefore(Object raw, Type declared, boolean roundTripStable) {
    // produce() returns raw; the workflow passes the value to sink(Object); both live
    assertEquals(serializer.serialize(raw), payloadOf(id, "Acts.produce"));           // payload exactly as before
    if (roundTripStable) {
        assertEquals(ArgumentFingerprint.of(serializer, new Object[]{raw}), argsHashOf(id, "Acts.sink"));   // what cbf5a2a wrote
    } else {
        assertEquals(ArgumentFingerprint.of(serializer, new Object[]{serializer.deserialize(serializer.serialize(raw), declared)}),
                argsHashOf(id, "Acts.sink"));                                         // what replay computes
    }
}
```

Stable rows: a record of `String`s, `List<String>`, `Instant` with nanos, `BigDecimal("10.50")`, `OffsetDateTime` UTC. Unstable rows: `OffsetDateTime` +02:00, a `TreeSet` record, `Sub` declared as `Base`.

  Write `unreadableResult_…` and `unreadableResultWarning_…` in Step 4, once the callback exists. Every other test here compiles against the unfixed code.

- [ ] **Step 3: Run them on the unfixed code.**

Run:
```
./gradlew --no-watch-fs :maestro-core:test --tests '*LiveResultReadBackTest'
./gradlew --no-watch-fs :maestro-test:test --tests '*ActivityResultReadBackTest'
```

Expected:
- core: `compensationReceivesTheReadBackResult` FAILS with `expected: <Z> but was: <+02:00>`. `outcomeAppendOutage_…` and `liveReadBackLeavesPayloadsAsBefore` PASS: they are invariance guards.
- kit: `theLiveRunSeesTheStoredForm…` FAILS (`…T10:00+02:00 done`); both repro ports FAIL (`expected: <COMPLETED> but was: <RUNNING (not terminal after 5s)>`); `liveValueIsTheReplayedValue` FAILS at least for `SET_OF_RECORD`, `TREESET_RECORD`, `TREESET_TOP_LEVEL`, `OFFSET_PLUS_TWO`, `ZONED_BERLIN` and `BASE_SUB`.
- Record the full red list.

- [ ] **Step 4: Implement.**
  - Add the callback with the Javadoc from the surface table. Forward it in `CompositeEngineObserver` like its siblings.
  - Add `unreadableResultWarning` as a stub returning `""`. Write the two D9 tests from Step 2 and run them. Both compile and FAIL on a value: `unreadableResults()` has size 0, and the warning line does not contain `wf-1`. Record the FAIL lines.
  - Make the named `RecordingEngineObserver`/`CompositeEngineObserverTest` edits.
  - In `executeLiveHoldingSlot`, hoist `var payload = serializer.serialize(result);` above the append's `try`, pass `payload` to `appendEventSafe`, and after the `catch` return `readBack(payload, method, result, info, ctx, seq, stepName)`. The lifecycle publish and `observer.activityCompleted` move between the read-back and the `return`.
  - `readBack` is `try { return deserializeResult(stored, method); } catch (SerializationException unreadable) { … }`. The catch:
    - calls `observer.activityResultUnreadable(info, root)`, where `root` is `unreadable.getCause() != null ? unreadable.getCause().getClass().getName() : unreadable.getClass().getName()`;
    - logs `unreadableResultWarning(...)` at WARN the first time `warnedUnreadable.add(method)` is true, else at DEBUG;
    - returns `raw`.
  - Exact copy of the WARN:

```
Workflow '%s': the result of activity '%s' at sequence %d is recorded but cannot be read back as %s (%s); this run continues with the object the activity returned, and a replay of this step will stand the run down as unknown_event_payload — make the type deserializable (docs/pitfalls.md, rule 12)
```

The last `%s` is `UnknownHistoryGuard.redactedCause(root cause)`. The `<h2>` Javadoc paragraph states D-1 in four sentences.

- [ ] **Step 5: Run the tests and the touched suites.**

Run:
```
./gradlew --no-watch-fs :maestro-core:test
./gradlew --no-watch-fs :maestro-test:test
```

Expected: `BUILD SUCCESSFUL`. `ReplayPayloadTest` is unchanged and green. A failure in a test not named here stops the task (Global Constraints).

- [ ] **Step 6: Mutation checks** (revert each one).
  - M1: `return result;` instead of the read-back. The kit red list and `compensationReceivesTheReadBackResult` go red.
  - M2: deserialize to `Object.class` instead of the generic return type. `liveValueIsTheReplayedValue` goes red on its record rows (class mismatch).
  - M3: widen the read-back `try` to wrap the append and catch `RuntimeException`. `outcomeAppendOutage_latchesAndNeverReadsBack` goes red.
  - M4: `serializer.serialize(readBackValue)` as the appended payload. `liveReadBackLeavesPayloadsAsBefore[OffsetDateTime +02:00]` goes red.

- [ ] **Step 7: Commit** with the message `fix(core): a live activity returns the value replay reads back (3.8, D7, D9)` and the red and mutation lines in the body.

---

### Task 3: The `maestro.activity.result.unreadable` counter

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/observe/MicrometerEngineObserver.java` (the method, plus a row in the class-Javadoc catalog after `maestro.activity.duration`)
- Modify: `docs/observability.md`
  - the counters table row: `maestro.activity.result.unreadable` | `workflow`, `activity` | workflow type, activity name | "A live activity's result was recorded but cannot be read back as its declared type; the run continued with the activity's own object and will stand down as `unknown_event_payload` at its next recovery (D9)";
  - `:70`: "Seventeen meters: **14 counters**" becomes "Eighteen meters: **15 counters**";
  - `:172`: "13 of the 23" becomes "14 of the 24".
- Modify: `docs/operations.md` §10.4 table (`:628`). New row: `rate(maestro.activity.result.unreadable) > 0` → "A live result cannot be read back; those runs will stand down as `unknown_event_payload` on their next recovery. Fix the return type (Pitfalls §12)."
- Test (new methods): `MicrometerEngineObserverTest.activityResultUnreadableCountsByWorkflowAndActivity`, `MaestroObservabilityAutoConfigurationTest.anUnreadableActivityResultIsCounted`

**Interfaces:**
- Consumes: Task 2's callback.

**WP11 rebase.** WP11 adds `maestro.run.abandoned`, `maestro.workflow.duration`, `maestro.taskqueue.slots.used` and `workflowFinished` to these same files. The names do not collide. A rebase in either order touches:
- `EngineObserver`, `CompositeEngineObserver` and `MicrometerEngineObserver` (adjacent additions; keep both);
- `RecordingEngineObserver`, and `CompositeEngineObserverTest.invokeEveryCallback`/`everyCallbackFansOut` (keep both lines);
- `docs/observability.md`: the count sentences. Recount from the tables: counter rows, timer rows, gauge rows, and `grep -c 'default ' EngineObserver.java` for callbacks. Never add the two deltas by hand.
- the `docs/release-notes.md` Unreleased bullets.

- [ ] **Step 1: Write the tests.**
  - `MicrometerEngineObserverTest`: call `activityResultUnreadable(info("OrderWorkflow", "Pay.card"), "x.Y")` twice. Assert `registry.get("maestro.activity.result.unreadable").tag("workflow","OrderWorkflow").tag("activity","Pay.card").counter().count() == 2.0`, and that the meter's tags are exactly those two.
  - `MaestroObservabilityAutoConfigurationTest`: run a workflow whose activity declares a sealed interface without type info, under the existing `runner` with `SimpleMeterRegistry`. Await the counter `== 1.0`. Assert `maestro.workflow.completed` for that type `== 1.0`.
- [ ] **Step 2: Run them.** Run `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests '*MicrometerEngineObserverTest' --tests '*MaestroObservabilityAutoConfigurationTest'`. Expected: FAIL with `MeterNotFoundException` for `maestro.activity.result.unreadable`.
- [ ] **Step 3: Implement** `increment("maestro.activity.result.unreadable", TAG_WORKFLOW, a.workflowType(), TAG_ACTIVITY, a.activityName())`, then make the doc edits.
- [ ] **Step 4: Run them again.** Same command. Expected: PASS. Mutation: tag `activity` with `exceptionType` and the first test goes red. Revert.
- [ ] **Step 5: Commit** with the message `feat(observe): count live activity results that cannot be read back (D9)`.

---

### Task 4: `currentTime()` returns its read-back

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/DefaultWorkflowOperations.java:961-965`
- Create: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/CurrentTimeReadBackTest.java`

- [ ] **Step 1: Write the tests.**
  - Executor: `WorkflowExecutor.builder(store, new PayloadSerializer(millisMapper), "svc").instantSource(InstantSource.fixed(Instant.parse("2026-11-12T10:00:00.123456789Z"))).build()`.
  - `millisMapper` enables Jackson 3's `DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS` and disables `DateTimeFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS`.
  - `currentTimeIsTheRecordedValue_whenTheMapperWritesMillis`: the workflow returns `ctx.currentTime().toString()`; assert the result is `"2026-11-12T10:00:00.123Z"`.
  - `currentTimeWritesTheSamePayloadAsBefore`: the `SIDE_EFFECT` payload equals `serializer.serialize(Instant.parse("2026-11-12T10:00:00.123456789Z"))`.
  - `defaultMapperIsUnaffected`: with the default mapper the result is `"2026-11-12T10:00:00.123456789Z"`.
- [ ] **Step 2: Run them.** Run `./gradlew --no-watch-fs :maestro-core:test --tests '*CurrentTimeReadBackTest'`. Expected: the first test FAILS (`…123456789Z`); the other two PASS.
- [ ] **Step 3: Implement.**
  - Keep `var payload = serializer.serialize(now)` and append it. Then return `serializer.deserialize(payload, Instant.class)`.
  - On `SerializationException`, return `now`. The replay of that slot already stands down as `UNKNOWN_EVENT_PAYLOAD`, which `maestro.standdown` counts. No meter.
  - One comment line names D7.
- [ ] **Step 4: Run them again.** Same command, then `:maestro-core:test`. Expected: PASS. Mutation: `return now;` and the first test goes red. Revert.
- [ ] **Step 5: Commit** with the message `fix(core): currentTime() returns the value its replay reads (D7)`.

---

### Task 5: `PayloadSerializer` reads through JSON text; null becomes the type's null value

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/PayloadSerializer.java:59-103` (both overloads, plus the class Javadoc "Null and Void Handling")
- Test (new methods): `maestro-core/src/test/java/io/b2mash/maestro/core/engine/PayloadSerializerTest.java`; kit row `OPTIONAL_EMPTY` and a new `ProbeCase` row `MAP_FLOAT_STAYS_DOUBLE_ON_REPLAY` in `ActivityResultReadBackTest`

**Interfaces:**
- Produces: `deserialize(node, type)` = `objectMapper.readerFor(javaType).readValue(objectMapper.writeValueAsBytes(node == null ? NullNode.instance : node))`. Every `Exception` still becomes `SerializationException`. This holds for both overloads.

- [ ] **Step 1: Write the tests.**

```java
@Test void untypedNumbersReadBackAsATextStoreReadsThem() {
    var m = (Map<?, ?>) serializer.deserialize(serializer.serialize(Map.of("f", 0.1f, "n", 5L)), Object.class);
    assertEquals(Double.class, m.get("f").getClass());  assertEquals(0.1d, m.get("f"));
    assertEquals(Integer.class, m.get("n").getClass());
}
@Test void aJsonNodeResultIsACopyNotTheStoredNode() {
    var stored = serializer.serialize(Map.of("a", 1));
    var read = serializer.deserialize(stored, JsonNode.class);
    assertEquals(stored, read);  assertNotSame(stored, read);
}
@Test void nullReadsAsTheTargetTypesNullValue() {
    assertEquals(Optional.empty(), serializer.deserialize(NullNode.instance, OPTIONAL_STRING_TYPE));
    assertEquals(Optional.empty(), serializer.deserialize(null, OPTIONAL_STRING_TYPE));
    assertNull(serializer.deserialize(NullNode.instance, String.class));
}
@ParameterizedTest @MethodSource("jdbcReadCorpus")       // anti-goal 2: history replays as before
void textPathEqualsTreePathForEveryNodeTheJdbcStoreReads(String json, Type target) {
    var node = MAPPER.readTree(json);                     // what today's JDBC store hands the engine
    var before = MAPPER.treeToValue(node, MAPPER.getTypeFactory().constructType(target));
    var after = serializer.deserialize(node, target);
    assertEquals(before, after);  assertEquals(before == null ? null : before.getClass(), after == null ? null : after.getClass());
}
```

The corpus holds:
- typed: a record with `BigDecimal`, `double`, `float`, `int`, `long`, `Instant`, `OffsetDateTime`, `String`, `List<String>`, `Set<String>`, `Map<String, Integer>` and `byte[]`;
- untyped: `Object`, `Map<String, Object>` and `List<Object>`, over decimals, exponents, ints, longs, big integers, booleans and nested objects.

- [ ] **Step 2: Run them.** Run `./gradlew --no-watch-fs :maestro-core:test --tests '*PayloadSerializerTest'` and `./gradlew --no-watch-fs :maestro-test:test --tests '*ActivityResultReadBackTest'`.

Expected:
- the first three tests FAIL (`Float`, `Long`; `assertNotSame`; `expected: <Optional.empty> but was: <null>`);
- `textPathEqualsTreePath…` PASSES (it is a guard);
- kit `OPTIONAL_EMPTY` FAILS (live `Optional.empty`, replay `null`).

- [ ] **Step 3: Implement** the Interfaces line. Rewrite "Null and Void Handling": a `null` or `NullNode` reads as Jackson's null value for the target (`null`, or `Optional.empty()` for `Optional`); a payload is always read through its JSON text, as a JDBC store reads it.
- [ ] **Step 4: Run the touched suites.** Run `:maestro-core:test`, then `:maestro-test:test`. Expected: PASS. Mutation: give the text read `.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)`. `untypedNumbersReadBack…` and `textPathEqualsTreePath…[untyped decimal]` go red. Revert.
- [ ] **Step 5: Commit** with the message `fix(core): payloads are read through JSON text, as a JDBC store reads them`.
- [ ] **Step 6: Coordinator gate.** `./gradlew --no-watch-fs build` on this commit. Expected: `BUILD SUCCESSFUL`.

---

### Task 6: Exact decimals for typed targets in the JDBC store (C1)

Skip this task if Task 1 disproved the defect.

**Files:**
- Modify: `maestro-store-jdbc/src/main/java/io/b2mash/maestro/store/jdbc/AbstractJdbcWorkflowStore.java:1679-1685` (`parseJsonNode` uses `objectMapper().reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)`), plus the class Javadoc sentence on JSON
- Test: `PostgresPayloadFidelityTest`. Remove Task 1's `@Disabled` and add the new methods below.

- [ ] **Step 1: Write the tests.**
  - `c1Matrix` (parameterized) checks, via `roundTrip` then `serializer.deserialize`:
    - exact `BigDecimal` for a top-level `BigDecimal`, a `Quote` field, `List<BigDecimal>` and `Map<String, BigDecimal>` (digits `12345678901234567890.123456789`, and `10.50` with its scale);
    - `Double` with value `1.1234567890123457` for `Object`, `Map<String, Object>` and `List<Object>`;
    - the same `double` as `Double.parseDouble` for a `double` field;
    - `0.1f` for a `float` field;
    - `Integer` 5 and `Long` 2^60 for untyped ints.
  - `aHostMapperWithBigDecimalForFloatsGetsBigDecimalUntypedToo`: a second store and serializer on a mapper with the feature on. Untyped gives `BigDecimal("1.123456789012345678")`.
  - `signalReceivedCopiesTheExactDecimal`: `saveSignal` with payload `Map.of("d", new BigDecimal("1.123456789012345678"))`, read it with `getUnconsumedSignals`, `appendEvent` a `SIGNAL_RECEIVED` with that payload, then `SELECT payload::text`. It contains `1.123456789012345678`. This is the D-3 exception, pinned.
- [ ] **Step 2: Run them.** Run `./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*PostgresPayloadFidelityTest'`. Expected: Task 1's cases, the `c1Matrix` exact rows and `signalReceived…` FAIL. The untyped rows and the host-mapper row PASS.
- [ ] **Step 3: Implement** the reader change.
- [ ] **Step 4: Run them again** (same command, then `:maestro-store-postgres:test`). Expected: PASS.

  Mutations, each reverted:
  - drop the feature: the exact rows go red;
  - have `PayloadSerializer` deserialize with `treeToValue` again: the untyped rows return `BigDecimal` and go red.

- [ ] **Step 5: Commit** with the message `fix(store): typed BigDecimal payloads replay exactly from Postgres (C1)`.

---

### Task 7: The store returns what it stored (Postgres `INSERT … RETURNING payload`)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/spi/WorkflowStore.java` (after `:357`)
- Modify: `StoreFaultGuard.java:121` (`RETRIED` gains the name; the Javadoc `:34` names it)
- Modify: `ActivityInvocationHandler.java`. `appendEventSafe` returns `@Nullable JsonNode` from `store.appendEventReturningPayload(event, ctx.lease())`. `persistFailure` ignores it. The live path passes it to `readBack`.
- Modify: `AbstractJdbcWorkflowStore.java:614-700`. Override the new method, add the hook `returnsInsertedPayload()`, and add a private insert that runs `executeQuery` when the hook is on. No row means `FencedWriteException`; `23505` means `DuplicateEventException`; any other `SQLException` means `storeFault(...)`, exactly as `appendEvent(event, lease)`. A parse failure of the returned column returns `event.payload()` and logs at DEBUG.
- Modify: `PostgresWorkflowStore.java` (`returnsInsertedPayload() { return true; }`)
- Modify: `maestro-test/src/main/java/io/b2mash/maestro/test/NodeStoreView.java:216-221` (`case "appendEvent", "appendEventReturningPayload", "appendEventAndConsumeSignal"`), Javadoc `:30-32`
- Modify (named): `NodeStoreViewTest` `WRITES`; `KafkaTracePropagationContractTest`
- Test (new): `NodeStoreViewTest.appendEventReturningPayloadIsOfferedToTheCrashPolicy`; `StoreFaultGuardTest.appendEventReturningPayloadIsRetriedLikeAppendEvent` and `…IsRefusedOnceLatched`; `PostgresPayloadFidelityTest` additions; `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/engine/LiveResultMatchesReplayIT.java`; `…/engine/RawArgumentHistoryStandDownIT.java`

**Interfaces:**
- Produces the SPI method and hook in the surface table.

- [ ] **Step 1: Write the tests.**
  - **`PostgresPayloadFidelityTest`** (new methods). The read-back helper calls `store.appendEventReturningPayload(event, new RunLease(instanceId, 0))`.
    - `appendEventReturningPayloadReturnsWhatGetEventBySequenceReads`, with rows: key order `{"aa":1,"b":2}`, the exponent `1.0E7`, the exact decimal, `NullNode` and `null`. The returned node equals `getEventBySequence(...).payload()`, and property order is compared too.
    - `returningInsertStoresTheSameJsonbAsAppendEvent`: for each row, `SELECT payload::text` of the two events is equal.
    - `returningInsertIsFencedAndUniqueLikeAppendEvent`: epoch 1 against a row at 0 throws `FencedWriteException` and leaves 0 rows; a second insert at the same sequence throws `DuplicateEventException`.
  - **`LiveResultMatchesReplayIT`** (harness on Postgres, pattern `ArgumentDriftStandDownIT`):
    - Node A starts `ProbeWorkflow(case)`. It produces the value, records it in `Seen`, and awaits `"go"`. Close node A.
    - Node B runs `recover()`, which replays and records the value, then delivers `"go"`. Assert COMPLETED.
    - Assert, as in Task 2, that the fingerprint, the class and the comparable form of the two `Seen` values are equal.
    - Rows: `MAP_KEY_ORDER` (`Map<String, Integer>`; comparable = `List.copyOf(keySet())`), `UNTYPED_EXPONENT`, `MAP_FLOAT`, `DECIMAL_19`, `DECIMAL_10_50`, `OFFSET_PLUS_TWO`, `TREESET_RECORD`, `OPTIONAL_EMPTY`, `NULL_RESULT`.
  - **`RawArgumentHistoryStandDownIT`** (a pin of D-4(b); it passes before and after):
    - Node A runs `reserveSlot()` (+02:00), then `confirm(slot)`, and parks.
    - `UPDATE … SET args_hash = <fingerprint of the raw +02:00 slot>` on `confirm`'s event, which is what `cbf5a2a` wrote there.
    - Node B runs `recover()`. Assert `standDowns()` holds exactly one `NONDETERMINISTIC_HISTORY` whose detail is `"seq=2 step=Booking.confirm argsHash expected=<fp(read-back)[0..8]> stored=<fp(raw)[0..8]>"`.
    - Assert the status equals the status before recovery, `failed()` and `compensating()` are empty, and `confirm` ran exactly once.
    - Test-side mutation: write the read-back's fingerprint instead, and the test goes red.
  - **`NodeStoreViewTest`**: a policy that crashes `after` seq 1, called through `appendEventReturningPayload` on a workflow thread, throws `ExecutorShutdownException` and the event is present. **`StoreFaultGuardTest`**: `failNext("appendEvent", 2)` → the call returns the payload and `calls("appendEvent") == 3`; a latched run's call throws `StoreUnavailableException` and `calls("appendEvent") == 0`.
- [ ] **Step 2: Run them on the previous task's commit (Task 6, or Task 5 if Task 6 was dropped).**

Run:
```
./gradlew --no-watch-fs :maestro-store-postgres:test --tests '*PostgresPayloadFidelityTest'
./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*LiveResultMatchesReplayIT' --tests '*RawArgumentHistoryStandDownIT'
```

Expected:
- compilation FAIL in the store test (no method). Run the IT alone: `MAP_KEY_ORDER` FAILS (`expected: <[b, aa]> but was: <[aa, b]>`), `UNTYPED_EXPONENT` FAILS (`Integer` vs `Double`), and every other row PASSES;
- `RawArgumentHistoryStandDownIT` PASSES.

- [ ] **Step 3: Implement** everything under Files, then make the two named test edits.
- [ ] **Step 4: Run the tests and the touched suites.**

Run:
```
./gradlew --no-watch-fs :maestro-core:test
./gradlew --no-watch-fs :maestro-test:test
./gradlew --no-watch-fs :maestro-store-postgres:test
./gradlew --no-watch-fs :maestro-messaging-kafka:test --tests '*KafkaTracePropagationContractTest'
```

Then the two ITs again. Expected: PASS.

Mutations, each reverted:
- the Postgres hook returns `false`: `MAP_KEY_ORDER` and `UNTYPED_EXPONENT` go red;
- remove the name from `RETRIED`: `…IsRetriedLikeAppendEvent` goes red;
- remove it from `NodeStoreView`: the new `NodeStoreViewTest` test and `ActivityResultReadBackTest`'s `crashAfter` tests go red.

- [ ] **Step 5: Commit** with the message `feat(store): appendEventReturningPayload — a live result is what Postgres stored (3.8)`.
- [ ] **Step 6: Coordinator gate.** `./gradlew --no-watch-fs build` and the whole `:maestro-integration-tests:test`. Expected: `BUILD SUCCESSFUL` for both.

---

### Task 8: History written at `cbf5a2a` still replays (WP9's seeded fixture)

**Files:**
- Create: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/SeededHistoryReplayFidelityIT.java` (`@Tag("integration")`; WP9's rule is that new assertions go in a new class)
- Modify (named): the `SeededHistory` class Javadoc sentence (see "Existing tests changed")

**Interfaces:**
- Consumes (WP9): `SeededHistory.load()/upgrade(SchemaMode)/node(String, RecordingObserver, Invocations)/dataSource()/serializer()/awaitStatusOrLast(...)`, `Seeds.WP1_SERVICE`, `Seeds.WP1_DECIMAL`, `Seeds.WP1_OFFSET`, `Seeds.WP1_SET`, `SeedTypedResultWorkflow`, `TypedRequest`.

- [ ] **Step 1: Bring WP9 in.**

```bash
WT=/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/replay-fidelity
git -C "$WT" fetch origin && git -C "$WT" status --short && \
git -C "$WT" log origin/main --oneline -1 -- maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/upgrade/SeededHistory.java && \
git -C "$WT" rebase origin/main
```

Expected: a clean status, a non-empty log line, and a clean rebase. If the log is empty, stop: WP9 has not merged. Read the frozen `cbf5a2a/SeededHistoryWorkflows.java` on `main`. If `SeedTypedResultWorkflow` calls `settle*` *before* the `"go"` await (contrary to WP9's plan), stop and report.

- [ ] **Step 2: Write the tests.** Helper: `events(id)` is a JDBC query over `seeded.dataSource()` for `sequence_number, event_type, step_name, payload::text, args_hash` ordered by sequence.

```java
@Test void dormantTypedRunsWrittenAtCbf5a2aCompleteOnThisBuild() {
    var seeded = SeededHistory.load().upgrade(SchemaMode.FLYWAY);
    try (var node = seeded.node(Seeds.WP1_SERVICE, observer, invocations)) {
        assertEquals(3, node.recover());
        for (var id : List.of(WP1_DECIMAL, WP1_OFFSET, WP1_SET)) node.deliverSignal(id, "go", "go");
        for (var id : List.of(WP1_DECIMAL, WP1_OFFSET, WP1_SET))
            assertEquals(COMPLETED, seeded.awaitStatusOrLast(id, COMPLETED, Duration.ofSeconds(60)), id);
    }
    assertEquals(List.of(), observer.standDowns());
    assertEquals(0, invocations.count("seed-typed.exactAmount"));  // replayed, never re-run (same for localStamp, tags)
    assertEquals(1, invocations.count("seed-typed.settleAmount"));  // live once (same for settleStamp, settleTags)
    // each settle* event's args_hash == ArgumentFingerprint.of(serializer, {deserialize(first event's payload, the frozen method's generic return type)})
    assertEquals(ArgumentFingerprint.of(seeded.serializer(), new Object[]{new BigDecimal("12345678901234567890.123456789")}),
            argsHash(WP1_DECIMAL, "seed-typed.settleAmount"));   // the exact decimal written at cbf5a2a reached the activity
}

@Test void thisBuildWritesTheBytesCbf5a2aWrote() {
    // start SeedTypedResultWorkflow DECIMAL, OFFSET, SET under fresh ids on this build; await WAITING_SIGNAL
    // first ACTIVITY_COMPLETED of each vs the seed's: payload::text equal for DECIMAL and OFFSET
    //   (OFFSET's text still carries "+02:00": the write path is unchanged although the returned value is UTC);
    //   SET compared as a sorted element list (Set.of's order varies per JVM); args_hash equal for all three
}
```

- [ ] **Step 3: Run them.** Run `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*SeededHistoryReplayFidelityIT' --tests '*SeededHistoryUpgradeIT'`. Expected: PASS, with WP9's own seven tests unchanged.

  Mutations, each reverted:
  - revert Task 6's reader: the decimal `args_hash` assertion goes red (a double-rounded value reached `settleAmount`);
  - append `serializer.serialize(readBackValue)` instead: `thisBuildWritesTheBytesCbf5a2aWrote[OFFSET]` goes red.

- [ ] **Step 4: Edit the `SeededHistory` Javadoc sentence**, then commit with the message `test(upgrade): history written at cbf5a2a replays and is rewritten byte-identically after WP1`.

---

### Task 9: Rule 12, documentation, release note, new issues; gates

**Files:**
- `docs/pitfalls.md`:
  - `:3` "Eleven" becomes "Twelve";
  - add `## 12. Your workflow gets an activity's result as the store gives it back, not the object the activity returned` before "See Also";
  - "See Also" names rule 12 among the crash-test rules.
- Rule count elsewhere: `README.md:280`, `docs/concepts.md:744` and `docs/getting-started.md:640` say "Twelve rules…". `docs/testing.md:715` ("The ten mistakes…", already stale) becomes "Twelve rules…". `CLAUDE.md:16` becomes "The twelve workflow-author rules (…, no same-queue waits in activities, results arrive as the store returns them)".
- `CLAUDE.md` "Three SPIs": add `default @Nullable JsonNode appendEventReturningPayload(WorkflowEvent event, RunLease lease); // the payload as stored (Postgres: INSERT … RETURNING); a live activity returns what replay reads`. Grep `appendEventAndConsumeSignal` across `docs/*.md` for any other full SPI listing, and add the method there too.
- `docs/concepts.md:204`: replace the sentence from "Two author-side habits…" to the end with: "One author-side habit surfaces as a stand-down where it used to replay silently: an activity that mutates its own argument objects (the live call hashes the pre-mutation value, the replay the post-mutation one, if the same object is reused) — keep arguments immutable. A result that does not round-trip through Jackson no longer drifts: a live call returns the value replay reads back ([Pitfalls §12](pitfalls.md#12-…))."
- `docs/testing.md:432`: replace the bullet with D-7's text, in two sentences.
- `docs/release-notes.md`: a new first subsection under `## Unreleased`.
- `docs/open-issues.md`: `Issue N1` and `Issue N2` from D-2 take the next free numbers (`grep -n '^### Issue' docs/open-issues.md | tail -1`). Each has the house sections: what happens, file:line, harm, fix shape and why it is not WP1. Add a summary-table row for each.

**Rule 12 content, in order** (the house shape: Wrong, Right, What you see, Test it):
- **Opening.** A live call returns the stored result deserialized into the method's declared return type, so the value is the same on the first run and after every restart, but it is not always the object the activity built.
- **Wrong.** Code that relies on any of these:
  - the offset of an `OffsetDateTime` or `ZonedDateTime` (it comes back in UTC);
  - the order of a `Set.of`, `HashSet` or `TreeSet` (it comes back as a `HashSet`);
  - a subtype returned under a declared supertype, or `Object`: fields are lost, and a cast now fails on the **first** run (the D7 edge);
  - `Long`, `Float` or `BigDecimal` inside `Map<String, Object>` (they come back as `Integer`/`Long` and `Double`);
  - map iteration order on Postgres (shortest key first);
  - identity with, or mutation of, the activity's object.
- **Right.**
  - Declare the concrete type, or use `@JsonTypeInfo`.
  - Use `Instant`, or keep the offset as its own field.
  - Use `List` or a `SortedSet` (read as a `TreeSet`) when order matters, and `SortedMap` or a `List` of entries for ordered maps.
  - Use typed `BigDecimal` for money.
- **What you see.** The value differs from what the activity returned, identically live and on replay. A type Jackson cannot read at all logs the Task 2 WARN, increments `maestro.activity.result.unreadable`, completes this run, and stands down as `unknown_event_payload` at the next restart.
- **Test it.** A plain kit test now shows the read-back value: assert on it. `crashAfter(n)` + `recover()` shows the stand-down. Map order shows only against Postgres.

**Release-note subsection:** `### Changed — a live activity call returns the value its replay returns (3.8, D7, D9, C1)`. Its bullets:
- **Before:** live raw versus replay round trip, with the stand-down and divergence examples.
- **Now:** D-1; `currentTime()`; the text-path read; `Optional.empty()`; exact typed decimals on Postgres.
- **What is written:** D-3.
- **In-flight runs:** D-4 (a), (b) and (c), with the `verify-arguments=false` escape hatch.
- **Kit:** untyped `Float`/`Long` now read as `Double`/`Integer`, as on Postgres.
- **SPI:** the default method; decorators should forward it.
- **Performance:** D-8, naming `perf/run.sh s1-throughput`.

- [ ] **Step 1: Make the edits above.**
- [ ] **Step 2: Verify that no stale count remains.** Run `grep -rniE "eleven (rules|workflow-author)|ten mistakes|eleven rules" README.md CLAUDE.md docs/*.md maestro-samples/README.md`. Expected: no output. Run `grep -n "^## 12\." docs/pitfalls.md`. Expected: one line.
- [ ] **Step 3: Run the existing-tests check** in "Existing tests changed" and paste its output into the commit body. Then `./gradlew --no-watch-fs :maestro-core:javadoc :maestro-store-jdbc:javadoc`. Expected: no warning naming a changed member.
- [ ] **Step 4: Commit** with the message `docs: pitfalls rule 12, replay-fidelity release note, two new issues (3.8)`.
- [ ] **Step 5: Coordinator gates, before the PR merges.**
  - `./gradlew --no-watch-fs build`;
  - the whole `:maestro-integration-tests:test`;
  - nightly E2E on the branch;
  - chaos PR-gate ×3;
  - the 2 h soak.

  Every result goes in this plan's review section with its log identity.

---

## Self-review (run 2026-11-12)

- **Spec coverage.**
  - (a) D7: Task 2, extended by Task 7 (Postgres) and Task 4 (`currentTime`).
  - (b) D9: Tasks 2 and 3.
  - (c) The Postgres proof first: Task 1, which gates Task 6. C1's scope: D-6 and Task 6's matrix.
  - (d) Rule 12 and its counts: Task 9.
  - Replay-compatible with existing history: Task 5 `textPathEqualsTreePath…`, Task 8, D-4.
  - Merge after WP9: Global Constraints and Task 8 Step 1.
  - The surface listed first: yes.
- **Every decision the brief asked for:**
  - placement: D-1;
  - other paths: D-2;
  - what is written: D-3;
  - in-flight runs: D-4;
  - the meter: D-5;
  - C1: D-6;
  - the kit: D-7;
  - performance: D-8.
- **Type consistency.** These names are the same in every task: `appendEventReturningPayload(WorkflowEvent, RunLease)`, `returnsInsertedPayload()`, `activityResultUnreadable(ActivityInfo, String)`, `unreadableResults()`, `readBack(...)`, `unreadableResultWarning(...)`, `ProbeCase`, `Seen`.
- **Review Focus.** Every item has an owning test (Tasks 2, 4, 5, 6, 7).
- **Proportion.** The code blocks are test assertions and commands only. Implementation bodies are left to signatures, except the WARN copy, which is fixed text.

## Review section

(The coordinator fills this in: Task 1's verdict, the red and mutation lines per task, gate results with log identity, and the issue numbers given to N1 and N2.)
