# Audit C — host-integration follow-ups (read-only)

Repo: `/Users/rakheendama/Projects/2026/maestro`, `main` @ `cbf5a2a` (clean). Nothing in the repo was modified.
Dependency versions checked against the Gradle cache: Spring Boot 4.0.5, Spring Framework (spring-beans) 7.0.6,
Spring Kafka 4.0.4, Flyway 11.14.1, HikariCP 7.0.2, pgjdbc 42.7.8.

Legend: **Traced** = followed in current source (repo or the library's source jar). **Inferred** = reasoning from
traced code plus library behaviour I did not execute. Nothing was run (no Gradle, no containers).

---

## Summary table

| Item | Verdict | Fix in a phrase | Size |
|---|---|---|---|
| 3.4 Kafka beans suppress Boot's | CONFIRMED (worse than stated) | `@Bean(defaultCandidate=false)` on the 3 beans + `@Qualifier` at 3 internal by-type sites + samples + dead admin-client bean | M |
| 3.11a retention back-to-back batches | CONFIRMED, actually up to 300 (100 per status x 3) | pause between batches (duty-cycle sleep = last batch's duration) | S |
| 3.11b janitor DELETE unbounded | CONFIRMED (+ no leader, no service filter) | batched DELETE via `id IN (SELECT ... LIMIT ?)` loop | S |
| 3.11c admin events default on | CONFIRMED (+ required topic at startup) | flip `maestro.admin.events.enabled` default to `false` (decision) or DOC | S |
| 3.11d DDL as app user by default | CONFIRMED (same as Boot's Flyway default) | DOC-ONLY production guidance; optional separate schema credentials | S |
| Issue 38 wake-listener shutdown WARN | CONFIRMED by trace (not reproduced) | do not interrupt a connected poll thread; join, then UNLISTEN | S |
| Issue 39 stranded under other service_name | CONFIRMED | one advisory startup query + WARN per foreign name | S-M |
| 4.2-1 no `runAbandoned` meter; retries DEBUG | CONFIRMED | FILE+FIX: `maestro.run.abandoned{reason}` counter | S |
| 4.2-2 SQLSTATE not from getNextException | PARTIALLY (log-only; no batch code) | DROP | — |
| 4.2-3 workflow ops from activity body unguarded | CONFIRMED | DOC-ONLY (pitfalls rule) | S |
| 4.2-4 `outOfOrder` comment incomplete | CONFIRMED (Flyway source traced) | FILE+FIX: comment + V200–V202 history test | S |
| 4.2-5 untested defensive branches | CONFIRMED (by grep, not coverage) | FILE+FIX: two tests | S |
| 4.2-6 decision reads throw from catch arm | CONFIRMED but harmless | DROP | — |
| 4.2-7 cosmetics (3) | CONFIRMED all three | DOC-ONLY / one-line message fix | S |
| 4.3 no N-1 → N upgrade test | PARTIALLY (schema-level one exists, data/engine-level none) | frozen-fixture schema test (S) then seeded-history replay test (M) | S + M |
| 7 doc drift | PARTIALLY (only open-issues §1–§2 and two PRD lines are un-annotated) | edit open-issues §1/§2, PRD :36/:549, release-notes :277 | S |
| 7 `actions/*@v4` | CONFIRMED; 17 `actions/*` pins, latest majors are v5–v7 | bump after reading each changelog | S |
| 7 todo.md unticked | CONFIRMED, plus 17 more unticked lines | tick/annotate | S |

---

## 3.4 — Maestro's Kafka beans suppress Spring Boot's

### (a) Verdict: CONFIRMED (traced)

- `KafkaMessagingAutoConfiguration` (`maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/config/KafkaMessagingAutoConfiguration.java`)
  is `@AutoConfiguration(beforeName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")` (:115) and
  registers `maestroKafkaProducerFactory` (:137-160), `maestroKafkaTemplate` (:162-173), `maestroKafkaConsumerFactory`
  (:197-227). They are ordinary default candidates.
- Boot 4.0.5 `KafkaAutoConfiguration` (source jar): `kafkaTemplate` is `@ConditionalOnMissingBean(KafkaTemplate.class)`
  and declared `KafkaTemplate<?, ?>`; `kafkaConsumerFactory` is `@ConditionalOnMissingBean(ConsumerFactory.class)`;
  `kafkaProducerFactory` is `@ConditionalOnMissingBean(ProducerFactory.class)`. All three back off.
- Pinned deliberately by `KafkaMessagingAutoConfigurationPropertiesTest.bootsOwnTemplateStaysSuppressed_deliberately`
  (`maestro-messaging-kafka/src/test/.../config/KafkaMessagingAutoConfigurationPropertiesTest.java:144-151`) and
  documented at `docs/configuration.md:954-1003`.

### (b) Harm to a host (what it sees today)

Traced through spring-beans 7.0.6 `DefaultListableBeanFactory.findAutowireCandidates` (:1960-2000) and
`GenericTypeAwareAutowireCandidateResolver` (:148-158):

1. Injecting `KafkaTemplate<String, MyDto>` (or `<String, String>`) fails startup with `NoSuchBeanDefinitionException`:
   the only candidate is `KafkaTemplate<String, byte[]>`, fully resolved, so neither the strict pass nor the fallback
   pass (which only relaxes *unresolvable* generics) matches. In a Maestro-free Boot app the same injection point
   works, because Boot's bean is `KafkaTemplate<?, ?>` and the fallback pass accepts it. (Traced; the
   `maestro-admin` app relies on exactly this fallback: `AdminCommandService` and `AdminEventConsumer` inject
   `KafkaTemplate<String, byte[]>`/`ConsumerFactory<String, byte[]>` and receive Boot's wildcard beans.)
2. Injecting raw `KafkaTemplate`, `KafkaTemplate<?, ?>` or `KafkaOperations` silently gets Maestro's
   `byte[]` template; sending a non-`byte[]` value fails at runtime with a `ClassCastException` in
   `ByteArraySerializer`. (Inferred.)
3. `spring.kafka.template.*`, `ProducerListener`, `RecordMessageConverter` beans and `DefaultKafkaProducerFactoryCustomizer`s
   never reach any template (Issue 23 lesson; documented).
4. **Doc claim likely wrong:** `docs/configuration.md:998-1001` says "Boot's `kafkaTransactionManager` never exists in a
   Maestro context". Boot's `kafkaTransactionManager` is `@ConditionalOnProperty(spring.kafka.producer.transaction-id-prefix)`
   and takes `ProducerFactory<?, ?>`, which Maestro's factory satisfies; Spring Kafka 4.0.4
   `KafkaTransactionManager(ProducerFactory)` asserts `producerFactory.transactionCapable()` (:92), and Maestro's factory
   is not transactional (Boot's `Producer.buildProperties()` does not carry the prefix). So a host that sets that
   property should get a **startup failure**, not a silently missing bean. (Inferred from source; not run.)

### (c) Fix: `defaultCandidate = false` on Maestro's three beans

Why it works (traced): Boot 4.0.5 `OnBeanCondition.isCandidate` (:280-307) ignores bean definitions that are not
`isAutowireCandidate() && isDefaultCandidate()`. With Maestro's three beans marked `@Bean(defaultCandidate = false)`,
all of Boot's Kafka beans register normally: `kafkaTemplate`, `kafkaProducerFactory`, `kafkaConsumerFactory`,
`kafkaProducerListener`, `kafkaTransactionManager` (now over Boot's factory), the retry-topic
`@ConditionalOnSingleCandidate(KafkaTemplate)`, and `kafkaListenerContainerFactory` gets Boot's consumer factory.
The host sees a plain Boot Kafka setup, plus three Maestro beans invisible to by-type injection.

**What inside Maestro injects these beans by type and must get a qualifier.** spring-beans 7.0.6
`QualifierAnnotationAutowireCandidateResolver.isAutowireCandidate` (:149-168): with no qualifier annotation at the
injection point, a non-default-candidate bean is excluded. The step-3 name shortcut
(`DefaultListableBeanFactory` :1689-1706) also calls `isAutowireCandidate`, so **a matching parameter name does
not rescue it**. Worse, once Boot's beans exist the fallback pass will **silently inject Boot's wildcard bean** into
these `<String, byte[]>` injection points (same mechanism as the admin app). Boot's default value serializer is
`StringSerializer`, so this would turn into `ClassCastException` on every engine publish, or into wrong
`acks`/serializers. Every site below must carry `@Qualifier("<bean name>")`:

| Site | Today | Without a qualifier after the fix |
|---|---|---|
| `KafkaMessagingAutoConfiguration.maestroKafkaTemplate(ProducerFactory<String, byte[]> maestroKafkaProducerFactory, ...)` :164-165 | Maestro's factory | Boot's `kafkaProducerFactory` (fallback) |
| `KafkaMessagingAutoConfiguration.kafkaWorkflowMessaging(KafkaTemplate<String, byte[]> maestroKafkaTemplate, ConsumerFactory<String, byte[]> maestroKafkaConsumerFactory, ...)` :321-323 | Maestro's | Boot's `kafkaTemplate` + `kafkaConsumerFactory` (fallback) |
| `AdminClientAutoConfiguration.adminEventPublisher(KafkaTemplate<String, byte[]> kafkaTemplate, ...)` (`maestro-admin-client/.../AdminClientAutoConfiguration.java:69-76`) | Maestro's | Boot's (fallback) |
| `MaestroSignalListenerBeanPostProcessor.resolveKafkaTemplate`/`resolveConsumerFactory` (`.../listener/MaestroSignalListenerBeanPostProcessor.java:379-400`) | by name first | unaffected (`getBean(name)` ignores candidacy); the by-type fallback (:383, :400) is dead code while the name-conditioned beans always exist — delete it or keep |
| `KafkaTopicVerifier`, `KafkaSignalIdentity` | handed the factory | unaffected |

Surprise: **`AdminEventPublisher` is a dead bean.** Nothing in main code calls it (grep across all modules); lifecycle
events go `GatedWorkflowMessaging` → `KafkaWorkflowMessaging.publishLifecycleEvent` → `maestroKafkaTemplate`. Simplest:
delete `AdminClientAutoConfiguration`'s bean (or the class) rather than qualifying it. A `@Qualifier("maestroKafkaTemplate")`
would also break the `maestro-admin` app, which includes admin-client but has no `maestroKafkaTemplate`.

**Host-visible behaviour change (the real risk).** `docs/configuration.md:971-973` tells hosts to "inject and reuse
`maestroKafkaTemplate`" for `byte[]` traffic. A host doing that by type will, after the fix, silently receive Boot's
template. The repo's own samples do exactly this and would break the same way:
`sample-order-service/.../KafkaMessagingActivities.java:30`, `sample-payment-gateway/.../KafkaPaymentMessagingActivities.java:36`,
`underwriting-service/.../KafkaDecisionMessagingActivities.java:37`, `verification-gateway-service/.../KafkaVerificationMessagingActivities.java:40`,
`loan-application-service/.../KafkaLoanMessagingActivities.java:38`. No sample yml sets a producer
`value-serializer`, so they would throw `ClassCastException` (byte[] → String) at send (inferred). Each needs
`@Qualifier("maestroKafkaTemplate")`, and the release note must tell hosts to do the same.

Other consequences:
- The `beforeName = KafkaAutoConfiguration` ordering and its comment (:102-115) become unnecessary; rewrite the comment
  (the "NoUniqueBeanDefinitionException" rationale no longer applies). Keep `afterName` for tracing.
- `spring.kafka.producer.*` now feeds two producer factories, Boot's and Maestro's (Maestro still forces its invariants).
  Documented precedence stays true.
- A user who overrides `maestroKafkaTemplate` by name with a plain `@Bean` makes it a default candidate again, so Boot backs
  off as today. Document: override with `@Bean(defaultCandidate = false)`.
- `docs/configuration.md:954-1003` rewrite: drop "deliberately suppressed", drop the transaction paragraph, and replace
  "inject by type" with "inject with `@Qualifier`".

Tests:
1. Flip `bootsOwnTemplateStaysSuppressed_deliberately` → Boot's `kafkaTemplate`/`kafkaProducerFactory`/`kafkaConsumerFactory` exist.
2. A context test with real `AutoConfigurations.of(KafkaAutoConfiguration, KafkaMessagingAutoConfiguration, ...)` (no `.withBean`,
   per lessons.md) plus a user bean that injects `KafkaTemplate<String, MyDto>`: the context starts.
3. **Identity pin.** `KafkaWorkflowMessaging`'s template is `maestroKafkaTemplate` (same instance) and its producer config has
   `ByteArraySerializer`/`acks=all`. Mutation check: remove one qualifier and confirm the test fails. This is the pin that
   catches the silent fallback.
4. With `spring.kafka.producer.transaction-id-prefix` set, `kafkaTransactionManager` exists and is built over Boot's
   factory. It is RED against today's code if the inference in (b)4 holds.
5. `tasks/todo.md:343` (A6, plain `@KafkaListener` boundary pin) fits naturally here.

Config/API surface: no new properties. Behaviour change for anyone injecting Maestro's Kafka beans by type.
Release-notes entry required.

### (d) Size: M. Shares files with 3.11c (`AdminClientAutoConfiguration`, `docs/configuration.md` Kafka/admin sections) and with the samples.

---

## 3.11 — smaller host-load items

### 3.11a Retention sweeper back-to-back batches

(a) **CONFIRMED, and worse than stated** (traced). `RetentionSweeper.sweepOnce`
(`maestro-core/src/main/java/io/b2mash/maestro/core/engine/RetentionSweeper.java:198-234`) loops the three targets
COMPLETED/TERMINATED/FAILED (:239-244), and for **each** target runs up to `maxBatchesPerSweep` (default 100) batches
with no pause. Each batch is one `trySetLeader` write plus one purge transaction
(`AbstractJdbcWorkflowStore.purgeTerminalInstancesBefore` :784-846, which deletes signals, timers, events and instances).
Worst case: 300 batches x 500 instances, every instance with all its events, run back to back. `docs/configuration.md:700`
correctly says "per status".

(b) Harm: a large first sweep (after an upgrade, or after retention was off) or a high-throughput service produces a
continuous stream of large DELETE transactions. Effects: WAL and replication-lag bursts, autovacuum pressure, and one
pool connection held almost continuously. The workflow traffic itself is unaffected. Severity: low to medium.

(c) Fix (simplest, no new property): after each full batch, sleep for as long as that batch took (a 50 % duty cycle),
capped (e.g. at 1 s). Respect `stopRequested` and the interrupt the way `sweepLoop` already does. An alternative is a
`maestro.retention.batch-pause` property (more surface). Files: `RetentionSweeper.java` (+ an injectable sleeper/clock
seam), `RetentionSweeperTest`, `docs/configuration.md` retention table. Risk: a long sweep stretches; at the defaults
300 x ~1 s fits easily within the 1 h interval. Test: a fake store that reports full batches shows a pause recorded
between batches and none after a short batch; `stop()` during the pause exits promptly.

(d) S. Shares `docs/configuration.md` retention section with 3.11b.

### 3.11b Signal janitor `DELETE` has no limit

(a) **CONFIRMED** (traced). `AbstractJdbcWorkflowStore.deleteUnconsumedSignalsForTerminalInstancesBefore`
(`maestro-store-jdbc/.../AbstractJdbcWorkflowStore.java:1254-1265`) is a single
`DELETE ... WHERE consumed = FALSE AND workflow_instance_id IN (SELECT id ... WHERE status IN ('COMPLETED','TERMINATED') AND completed_at < ?)`,
with no LIMIT. Two additional facts:
- `SignalJanitor` (`maestro-core/.../SignalJanitor.java:146-158`) has **no leader election**: every node of every
  service runs the same statement every hour (default).
- The statement has **no `service_name` filter**: in shared tables (Issue 31) one service's janitor applies its own
  retention to other services' signals.

(b) Harm: low in steady state, because after the first sweep each run deletes about one hour's increment. The first
sweep on a database with a large backlog of unconsumed signals for finished workflows is one long transaction. N nodes
racing on the same rows wait on each other's row locks. Supporting indexes exist (V8 `idx_wf_instance_terminal`,
`idx_wf_signal_instance`).

(c) Fix: batch it with `DELETE ... WHERE id IN (SELECT s.id FROM maestro_workflow_signal s JOIN maestro_workflow_instance i ON ... LIMIT ?)`,
looping until a short batch, with a cap per sweep (reuse `maestro.retention.batch-size`/`max-batches-per-sweep`).
SPI: add `default int deleteUnconsumedSignalsForTerminalInstancesBefore(Instant cutoff, int limit)` to `WorkflowStore`
(default delegates to the unbounded one, so `InMemoryWorkflowStore` and third-party stores keep working). Optionally
run it under the retention leader key. Files: `WorkflowStore`, `AbstractJdbcWorkflowStore`, `SignalJanitor`,
`SignalJanitorTest`, `PostgresWorkflowStoreTest`. Risk: minimal.

(d) S. Shares `WorkflowStore`/`AbstractJdbcWorkflowStore` with Issue 39 (new SPI method) and handover 3.3.

### 3.11c Admin events on by default

(a) **CONFIRMED** (traced). `MaestroProperties.EventsProperties` `@DefaultValue("true") boolean enabled`
(`maestro-spring-boot-starter/.../MaestroProperties.java:582-584`). Every live activity publishes `ACTIVITY_STARTED`
(`ActivityInvocationHandler.java:666`) and `ACTIVITY_COMPLETED`/`ACTIVITY_FAILED` (:723/:699). Workflow, signal, timer
and compensation events are on top of that. Publishing is off-thread and bounded (`LifecycleEventSink`), so it does not
block workflows. Also, **the topic is required at startup**: `docs/configuration.md:300` lists `maestro.admin.events`
under `verify-on-startup=fail` (the default) whenever events are enabled.

(b) Harm: a host that does not run the (unauthenticated, unpublished) dashboard pays two producer sends per activity
and broker bytes. On first start it also **fails** until it creates `maestro.admin.events` or disables events. This is
friction rather than a correctness problem.

(c) Options:
- (i) Flip the default to `false`. Dashboard users set `maestro.admin.events.enabled=true`. Pre-1.0, this keeps the
  optional component opt-in and removes one required topic. This is a product decision and needs a release note,
  because upgraded dashboard users see an empty dashboard otherwise.
- (ii) DOC-ONLY: state the cost and the required topic prominently in getting-started.

Files for (i): `MaestroProperties.java`, `docs/configuration.md:770-778` & :300, the samples' yml (they use the dashboard
in demos), and admin E2E configs. Test: the starter's properties-binding test.

(d) S. Shares `AdminClientAutoConfiguration`/docs with 3.4.

### 3.11d Default schema mode runs DDL as the app's DB user

(a) **CONFIRMED** (traced). `PostgresStoreAutoConfiguration.maestroSchemaInitializer`
(`maestro-store-postgres/.../config/PostgresStoreAutoConfiguration.java:116`) defaults the mode to `"flyway"` and runs
Flyway over the same `DataSource` the store uses (`MaestroSchemaInitializer.java:143-160`). There is no separate
migration credential (Boot's own Flyway has `spring.flyway.user/password`; Maestro has no equivalent).
`docs/configuration.md:75` already explains ownership for `validate`/`none`.

(b) Harm: least-privilege. The application's runtime role needs CREATE/ownership on the schema, and index migrations
take `SHARE` locks during the deploy (noted for V101 in release notes). This matches Boot's own Flyway default, so it is
not surprising to Spring users, but it is a security-review item.

(c) DOC-ONLY: a "Production" paragraph in `docs/configuration.md` § Schema management recommending `validate` plus
migrations run by the owning role (the content exists at :75; it needs a headline recommendation). Optional later:
`maestro.store.schema.user/password` (or a `@MaestroSchemaDataSource`) mirroring `spring.flyway.user`. Size S-M if built.
Changing the default to `validate` would break every first start, so it is not recommended.

(d) S (doc). Shares `docs/configuration.md` § Schema management with 4.2-4 and 4.3.

---

## Issue 38 — `PostgresWakeListener.close()` WARN with stack trace

(a) **CONFIRMED by trace; not reproduced.**
`maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/notify/PostgresWakeListener.java`:
- `close()` sets `running=false`, then calls `thread.interrupt()` (:153). The poll thread is a **virtual** thread (:103)
  blocked in `pg.getNotifications(500)` (:197) on the *unwrapped* `PGConnection`. Inferred: interrupting a virtual
  thread blocked in socket I/O closes the socket (JDK virtual-thread semantics), so pgjdbc throws an I/O
  `PSQLException`. The loop swallows it because `running` is false (:203-208) and exits.
- `close()` then runs `UNLISTEN *` through the **pool proxy** connection (:160-166). pgjdbc throws an `08xxx`
  ("connection has been closed"/I/O error). The proxy's `checkException` (HikariCP 7.0.2
  `ProxyConnection.checkException` :151-185) treats any SQLSTATE starting with `08` as broken and logs
  `LOGGER.warn("{} - Connection {} marked as broken because of SQLSTATE({}), ErrorCode({})", ..., exception)`, **with
  the exception, hence the stack trace**. Maestro's own log for it is DEBUG (:165). Emitting logger:
  `com.zaxxer.hikari.pool.ProxyConnection` (traced in Hikari source; the pgjdbc state code is inferred).

(b) Harm: a WARN with a stack trace on every clean shutdown when the host uses Hikari, Boot's default pool. It teaches
operators to ignore WARNs. Nothing is lost.

(c) Fix: in `close()`, set `running=false` and **interrupt only when no LISTEN session is open** (`pgConnection == null`
means the thread is in its backoff `sleep`/`connect`, where an interrupt closes nothing). `connect()` already refuses
once `running` is false (:237-240). Otherwise, let the loop leave within one `POLL_TIMEOUT_MS` (500 ms, inside the
existing 2 s join), then `UNLISTEN *` on a live connection and return it to the pool clean. If the join times out,
skip the UNLISTEN (the thread may still be using the connection) and close. File: `PostgresWakeListener.java` only.
Risk: shutdown takes up to 500 ms longer for this bean. Test (in
`maestro-store-postgres/src/test/.../notify/`): a HikariCP-backed listener (Hikari arrives via spring-boot-starter-jdbc;
add a test dependency if it is not on the module's test classpath; current tests use `PGSimpleDataSource`,
`PostgresNotifyTestSupport.java:62`). Start the listener, close it, and assert a logback `ListAppender` on
`com.zaxxer.hikari` saw no WARN. Then borrow the pool's connection and assert `SELECT pg_listening_channels()` is
empty. Run this test against the unfixed code first: it must be RED.

(d) S. Isolated file.

---

## Issue 39 — workflows under another `service_name` stranded silently

(a) **CONFIRMED** (traced). Recovery reads only `service_name = ?` (`AbstractJdbcWorkflowStore.getRecoverableInstances`
:338-359; `WorkflowExecutor.recoveryPass` :887+). No code anywhere looks for active rows under other names (grep for
strand/other-service: nothing). `StartupRecoveryRunner.run` (`maestro-spring-boot-starter/.../StartupRecoveryRunner.java:41-83`)
logs only the adopted count. The release notes' only mitigation is a manual SQL (`docs/release-notes.md:19-28`).

(b) Harm: medium. A deployment that named replicas individually, or renamed the service, leaves workflows that never
resume and nothing reports it. This is decision D1's sharp edge, on the default path.

(c) Fix:
- New `WorkflowStore` default method `Map<String, Long> countActiveInstancesUnderOtherServices(String serviceName, Set<String> workflowTypes, int limit)`
  (default: empty map, meaning "unknown").
- JDBC: `SELECT service_name, count(*) FROM maestro_workflow_instance WHERE status IN (<active>) AND service_name <> ? AND workflow_type = ANY(?) GROUP BY service_name ORDER BY 2 DESC LIMIT ?`.
  This scans only active rows (the V2 partial index on status helps).
- Call it once from `StartupRecoveryRunner` after `recoverWorkflows`, catch-all, never failing startup. Log one WARN
  per name: count plus remedy ("run a node with `maestro.service-name=<x>` or correct the rows").

Caveat: in shared tables (Issue 31), a *live* service that registers the same workflow type name would be a false
positive. Either phrase the WARN conditionally ("if no node runs under that name"), or suppress names that currently hold
an unexpired `maestro:leader:timer-poller:<name>` row in `maestro_leader_election`. The second is stronger, but it
couples store and lock tables.

Files: `WorkflowStore`, `AbstractJdbcWorkflowStore`, `InMemoryWorkflowStore` (maestro-test), `StartupRecoveryRunner` (or
a `WorkflowExecutor` method so non-Spring users get it), tests: a seeded JDBC test (WARN names the foreign name and count;
none when clean) and a starter unit test with a `ListAppender`. No config surface.

(d) S-M. Shares the SPI and JDBC store with 3.11b. It touches `StartupRecoveryRunner`, which also starts the janitor
and sweeper.

---

## §4.2 bullets

### 4.2-1 No meter for `runAbandoned` by reason; store-guard retries at DEBUG — CONFIRMED → **FILE+FIX (S)**
- `MicrometerEngineObserver` has no `runAbandoned` override (methods at :104-225; only `TracingEngineObserver:249`
  implements it). The omission is deliberate and documented (`docs/observability.md:180-188`, "counting it would
  double-count" — true only for `TERMINATED`). Today `STORE_UNAVAILABLE`/`NODE_ERROR` show up only as a span attribute
  and as logs.
- `StoreFaultGuard` in-place retries log at DEBUG (`StoreFaultGuard.java:268`); the final latch is WARN (:299).
- Harm: no metric to alert on a sustained `STORE_UNAVAILABLE` or `NODE_ERROR` rate.
- Fix: counter `maestro.run.abandoned{reason}`, either excluding `TERMINATED` or documenting that it overlaps
  `maestro.workflow.terminated`. Update the meter catalog (`MicrometerEngineObserver` Javadoc table :44 area,
  `docs/observability.md`) and add a `MicrometerEngineObserverTest` case. Leave retries at DEBUG: the meter is the
  signal.

### 4.2-2 SQLSTATE from first SQLException in the chain, not `getNextException` — PARTIALLY → **DROP**
- This is about `StoreFaultGuard.diagnosis` (`StoreFaultGuard.java:206-217`), i.e. **log text only**. The
  outage-vs-data classifier is `AbstractJdbcWorkflowStore.storeFault` (:1771-1778). It reads only the caught exception's
  own `getSQLState()`: no cause chain and no next-exception.
- `getNextException` matters for `BatchUpdateException`, and there is **no `executeBatch`/`addBatch` anywhere** in
  `maestro-store-jdbc` or `maestro-store-postgres` main. pgjdbc sets the state on the thrown `PSQLException` for single
  statements. No reachable harm. Revisit only if batching is introduced (put a comment at `storeFault`).

### 4.2-3 Workflow operations from inside an activity body run unguarded — CONFIRMED → **DOC-ONLY (S)**
- The activity body runs inside `StoreFaultGuard.passThrough` with the run's `WorkflowContext` re-bound on the attempt
  thread (`ActivityInvocationHandler.java:771-778`). Under pass-through the guard calls the delegate directly
  (`StoreFaultGuard.java:227-228`), so any `sleep`/`awaitSignal`/activity-stub/`sideEffect` call made from an activity
  body bypasses retries, latch and append-refusal.
- This usage is broken in other ways too: the workflow thread is blocked in `get()`.
- Add a rule to `docs/pitfalls.md` ("never call workflow operations from inside an activity").
- Optional later: a fail-fast `IllegalStateException` in `DefaultWorkflowOperations` when `PASS_THROUGH` is bound.

### 4.2-4 `MaestroSchemaInitializer`'s `outOfOrder` comment — CONFIRMED → **FILE+FIX (S)**
- The comment says the option "matters only for that history" (`MaestroSchemaInitializer.java:68-69`) and that new
  migrations are "above every applied version" (:157-158). CLAUDE.md repeats the same narrower claim.
- Traced in Flyway 11.14.1: `MigrationInfoServiceImpl.updateContextFromAppliedVersionedMigrations` (:321-334) sets
  `lastApplied` to the highest applied versioned migration, **including future ones** (V200–V202 that this build does
  not resolve). `ResolvedMigration.getState` (:82-84) returns `IGNORED` for a resolved migration below `lastApplied`
  unless `outOfOrder`, and validate then rejects it ("Detected resolved migration not applied").
- So on a database holding the retired V200–V202 rows, **V101 and every future V102–V199 apply only because of
  `outOfOrder(true)`**. Removing the option would break those databases.
- No test covers that history: `MaestroSchemaInitializerTest` has no 200-band case (grep).
- Fix: rewrite both comments and the CLAUDE.md line. Add a test: migrate the store band + V100, insert history rows for
  200–202 (plus the orphan `maestro_signal_queue`), run the current initializer in FLYWAY then VALIDATE, and assert V101
  is recorded. Mutation check: the test must fail with `.outOfOrder(false)`.

### 4.2-5 No test reaches the executor's `catch (Error)` latch branch or `SagaManager.executeParallel`'s store-fault pass — CONFIRMED (by grep, no coverage run) → **FILE+FIX (S, tests only)**
- Branches: `WorkflowExecutor.java:2521-2543`, specifically the `storeUnavailableLatched` arm at :2537-2540;
  `SagaManager.java:744-760` (StoreUnavailable rethrow and the `throwIfStoreUnavailable` R2 pass).
- `StoreFaultStandDownTest` covers workflow `parallel()` branches (:470-510) and a compensation append fault (:421-468),
  but not parallel *compensation* (`executeParallel`). No core test throws a non-node `Error` after a latch.
- Add two tests to `StoreFaultStandDownTest`:
  - Workflow code swallows the latched `StoreUnavailableException` and throws `AssertionError`: expect a
    `STORE_UNAVAILABLE` abandon, not FAILED.
  - A parallel saga whose branch hits the faulty store: expect the run to stay COMPENSATING with no
    `COMPENSATION_STEP_FAILED`.

### 4.2-6 Decision reads during an outage still throw out of their catch arm — CONFIRMED, harmless → **DROP**
- The one site I traced: `ActivityInvocationHandler.java:928-948`. In the `DuplicateEventException` arm,
  `store.getEventBySequence` (guarded) runs before `requestStale`. In an outage it throws `StoreUnavailableException`
  (and latches), so the run abandons as `STORE_UNAVAILABLE` instead of standing down as `STALE_RUN`.
- Both outcomes write nothing and leave the instance recoverable; only the reported reason differs. The log-only reads
  were already made advisory (`WorkflowExecutor.statusForLog` :2641-2650, commit `3692cc0`). Not worth an issue.

### 4.2-7 Cosmetics — all CONFIRMED → **DOC-ONLY (S)**
- `MaestroDataSources.resolve` message (`maestro-store-postgres/.../config/MaestroDataSources.java`, the
  `NoUniqueBeanDefinitionException` arm) says "Keep one, or mark the one Maestro should use @Primary." and omits the
  `maestroDataSource`-name tie-breaker that its own Javadoc describes. The message is quoted verbatim in
  `docs/configuration.md` (~:190-198) and `docs/release-notes.md:12`, so all three must change together.
- Pool sizing uses `max(default-concurrency, default-activity-concurrency) + 5` (`docs/configuration.md:206-208`,
  `docs/operations.md:1195-1196`, `docs/release-notes.md:12`) without calling itself a heuristic. Parallel branches and
  activity bodies that use the same pool can exceed it; the sum is the safe upper bound. Say "heuristic", or give the
  sum.
- `docs/release-notes.md:277` begins "**Now:**" and describes the retired bands (store 1–99, lock 100–199, messaging
  200–299) as current. Change "Now:" to "At the time:", or add a superseded note.

---

## §4.3 — no N-1 → N upgrade test

(a) **PARTIALLY.** A schema-level forward-and-back test already exists:
`MaestroSchemaInitializerTest.anOlderBuildStartsAgainstADatabaseThatAlreadyRecordedV101`
(`maestro-store-postgres/src/test/.../schema/MaestroSchemaInitializerTest.java:303-368`). It migrates with a27a275's
migration set and Flyway settings (:340-347), runs the current initializer (:351), then restarts the old Flyway (:357-367).
Gaps:
1. It copies a27a275's SQL **from the current classpath** (:332-337). An edit to an already-shipped migration file
   would be invisible to it, and to every other test, since all start from current resources.
2. It is schema-only: no rows written by an older build are read, recovered or replayed by current code. That is where
   payload/serialization drift and column-default assumptions (NULL `args_hash`, `lease_epoch` 0, NULL `message_id`)
   would bite.
3. No case for retired V200–V202 history (4.2-4) or the dev-only V10 row (handover §7).

(c) Smallest useful tests, needing no published release:
- **S — frozen schema fixture.** Commit `maestro-store-postgres/src/test/resources/upgrade/a27a275/{store,lock}/*.sql`
  extracted once with `git show a27a275:<path>` (a script in `scripts/` records the ref). The test applies them with the
  old config, runs the current initializer FLYWAY and then VALIDATE, and asserts every shipped version is recorded and
  no checksum mismatch occurs. Because the fixture is frozen, a later edit to a shipped V1–V101 file makes VALIDATE fail
  on checksum. That catches a whole class of mistakes that nothing catches today. Add the V200–V202 variant (4.2-4) in the same class.
- **M — seeded-history replay** (in `maestro-integration-tests`, which already depends on every module). Seed a frozen
  data-only dump (`pg_dump --data-only -t 'maestro_*'`) captured once by running a tiny fixture workflow at the pinned
  ref to a parked state (`WAITING_SIGNAL`, with completed activities and a PENDING timer). Use a capture script against
  a `git worktree` of the ref, so the dump really is the old build's serialization. The test loads old schema + dump,
  runs the current initializer, starts a current executor on the real Postgres store under the same service name with
  the same frozen fixture workflow class, delivers the signal, and asserts: COMPLETED, pre-park activities **not**
  re-invoked (invocation counters), and no stand-down. The fixture workflow class must never change; version it per
  ref.

Risks: the fixture must be regenerated deliberately at each release (it belongs in the release checklist). The
seeded test is only as good as the fixture workflow's coverage of event types.

(d) S + M. Shares `MaestroSchemaInitializerTest` with 4.2-4.

---

## §7 — housekeeping

### Doc drift (Valkey / RabbitMQ / Postgres transport presented as current)

**`docs/open-issues.md`: genuinely un-annotated, current-tense drift:**
- :5-19 header status dates. No retired-tech claim, but :5 "Status date 2026-07-28".
- **:33-36**: "using infrastructure you already run: Postgres, Kafka, and Valkey/Redis."
- **:67-73** SPI table: `WorkflowMessaging` → "Kafka, Postgres"; `DistributedLock` → "Valkey, Postgres".
- **:80-95** module map: `maestro-store-postgres ... (version band 1-99)` (:86), `maestro-messaging-postgres` (:88),
  `maestro-lock-valkey` (:89), `maestro-lock-postgres ... (band 100-199)` (:90). All three modules are gone and the bands
  are retired.
- **:99-114** §2: "`maestro-lock-postgres` and `maestro-messaging-postgres` gained their first real suites" (:107-108);
  "As of this update the allowlist is empty: `maestro-messaging-rabbitmq`, ..." (:112-114); ":105 65 tests" is stale.
- :267-268: Kind definitions use `maestro-messaging-postgres` as an example (past tense, acceptable).
- Everything else that matches (:303, :327-398, :701, :773, :792-832, :996-998, :1115-1136, :2596-2657, :2899-2986,
  :3211, :3256) is inside resolved issue sections, explicitly annotated ("Note 2026-11-04", "historical"), or in §6
  under "Historical note" (:3389, RabbitMQ at :3402-3403). It is history, not drift.

**`docs/maestro-prd.md`:** has a scope note at **:38-44** saying Valkey/Postgres-transport mentions are history, and
inline "since 2026-11-04" annotations at :55, :59, :381, :412, :533. Un-annotated:
- **:36** "leverages existing Spring Boot infrastructure (Postgres, Kafka, Redis/Valkey)". It sits before the scope note
  and states a current value proposition.
- **:549** "~~Messaging backends beyond Kafka~~ (Now available: a Postgres messaging backend has been implemented as an
  SPI alternative to Kafka.)" This is **false today**.
- :21 "Redis-based locks and flags" is the problem statement (what teams do today), not drift.

**`docs/test-plan.md`:** bannered. :3-7 says Kafka/Postgres are the only integrations, and :9-17 is "**Superseded
2026-11-04** ... kept as history"; RabbitMQ is bannered at :33-37. Residual present-tense lines in old gap lists:
:199-200 ("maestro-lock-postgres has zero tests — the default lock backend"), :205, :250-256 (P3 Valkey-mirroring
plan), :434, :501 ("Valkey beyond existing coverage, RabbitMQ parity suite" out of scope). These are covered by the
banner, so editing them is optional. **Separate staleness:** :504-508 says "fencing tokens have not landed — Issue 11",
but Issue 11 was fixed 2026-11-04.

**`docs/multi-instance-test-plan.md`:** a completed plan ("Status: COMPLETE as of 2026-08-01", :3). :72 is annotated
inline. :138-141 (Task E lock-backend matrix) is followed by a "Superseded 2026-11-04" note at :143-144. :4-5 "on both
lock backends" is accurate history. **No real drift.**

So the handover's "same drift" in four files is overstated: the actionable edits are open-issues §1–§2 (:33-36, :67-73,
:80-95, :105-114), PRD :36 and :549, plus release-notes :277 (4.2-7) and test-plan :504-508. Size S.

### `actions/*@v4` usages (exact)

| Action | Pins | Latest major (GitHub releases, 2026-11-12) |
|---|---|---|
| `actions/checkout@v4` | build-test.yml:20; e2e-nightly.yml:52, :98, :139; javadoc.yml:23; release.yml:23 | v7.0.1 |
| `actions/setup-java@v4` | build-test.yml:23; e2e-nightly.yml:55, :101, :142; javadoc.yml:26; release.yml:26 | v6.2.0 |
| `actions/upload-artifact@v4` | build-test.yml:35; e2e-nightly.yml:74, :121, :156 | v7.0.2 |
| `actions/deploy-pages@v4` | javadoc.yml:47 | v5.0.1 |

That is 17 `actions/*@v4` pins. Other old pins: `gradle/actions/setup-gradle@v4` x6 (build-test.yml:29;
e2e-nightly.yml:61, :107, :148; javadoc.yml:32; release.yml:32; latest v6.4.1), `actions/upload-pages-artifact@v3`
(javadoc.yml:41; latest v5.0.0). `actions/configure-pages@v5` is at javadoc.yml:38 (latest v6.0.0). The handover's
"move setup-java to @v5" is already two majors behind. Read each changelog before bumping (artifact action majors
changed behaviour historically). Size S, one PR.

### `tasks/todo.md` unticked items

- **:15** "Review section added here after merge" (Host-safety milestone), as the handover says. Also **:14** is stale:
  it says the plan 02 "2 h soak still running", but handover §1 records it passed (run 43082784284).
- Not mentioned by the handover:
  - :42 "Soak on run 42094711104 (reported separately)"
  - :338 L1 (traceparent wire IT), :339 L3 (Issue-22 exhaustion path skips `emitRunAbandoned`)
  - **:343 A6**: Boot-compat pin for plain `@KafkaListener`. Directly relevant to 3.4; do it there.
  - :344 A4 (classpath-walking `maestro.enabled` pin)
  - :564-576 chaos-harness steps 2–14, which look completed (the harness ships and runs nightly) but were never ticked
  - :601-602 Ruling-3 execution
- Recommend ticking or annotating each with its outcome, or moving the open ones (L1, L3, A4, A6) into
  `docs/open-issues.md`.

---

## Overlaps — group the work by file

- **Kafka PR:** 3.4 + 3.11c + todo A6. Files: `KafkaMessagingAutoConfiguration`, `AdminClientAutoConfiguration`
  (likely delete the dead bean), `MaestroSignalListenerBeanPostProcessor`, 5 sample activity classes, Kafka config tests,
  `docs/configuration.md` §§ Kafka Client Configuration and Admin, release notes.
- **Store/janitor PR:** 3.11a + 3.11b + Issue 39. Files: `WorkflowStore` SPI, `AbstractJdbcWorkflowStore`,
  `InMemoryWorkflowStore`, `RetentionSweeper`, `SignalJanitor`, `StartupRecoveryRunner`. (Handover 3.3 also edits
  `AbstractJdbcWorkflowStore`; sequence them.)
- **Schema PR:** 4.2-4 + 4.3 (S part) + 3.11d doc. Files: `MaestroSchemaInitializer`, `MaestroSchemaInitializerTest`,
  test fixtures, `docs/configuration.md` § Schema management, CLAUDE.md.
- **Observability/tests PR:** 4.2-1 + 4.2-5. Files: `MicrometerEngineObserver`, `docs/observability.md`,
  `StoreFaultStandDownTest`.
- **Standalone:** Issue 38 (`PostgresWakeListener`).
- **Docs-only PR:** §7 drift, 4.2-3 pitfalls rule, 4.2-7 cosmetics (the `MaestroDataSources` message change is code
  plus two doc quotes), todo.md, CI action bumps (separate CI PR).

## Surprises

1. `AdminEventPublisher`/`AdminClientAutoConfiguration` registers a bean that no code uses.
2. `docs/configuration.md:998-1001` (transaction manager "never exists") is likely wrong: setting
   `spring.kafka.producer.transaction-id-prefix` should fail startup today. Inferred from source; prove with test 4 in 3.4.
3. With `defaultCandidate=false`, unqualified `<String, byte[]>` injection points silently fall back to Boot's
   wildcard beans. That includes the repo's samples and any host that followed the docs. An identity pin is mandatory.
4. Retention can run 300 back-to-back batches, not 100. The janitor has no leader election and no service filter.
5. The a27a275 upgrade test copies "old" SQL from the current classpath, so nothing catches an edit to a shipped
   migration.
6. Flyway counts unresolved V200–V202 rows as `lastApplied`, so `outOfOrder` is load-bearing for every future
   migration on such databases, not only for V6–V9.
7. The CI action majors are two to three versions behind, not one.
