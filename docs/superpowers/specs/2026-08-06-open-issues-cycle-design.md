# Design — Open-issues cycle: Issues 23, 24, 22 (+16 ruling, audit findings, inherited items)

**Date:** 2026-08-06.
**Base:** `origin/main` @ `3884282`.
**Inputs:** `tasks/next-cycle-handover-prompt.md`, `docs/open-issues.md` §§1–5,
the inert-configuration audit (`tasks/audit-2026-08-05-inert-config.md` —
findings cited below as F1–F13), and user decisions recorded in this document.
**Process:** superpowers subagent-driven development in a worktree off
`origin/main`; per-task implementer + independent reviewer; final whole-branch
review; integration menu at the end.

## User decisions (all made 2026-08-05/06, recorded verbatim)

| Decision | Ruling |
|---|---|
| Issue 23 bean strategy | **Honour `spring.kafka.*` in Maestro's beans** — build Maestro's factories from Boot's `KafkaProperties` + `KafkaConnectionDetails`; engine wire-format invariants forced last. Not "stop shadowing", not "reuse Boot's beans". |
| Issue 24 measures | **All three**: document `.DLT` pre-creation; warn-only startup existence check; `maestro.messaging.redelivery.enabled` flag. |
| Issue 16 semantics | **Keep the guard.** `COMPENSATED_NOT_RETRYABLE` remains correct; document the "start a new workflow instance" operator path. No relaunch/rebase mechanism this cycle. |
| Audit findings in scope | **F3, F5, F6, F8, F9, F10 fixed this cycle.** F7 filed, not fixed. |
| Inherited items in scope | **`startRenewerIfNeeded` try-scope fix; demo jar-name hardening.** `finaliseInstance` store contract: filed, not fixed. |

## Work items

### 1. Issue 23 — Maestro's Kafka beans honour `spring.kafka.*`; `@MaestroSignalListener` propagates trace context (Critical)

Files: `maestro-messaging-kafka/.../config/KafkaMessagingAutoConfiguration.java`,
`.../listener/MaestroSignalListenerBeanPostProcessor.java`; samples/demo
workaround removal; `docs/observability.md`, `docs/configuration.md`,
`docs/release-notes.md`.

**Producer/consumer factories.** `maestroKafkaProducerFactory` and
`maestroKafkaConsumerFactory` stop hand-building their config maps from
nothing but `spring.kafka.bootstrap-servers`. Instead they start from Boot's
bound `KafkaProperties.buildProducerProperties()` /
`buildConsumerProperties()` (with `KafkaConnectionDetails` when present, so
service connections/Testcontainers work), then force the engine's wire-format
invariants **last**:

- key serializer/deserializer → String
- value serializer/deserializer → byte[]
- producer `acks=all`

Precedence rule (documented in `docs/configuration.md`): *user
`spring.kafka.*` values apply to Maestro's engine clients, except the three
invariants above, which Maestro always overrides.* This makes `ssl.*`,
`security.*`, `compression-type`, `batch-size`, `properties.*` etc. reach the
engine — today Maestro cannot talk to a secured cluster without a full bean
override (F1/F2).

`KafkaAutoConfiguration` must be declared in `afterName` so Boot's
`KafkaProperties`/`KafkaConnectionDetails` beans exist when Maestro's
auto-config evaluates (today ordering is alphabetical-accidental; F1).
Boot's own template/factories remain suppressed by type — that is now
*deliberate and documented*, not accidental, and the property surface no
longer lies.

**Template observation.** `maestroKafkaTemplate` calls
`setObservationEnabled(true)` when `spring.kafka.template.observation-enabled`
is true, **defaulting on when a `Tracer` bean is present** (same condition
`TracePropagationConfiguration` uses). Spring's sender context
removes-then-adds `traceparent`, so this replaces the engine's manual header
with a child span of the same trace — no double-write (open-issues §23 step 1).

**Listener containers.** `MaestroSignalListenerBeanPostProcessor.createListenerContainer`:
- sets `containerProps.setObservationEnabled(...)` from
  `spring.kafka.listener.observation-enabled`, defaulting on when a `Tracer`
  exists (same rule as the template);
- wraps the message listener body in
  `KafkaTracePropagation.runWithExtractedContext(record.headers(), …)` so an
  inbound `traceparent` reaches `TraceContextHolder` and is persisted by
  `SignalManager.deliverSignal` (F4).

**F3 (folded in — same file).** The raw `ctx.getBean(ConsumerFactory.class)`
at `MaestroSignalListenerBeanPostProcessor.java:136` prefers the bean named
`maestroKafkaConsumerFactory` and falls back to the type lookup, mirroring
`resolveKafkaTemplate` (`:242–247`). Pin: a context with a user-defined
additional `ConsumerFactory` + `@MaestroSignalListener` starts cleanly
(today: `NoUniqueBeanDefinitionException`).

**Workaround removal.** Delete `ObservedKafkaTemplateConfig` from all three
loan services and the demo's copy if present. In the samples'
`spring.kafka.producer/consumer.*` blocks, remove serializer and `acks`
entries (Maestro forces those regardless); keep everything else, which now
genuinely applies. Retract `docs/observability.md`'s
"one connected trace" scope-limit paragraph. Release-note entry: behaviour
change for every Maestro+Kafka user (`spring.kafka.*` now applies to engine
clients; observation defaults on with a Tracer present).

**Pins (RED-first, each run against unfixed code first):**
1. `spring.kafka.producer.compression-type` (or similar non-default) reaches
   `maestroKafkaProducerFactory`'s config — asserts the positive value, not an
   absence.
2. `spring.kafka.template.observation-enabled=true` → `traceparent` header on
   a record published through the injected template (today: absent).
3. A record delivered to an `@MaestroSignalListener` topic carrying a valid
   `traceparent` persists non-NULL `trace_context` (today: NULL).
4. Cross-service integration test: one trace id spans producer service →
   Kafka → consumer service → signal row.
5. F3 ambiguity pin as above.
6. Ordering pin: real `AutoConfigurations.of(...)` context proving Maestro's
   factories see `KafkaProperties` values (guards the `afterName`; lesson
   2026-08-03 — `.withBean(...)` hides ordering bugs, don't use it for this).

### 2. Issue 24 — `.DLT` topics: document, detect, and gate (Medium)

- **Document:** `docs/configuration.md` pre-creation checklist lists
  `<topic>.DLT` for every topic Maestro or the application consumes. Both
  compose files (`maestro-samples/sample-loan-origination/docker-compose.yml`,
  `demo/docker-compose.yml`) pre-create the `.DLT` companion for every
  consumed topic.
- **Detect:** at subscription time, a warn-only check probes topic existence
  for each subscribed topic's `.DLT` companion via a bounded `AdminClient`
  call (small timeout, e.g. 5s; failure of the probe itself logs DEBUG and
  never blocks startup — same posture as the preflight topic gate). Kafka
  transport only; the Postgres transport's `DEAD_LETTER` status needs no
  destination.
- **Gate:** new `maestro.messaging.redelivery.enabled` (default `true`).
  `false` disables retry/dead-letter wiring on **both** transports (Kafka
  `DefaultErrorHandler`/recoverer and the BPP's equivalent; Postgres
  attempts/backoff/DEAD_LETTER transitions revert to single-attempt
  fail-and-log — exact semantics pinned).

**Pins:** handler exhausting `maxAttempts` on a stack with no `.DLT` topic →
a grep-able WARN naming the missing topic (today: silent stall);
startup check warns for a missing companion, silent for a present one; flag
off → single attempt, no dead-letter publish, on both transports.
Release-note + configuration-doc entries.

### 3. Issue 22 — terminate-vs-compensation race (Medium)

`SagaManager.transitionToCompensating` (`:542` guard, `:560` swallow):
replace the `OptimisticLockException` swallow with the proven Issue-21 idiom —
**bounded retry against a fresh read, terminal guard re-evaluated inside the
loop**. `TERMINATED` observed on any attempt → throw
`WorkflowTerminatedException` (no compensation). On exhaustion: **stand down**
— leave the instance in its existing recoverable state; never proceed to
compensate against an unread row (this differs from Issue 21's exhaustion
policy, deliberately: this write transitions *into* an active phase).

**Pins:** RED pin drives a `TERMINATED` write between the guard's read and
the CAS (store fixture hook) and asserts ≥1 `COMPENSATION_STARTED`/
compensation invocation today; after the fix, the same pin asserts **zero**
compensations, `WorkflowTerminatedException` propagating, and the store write
ledger. A mutation round then proves the loop's pins are load-bearing
(revert/degrade the fix in a scratch copy; each pin must go red) — the same
rigor Issue 21's fix needed.

### 4. F8 — `maestro.enabled=false` must actually disable Maestro (Medium)

`KafkaMessagingAutoConfiguration`, `PostgresMessagingAutoConfiguration`,
`ValkeyLockAutoConfiguration`, `PostgresLockAutoConfiguration`,
`PostgresStoreAutoConfiguration`, `AdminClientAutoConfiguration`,
`MaestroHealthAutoConfiguration`, `MaestroObservabilityAutoConfiguration`:
every Maestro auto-config gains the same
`@ConditionalOnProperty(name = "maestro.enabled", matchIfMissing = true)`
class-level gate `MaestroAutoConfiguration` already has. **Pins:**
context-runner per module with `maestro.enabled=false` → no Maestro beans, no
exception, and (Valkey) no connection opened. Today: `NoSuchBeanDefinitionException:
MaestroProperties` on the messaging modules; live Valkey connections from the
lock module.

### 5. F9 — JNDI/XA DataSource ordering gap (Low)

`PostgresStoreAutoConfiguration` `afterName` adds
`org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration`
and `...XADataSourceAutoConfiguration` (FQCNs verified against
spring-boot-jdbc-4.0.5). Pin: ordering context test with a DataSource from a
later-ordered config.

### 6. F10 — admin-client honours the canonical topic property (Low)

`maestro-admin-client` resolves `maestro.messaging.topics.admin-events` as
canonical, keeping `maestro.admin.events.topic` as the deprecated alias
(WARN when both set and different — messaging wins), matching
`KafkaMessagingAutoConfiguration.resolveAdminEventsTopic`. Extract/share the
resolution rather than duplicating it if dependency direction allows;
otherwise mirror it with a cross-referencing comment and a shared pin. Pin:
context tests for each combination.

### 7. F5 — Valkey connection properties (Medium)

`ValkeyLockAutoConfiguration.resolveRedisUri`: when neither
`maestro.lock.valkey.uri` nor `spring.data.redis.url` is set, build the URI
from `spring.data.redis.host/port/password/username/database/ssl.enabled`
before falling back to `redis://localhost:6379`. Existing `url`/`uri`
precedence unchanged. Document `maestro.lock.valkey.uri` in
`docs/configuration.md`; fix the Complete Example (`:444–447`) and the
`:478–479` guidance so every property shown is one something reads. Pin:
property-combination tests asserting the exact resolved URI.

### 8. F6 — wire `maestro.retry.*` (Medium)

Thread `MaestroProperties.RetryProperties` into the default `RetryPolicy`
used by `maestroRetryExecutor` (`MaestroAutoConfiguration.java:58–62`),
defaults unchanged (3/1s/60s/2.0 — matching both the hardcoded values and the
docs). `maestro-core` stays Spring-free: the starter builds a `RetryPolicy`
from the bound values and passes it in. Pins: context test proving a
configured value reaches the executor; behavioural test
`default-max-attempts=1` → exactly one attempt, no retry.

### 9. Issue 16 — ruling recorded; operator path documented

No mechanism change. `docs/admin.md` gains an explicit "retry says
COMPENSATED_NOT_RETRYABLE → start a new workflow instance" operator path
(what to carry over, that the old instance stays as the audit record).
`docs/open-issues.md` §16 gains the ruling and the date. Release notes
untouched (no behaviour change).

### 10. Inherited — `startRenewerIfNeeded` try scope

Move `startRenewerIfNeeded()` out of the `try` block that maps a throw to
`NO_BACKEND` (same shape as the bug already fixed in
`WorkflowInstanceLockManager`; locate via `grep -rn startRenewerIfNeeded
maestro-core`). RED pin: a renewer-start failure today reports `NO_BACKEND`;
after the fix it surfaces as its own failure mode.

### 11. Inherited — demo jar-name hardening

`demo/scripts/v1-to-v2-move.sh`: resolve the jar by glob
(`build/libs/*-SNAPSHOT.jar` or equivalent single-match with a loud error on
0/2+ matches) instead of the hardcoded versioned name, so a version bump no
longer breaks the D1 deep dive. Verified by running the script's resolution
path.

### 12. Filed, not fixed (new `docs/open-issues.md` entries)

- **F7** — `maestro.worker.*` (task-queues, concurrency) documented incl. the
  minimal example but consumed by nothing; needs a product decision: implement
  concurrency limits or retract the docs.
- **`finaliseInstance`** — terminal instance-row write and terminal event
  append are two non-transactional calls; recommended
  `WorkflowStore.finaliseInstance(instance, terminalEvent)` both-or-neither
  contract. **Do not** reorder to append-then-status (worse: converging
  runners double-append and `getRecoverableInstances()` re-invokes a completed
  workflow).
- **Doc gaps** — `maestro.workflow-packages` undocumented (works);
  fold `maestro.lock.valkey.uri` doc into item 7 rather than filing.

## Definition of done (from the handover, plus this cycle's additions)

- Issues 23, 24, 22 resolved with RED-first pins and release-note entries;
  Issue 16 ruling + operator docs; F3/F5/F6/F8/F9/F10 fixed with pins;
  F7/finaliseInstance filed.
- Samples' and demo's Issue-23 workaround removed; `docs/observability.md`
  scope-limit paragraph retracted; `docs/open-issues.md` updated with
  Resolved callouts and new entries.
- Every new pin proven RED against unfixed code before the fix (lesson
  2026-08-03); no pin asserts a bare absence where a positive value is
  assertable; every quoted number greppable from an archived log.
- Full `./gradlew build` green; loan E2E
  (`maestro-samples/sample-loan-origination/e2e/run-e2e.sh`) 10/10; chaos
  PR-gate (`:maestro-integration-tests:e2eTest`) green;
  `demo/scripts/preflight.sh` passes **cold** (`down -v` first) — the demo
  depends on the exact Kafka wiring items 1–2 change.
- Branch pushed and PR opened only after the user picks from the integration
  menu (`superpowers:finishing-a-development-branch`).
