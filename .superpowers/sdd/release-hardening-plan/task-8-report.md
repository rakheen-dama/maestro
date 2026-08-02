# Task 8 report — documentation close-out

**Identity**
- pwd: `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/release-hardening`
- branch: `worktree-release-hardening`
- base HEAD: `8b090483e586bfdce86ac294113a28186c545cda`
- final HEAD: `4479aa7a35d04e1f4cb71c8b2abdfb2e10c4f7da`
- timestampUtc: `2026-08-02T22:13:56Z`

## Commits

| Commit | What |
|---|---|
| `d391a0e` | `docs/observability.md` (new) + `docs/configuration.md` observability block + `CLAUDE.md` (control-flow base, packages, namespace, "all transports") |
| `3e3a7a8` | `docs/operations.md` §10 — versioning + mixed-version playbook |
| `d9e860a` | `docs/concepts.md` two corrections + `WorkflowContext.version()` Javadoc + design §5.3 CORRECTED block |
| `31e1792` | `docs/release-notes.md` — observability, versioning, stand-down, branch-parking fix, `VERSION_MARKER` upgrade note |
| `364e705` | `docs/open-issues.md` — Issue 21 (resolved) and Issue 22 (open) |
| `4aac19b` | Stale-claims sweep — Java 25+, RabbitMQ historical framing |
| `6f4ad37` | `README.md` — Key Features + documentation table |
| `4479aa7` | `docs/observability.md` meter count (corrects `d391a0e`'s commit message — see §5) |

---

## 1. What was written

### `docs/observability.md` (new, ~460 lines)

Sections: Overview (adapter map, the replay invariant, failure containment) ·
Meter catalog (counters / timers / gauges, plus which callbacks the meter
adapter deliberately does not implement) · Tracing (span topology, attributes,
span events + RULING 8) · Cross-service trace propagation (wire contract,
durable park, degradation, parenting) · Configuration · Known limitations (4) ·
Writing your own observer · See also.

The catalog was built **from the source, not from design §2.2**. Two places
where the shipped reality differs from the design's table, both now documented
as shipped:

- `maestro.recovery.scanned` exists in addition to the spec's
  `maestro.recovery.adopted`, and both are `increment(n)` calls rather than
  `increment()`.
- The gauges are **not** registered by `MicrometerEngineObserver` at all —
  `MaestroEngineGauges` is a separate holder bean, because gauges need the
  `WorkflowExecutor` and mixing the two would create a circular
  executor→observer→executor construction dependency.

### `docs/operations.md` §10 (new section)

10.1 upgrade-all-nodes-together, with stand-down explicitly framed as the
safety net for the rolling window and *not* a licence to run mixed fleets ·
10.2 `workflow.version()` operationally (including `VERSION_MARKER` as a new
event type and the retry-composes property) · 10.3 the stand-down contract as
implemented, with the two unknown-history reasons distinguished · **10.4 the
homogeneous-fleet alarm** · 10.5 the skipped-instance-row "not found" trade.

10.4 carries Task 7 §8.4's wording: any replay `SerializationException` is now
a permanent re-adopt/stand-down loop with **no `FAILED` status**, so on a
homogeneous fleet an incompatible payload change becomes a **silent zombie**,
and a rising `maestro.standdown{reason="unknown_event_payload"}` there means
"an incompatible payload change needs `workflow.version()`", **not** "wait for
the deploy to finish" — with the explicit contrast that `unknown_event_type`
keeps the "wait for the deploy" reading, which is why they are distinct enum
constants. A four-row alert table makes it actionable.

A one-line preface marks §10 as a behaviour playbook rather than a measured
one, because the document's own preamble promises measured numbers for
§§1–8 and §10 has none.

### `docs/concepts.md`

Both Task 6 corrections, plus a pointer to operations §10. The **same wrong
sentence was also in a public API Javadoc** (`WorkflowContext.version()`,
"Parallel branches") — corrected there too, and `maxSupported`-must-be-constant
added as its own Javadoc heading. Design §5.3 carries a marked **CORRECTED**
block rather than a silent rewrite, matching this cycle's convention.

### `docs/configuration.md`, `CLAUDE.md`, `README.md`, `docs/release-notes.md`, `docs/open-issues.md`

As briefed. Release notes cover all five areas; Issue 21 (fixed this cycle)
and Issue 22 (open, `SagaManager:542/560`) are filed with index rows, the §4
narrative updated, and Issue 22 written as behaviour with a fix idiom and a
"Done when".

---

## 2. Every claim verified, and how

Everything below was read in source on this branch. Sources are cited by file
and, where a number is at stake, by line.

### Meters

| Claim | Verified against |
|---|---|
| 12 distinct metric-name literals in `MicrometerEngineObserver`; `maestro.activity.duration` is the only `Timer` ⇒ **11 counters + 1 timer** | `rg -o '"maestro\.[a-z.]+"' MicrometerEngineObserver.java \| sort -u` → 12 names; `rg -n 'increment\("maestro\|record\("maestro'` → `record(` only on `maestro.activity.duration` (L129, L138); `recoveryPass` L160-163 uses `Counter.builder(...).increment(n)` |
| Tag keys `workflow` / `activity` / `signal` / `outcome` / `reason` | `MicrometerEngineObserver.java:80-86` constants |
| `outcome` ∈ {`completed`,`failed`} on the timer; ∈ {`error`,`lost`} on `lock.renew.failures` | L129-130, L138-139; L169, L174 |
| `reason` tag closed at 3 values, exact strings | `reasonTag(...)` L182-188 switch; `StandDownReason` has exactly `UNKNOWN_EVENT_TYPE`, `UNKNOWN_EVENT_PAYLOAD`, `STALE_RUN` |
| `signal.consumed` falls back to the literal `unknown` workflow type | `UNKNOWN_WORKFLOW_TYPE = "unknown"` L86, used L147 |
| exceptionType deliberately untagged | L110 comment: *"exceptionType is intentionally not tagged — open-ended cardinality"* |
| 2 gauges, no tags, `.strongReference(true)` | `MaestroEngineGauges.java` constructor |
| `running` = `runningWorkflows.size()`; `parked` = `parkingLot.parkedCount()` = `futures.size()` | `WorkflowExecutor.java:1228-1230`, `:1242-1244`; `ParkingLot.java:401-403`, field L77 |
| running: entry added **before** `thread.start()`, removed in the run's `finally` | `WorkflowExecutor.java:1384` (with the race comment at 1382-1383), `:1388`, `:1526` |
| parked: keyed by parking key, added in `register`, removed in both parks' `finally` | `ParkingLot.java:167`, `:118`, `:149` |
| Node-local, not store-polled, sum across pods | `MaestroEngineGauges` javadoc "Why node-local gauges, not store-polling" |
| 13 callbacks implemented, 9 not, incl. `runAbandoned` and why | `@Override` census in `MicrometerEngineObserver`; the rationale is on `EngineObserver.java:162-164` |
| 22 `EngineObserver` methods total | `grep -c "default void"` → 22; 13 + 9 = 22 |
| Replay guarded by early `return` at 4 flag-carrying callbacks | L126-128, L135-137, L144-146, L153-155 |
| A recovered run emits `workflowResumed`, not `workflowStarted` | `WorkflowExecutor.java:1392-1394` (`if (replaying)`); `MicrometerEngineObserver` does not implement `workflowResumed` |
| `safely()` warns once per meter name | L205-215, `ConcurrentHashMap.newKeySet()` |

### Tracing

| Claim | Verified against |
|---|---|
| Span names `maestro.workflow.run`, `maestro.activity` — literal constants, nothing dynamic | `TracingEngineObserver.java:111-112`; no concatenation at any `spanBuilder` site |
| `maestro.signal.receive`, `Span.Kind.CONSUMER` | `KafkaTracePropagation.java:61`, L152-163 |
| The 11 attribute keys | `TracingEngineObserver.java:119-129` constants + their application sites (segment 479-486, activity 328-332, 379, 568, 582, 611, 233, 254) |
| `maestro.sequence` is a **numeric** tag | `span.tag(String, long)` at L331 |
| The four span-event names | `TracingEngineObserver.java:114-117` |
| **RULING 8** — events carry no attributes; `maestro.timer.id` / `maestro.signal.name` become segment **tags**, last-write-wins | Micrometer `Span` exposes only `event(String)` / `event(String,long,TimeUnit)`; the two keys are applied via `span.tag(...)` on the segment at L379 (`signalConsumed`) and L568 (`timerEvent`), with no per-event association. Ruling text: design §11 RULING 8 |
| `signal.persisted` never opens a segment | L358-367 ("A delivery thread is not a run segment") |
| Segments open lazily; `workflowStarted`/`workflowResumed` open no span | L197-208; RULING 6 in design §11 |
| Segments close on park/complete/fail/terminate/standDown/runAbandoned | the `closeSegment()` call sites; `runAbandoned` at L247-257 |
| Remote parent wins, previous local segment becomes a **link** | L460-476, incl. `builder.addLink(new Link(s.previousSegment))` at 463-469 and the RULING 7 comment at 449-456 |
| Branch classification by fork-point ownership, then sequence latch | `isBranchThread(...)` L513-524; `forkPoint` `InheritableThreadLocal` L182; `BRANCH_SEQUENCE_BASE = 1000` L150 |
| Branch threads never open a segment; activity spans hang off the inherited fork parent | L429-439, L318-326 |
| `maestro.service.name` is a `volatile` set from `WorkflowInfo`-bearing callbacks, and is never backfilled | field L170, `rememberServiceName` L285-287, skip at L483, no-backfill early return L441-444 |

### Kafka propagation

| Claim | Verified against |
|---|---|
| `traceparent` is the only Maestro-owned header constant | `KafkaTracePropagation.java:59` (`static final String TRACEPARENT_HEADER = "traceparent"`); `tracestate`/`baggage` appear only in `KafkaTracePropagationContractTest.java:47-52` as the pinned allowed set |
| Grammar `^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$`, byte-identical in three places | `KafkaTracePropagation.java:85-86`, `TracingEngineObserver.java:158-159`, `KafkaTracePropagationContractTest.java:51-53` |
| A grammar-valid traceparent is exactly 55 chars | the grammar itself; also stated in `V4__signal_trace_context.sql:17-19` |
| Nothing written with no active span ⇒ byte-identical pre-tracing wire format | `KafkaTracePropagation.java:107-113`; pinned by `publishWithoutActiveSpanWritesNoTraceHeaders` / `publishWithoutCollaboratorWritesNoTraceHeaders` |
| Validation at extraction exists to stop a **signal discard** | `KafkaTracePropagation.java:127` + its javadoc L63-84 (unacked record → redelivery exhaustion → dead-letter) |
| `MAX_LENGTH = 128`, defensive cap at the sole persistence site | `TraceContextHolder.java:50`; `SignalManager.java:219` |
| Column `maestro_workflow_signal.trace_context`, `VARCHAR(128)`, **nullable**, added by V4 | `V4__signal_trace_context.sql:22-23`; schema shape asserted by `MaestroMigrationsCoexistIT.v4AddsTheNullableSignalTraceContextColumn` (`character varying` / `YES` / `128`) |
| Opaque — nothing parses, branches on, indexes or joins it | the migration's own comment; `AbstractJdbcWorkflowStore.java:413/434/721` read and write it verbatim |
| `TraceContextHolder` is a `ThreadLocal<String>` in `maestro-core`, `runWith` preferred over `set` | `TraceContextHolder.java` API + its "Discipline" javadoc |

### Config / auto-configuration

| Claim | Verified against |
|---|---|
| `maestro.observability.metrics.enabled` / `.tracing.enabled`, both default `true` | `MaestroProperties.java:487-520` — `@DefaultValue("true")` and `defaults()` on both records; field `observability` at L73 under `@ConfigurationProperties("maestro")` L26. Bound by `MaestroPropertiesBindingTest.observabilityBlockBinds` |
| Exact `@ConditionalOn*` per feature | `MaestroObservabilityAutoConfiguration.java:100-115` (outer), `:117-148` (metrics), `:157-178` (tracing) |
| The tracing property gates the Kafka injection too | `KafkaMessagingAutoConfiguration.TracePropagationConfiguration` L229-245 uses the identical `prefix = "maestro.observability.tracing"` |
| The `afterName` ordering is load-bearing, not decorative | the auto-config's own javadoc L83-98 and `MaestroObservabilityAutoConfigurationTest.wiresThroughRealBootTracingAutoConfigurationChain`, which fails without those entries (independently reproduced by two reviewers this cycle) |

### Versioning / stand-down

| Claim | Verified against |
|---|---|
| Peek-don't-consume; a non-matching event ⇒ `DEFAULT_VERSION` without consuming the slot | `DefaultWorkflowOperations.version` L704-760 |
| Guard runs **before** the predates-the-change classification | L740-741 (`UnknownHistoryGuard.requireKnown` on the peeked event) |
| Two racing branches each write their own marker (the correction) | the cache read at L726 and the write at L758-759 are not atomic; each branch peeks and writes in its own partitioned block — no collision, no single "winner" |
| Sealed base permits exactly three | `MaestroControlFlowError.java` `permits ExecutorShutdownException, WorkflowTerminatedException, UnknownWorkflowHistoryException` |
| Stand-down writes nothing, runs no compensation, keeps the status, releases the lock, WARNs, emits `standDown` | `WorkflowExecutor.handleUnknownHistoryStandDown` L1672-1684 (the WARN quoted in operations.md is verbatim from L1677-1680); catch arms at L1460-1469 and L1505-1512 |
| Unmappable status ⇒ skip one row with a WARN, pass continues; the "not found" trade | `AbstractJdbcWorkflowStore.mapInstance` L620-654 (the WARN quoted in operations.md is verbatim from L649-652) |
| `retryWorkflow` returns `COMPENSATED_NOT_RETRYABLE` for a compensated saga | `WorkflowExecutor.java:832`, `:864` |
| Nothing in any main source set branches on `WAITING_*` (so the exhaustion stand-down is safe) | `rg 'WAITING_SIGNAL' --glob 'maestro-*/src/main/**/*.java'` → all hits are Javadoc/comments plus the one *write* at `SignalManager.java:338` |
| `STATUS_WRITE_ATTEMPTS = 5`, immediate retries, no backoff | `InstanceStatusWriter.java` constant + its javadoc, which also states the wide-fan-out caveat verbatim |
| Issue 22's mechanism | `SagaManager.java:540` method, guard at `:542`, swallow at `:560-561` (`logger.debug(... "continuing")`) — the guard's own Javadoc three lines above states the "TERMINATED ⇒ stop now, without compensation" contract the swallow then breaks |

### Test citations

Every test class named in the docs and this report was confirmed to exist on
this branch by path: `ObserverReplayNoDoubleCountIT`, `TracingReplayNoSpansIT`,
`TracingParallelBranchIT`, `KafkaTraceLinkageIT`,
`KafkaTracePropagationContractTest`, `TracingEngineObserverTest`,
`MicrometerEngineObserverTest`, `MaestroObservabilityAutoConfigurationTest`,
`MaestroPropertiesBindingTest`, `MaestroMigrationsCoexistIT`,
`ConcurrentBranchParkingTest`, `EnginePostgresParallelIT`.

---

## 3. Stale-claims sweep — results

### `rg -i rabbitmq` — 8 files, all inside spec §3's amended classes

| File | Class | Note |
|---|---|---|
| `docs/release-notes.md` | (a) removal entry + (b) 0.3.0 history | The 0.3.0 hits describe what shipped *then*. Accurate history. |
| `docs/release-hardening-spec.md` | (e) | The spec itself. |
| `docs/open-issues.md` | (d) | All hits are in dated issue records or the "As of this update, the allowlist is empty" narrative that §3(d) explicitly blesses. |
| `docs/test-plan.md` | (d) | **Acted on** — see below. |
| `maestro-integration-tests/SPEC.md` | (d) | Line 240 already reads "*before its removal* — the RabbitMQ…". Past tense. |
| `tasks/todo.md`, `tasks/release-readiness-plan.md`, `tasks/release-hardening-plan.md` | (d) | Task history. |

**One judgement call, acted on.** `docs/test-plan.md` was the only file where a
reader could take a hit as a current-state claim: its "Other" coverage table
has a `maestro-messaging-rabbitmq` row, and §P6/§4 mention a "RabbitMQ suite if
that backend is kept" and a "RabbitMQ parity suite" as out-of-scope. None is
literally a "Maestro supports RabbitMQ" sentence, so the invariant held as
written — but rather than rewrite a document that declares itself a historical
record, I added a **dated status banner** at the top stating the removal
outright and marking every mention below it as historical. The record is intact
and the ambiguity is gone.

### `rg "Java 21|21\+"` — 5 live hits, 4 fixed

Toolchain of record: `build-logic/src/main/kotlin/maestro.java-conventions.gradle.kts:10`
— `languageVersion = JavaLanguageVersion.of(25)`. README (badge + table) and
`CLAUDE.md` were already correct from Task 1. Fixed:

- `docs/maestro-prd.md` ×4 — pitch line `Spring Boot 4 / Java 21+` → `25+`;
  tech-stack row `21+` → `25+`; two "Java 21 virtual threads" statements →
  "Java virtual threads" (the requirement is virtual threads, not the version
  that introduced them).
- `docs/maestro-architecture.md:681` — `| Language | Java 21+ |` → `25+`.
- `maestro-core/.../WorkflowExecutor.java:49` — class Javadoc, same treatment.

Left alone: `maestro-core/.../exception/package-info.java:6` ("pattern matching
in Java 21+ `switch` expressions") — a true statement about when a *language
feature* became available, not a platform-requirement claim; and
`docs/release-hardening-spec.md:20`, which is the instruction to make this fix.

### `rg "all transports"` — 1 live hit, fixed

`CLAUDE.md:209` said the redelivery policy applies to "all transports" — true
by accident post-removal, but written when there were three. Changed to "both
transports", matching what Task 1 did in `docs/configuration.md`. Remaining
hits are the 0.3.0 release-notes section, `docs/test-plan.md`, and
`maestro-integration-tests/SPEC.md:236` ("All transports **then** in the
matrix"), all historical.

### Link check

A script resolved every relative link and heading anchor across the nine files
I touched. All resolve. The only reported misses are six pre-existing
duplicate-heading anchors in `docs/release-notes.md` (`#breaking-changes-1`
etc.), present at base HEAD `8b09048` — a limitation of my slugger, not a
broken link.

---

## 4. Contradictions found between the docs and the code

1. **`WorkflowContext.version()`'s Javadoc carried the same wrong parallel-branch
   sentence as `concepts.md` and design §5.3** — "would place the marker in
   whichever branch's sequence space won". The brief named only concepts.md and
   the design. This is a **public API Javadoc**, so it shipped the wrong mental
   model to users. Corrected, and `maxSupported`-must-be-a-code-constant added
   there too (`:maestro-core:test --rerun-tasks` 398/0/0/0 and
   `:maestro-core:javadoc` green afterwards).
2. **`CLAUDE.md`'s exceptions section described two control-flow signals**
   where the code now has a sealed base and three. Corrected, including the
   maintainer instruction: broad `catch (Throwable)` collectors should check
   `instanceof MaestroControlFlowError`, which is *why* the base exists.
3. **`CLAUDE.md`'s package listing had no `io.b2mash.maestro.core.observe`**
   (and no `io.b2mash.maestro.spring.observe`) — the cycle's largest new
   surface was invisible in the project's own orientation doc.
4. **`docs/operations.md` had no `See also` entry for observability** and
   `README.md`'s documentation table had no `operations.md` row at all — a gap
   predating this cycle. Both fixed.
5. **Design §2.2's meter table is not what shipped.** `maestro.recovery.scanned`
   is an extra meter, and the gauges live in a separate bean. Documented as
   shipped; the design was left as the historical record (this cycle's
   convention is CORRECTED blocks for wrong *claims*, not for a design the
   implementation legitimately refined under a coordinator ruling).

---

## 5. One self-inflicted error, disclosed

Commit `d391a0e`'s message says "the 12 counters/1 timer/2 gauges". There are
**11** counters — `MicrometerEngineObserver` has 12 distinct metric names, one
of which (`maestro.activity.duration`) is the Timer. The doc body carried no
count when that commit landed, so nothing user-facing was wrong; the message
was. I recounted from source and fixed it the only honest way available without
rewriting six commits: `4479aa7` states the correct count in
`docs/observability.md` itself and records the correction in its own message.

This is the cycle's recurring failure mode (a number stated without being
regenerated from the artifact) arriving in a commit message rather than a
report. The remedy that caught it — recount from `rg` output before stating any
total — is what produced the number now in the doc.

---

## 6. Verification

- `./gradlew :maestro-core:test --rerun-tasks` → `BUILD SUCCESSFUL`,
  recomputed from the JUnit XML on disk: `maestro-core tests=398 failures=0
  errors=0 skipped=0` (matches Task 7's fix-round-1 count for the module, and
  is expected to: my only source edits are Javadoc).
- `./gradlew :maestro-core:javadoc` → `BUILD SUCCESSFUL` (the two Javadoc edits
  compile as Javadoc, not just as Java).
- No other module's sources were touched, so no other module was rebuilt. The
  full `./gradlew build --rerun-tasks` remains the QA gate's job on the
  integrated tree.

## 7. Concerns / handoff

1. **Issue 22 is open and unfixed.** It is a behavioural defect with a wrong
   observable outcome (compensations on an operator-terminated workflow),
   filed with a paste-ready fix idiom. Whether it blocks the release is a
   coordinator call, not mine — I documented it as open, per the brief.
2. **`docs/test-plan.md`'s body still names RabbitMQ 12 times.** The invariant
   holds and the new banner disambiguates, but a reviewer who wants the file
   scrubbed rather than framed will disagree with my judgement call. I chose
   framing because §3(d) names that file as an allowed historical record and
   rewriting a dated record is its own kind of dishonesty.
3. **`docs/operations.md`'s "Status date: 2026-08-01"** now precedes its own §10.
   I marked §10 inline instead of moving the date, because the date belongs to
   the measured §§1–8 and moving it would imply those numbers were re-measured.
4. The `maestro.service.name` first-recovered-segment gap (limitation 3) is
   documented from a code reading — `WorkflowExecutor.java:1385-1394` emits
   `workflowResumed` after `thread.start()`, and `TracingEngineObserver.java:441-444`
   never backfills. **No test pins it**, and the codebase itself makes no such
   claim. That is an accurate reading, but it is a reading; if the QA gate wants
   it pinned, that is new test work, not a doc change.
