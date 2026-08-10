# Maestro Audit — "Configuration That Reads Correctly and Does Nothing"

Scope: main tree of repository root (the `.claude/worktrees/*` copies were excluded; `maestro-messaging-rabbitmq` has **no sources on main** — no entry in `settings.gradle.kts` includes, only stale `build/`/`target/` dirs — its source exists only in the `multi-instance-verification` worktree and is covered in F14).

Spring Boot bytecode verified against the actual Gradle-cache jars (all 4.0.5): `spring-boot-kafka`, `spring-boot-jdbc`, `spring-boot-micrometer-metrics`, `spring-boot-micrometer-tracing{,-brave,-opentelemetry}`, `spring-boot-jackson`.

---

## Part 1 — Bean shadowing audit

Every `@Bean` in every main-tree `*AutoConfiguration`. "COMB" = `@ConditionalOnMissingBean`.

### KafkaMessagingAutoConfiguration (`maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/config/KafkaMessagingAutoConfiguration.java`)

Class ordering: `@AutoConfiguration(after = MaestroAutoConfiguration.class, afterName = {…tracing configs…})` (lines 68–84). **No ordering relative to Boot's `org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration`**, so the alphabetical fallback (`io.b2mash…` < `org.springframework…`) evaluates Maestro first — Maestro's typed beans are already registered when Boot's type-conditioned beans evaluate.

| Bean | Line | Maestro condition | Boot counterpart (verified in spring-boot-kafka-4.0.5 bytecode) | Verdict |
|---|---|---|---|---|
| `maestroKafkaProducerFactory` → `ProducerFactory<String,byte[]>` | 93–102 | COMB **on name** `"maestroKafkaProducerFactory"` (line 94) | `KafkaAutoConfiguration.kafkaProducerFactory` — COMB **on type** `ProducerFactory.class` | **Suppresses Boot's.** Voids `spring.kafka.producer.*` entirely (serializers, `acks`, `compression-type`, `batch-size`, `properties.*`, `transaction-id-prefix`) |
| `maestroKafkaTemplate` → `KafkaTemplate<String,byte[]>` | 104–110 | COMB **on name** `"maestroKafkaTemplate"` (line 105) | `KafkaAutoConfiguration.kafkaTemplate` — COMB **on type** `KafkaTemplate.class` | **Suppresses Boot's.** Voids `spring.kafka.template.*` (`observation-enabled`, `default-topic`, converters, `ProducerListener`) |
| `maestroKafkaConsumerFactory` → `ConsumerFactory<String,byte[]>` | 112–126 | COMB **on name** `"maestroKafkaConsumerFactory"` (line 113) | `KafkaAutoConfiguration.kafkaConsumerFactory` — COMB **on type** `ConsumerFactory.class` | **Suppresses Boot's** `kafkaConsumerFactory` bean. See F2 for exact blast radius |
| `maestroKafkaMessagingConfig` → `KafkaMessagingConfig` | 128–154 | COMB (type) | none (Maestro type) | OK |
| `kafkaWorkflowMessaging` | 199–215 | COMB `WorkflowMessaging.class` | none | OK |
| `maestroKafkaTracePropagation` (nested `TracePropagationConfiguration`) | 229–246 | `@ConditionalOnBean({Tracer, Propagator})` + COMB | consumes Boot beans, defines Maestro type | OK — ordering trap already fixed via class-level `afterName` (lines 79–84); all four FQCNs verified present in the 4.0.5 jars |
| `maestroSignalListenerBeanPostProcessor` | 248–252 | COMB (type) | none | OK (but see F3, F4) |

The only reason Maestro's own beans back off is the *name* condition — that is the extension seam the samples use (F13) — while Boot's back off on *type*. Both facts of Issue 23 confirmed exactly.

### MaestroAutoConfiguration (`maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroAutoConfiguration.java`)

13 beans (lines 52–176): `maestroPayloadSerializer`, `maestroRetryExecutor`, `maestroActivityProxyFactory`, `maestroWorkflowExecutor`, `maestroDurableWorkflowBeanRegistrar` (static), `maestroActivityStubBeanPostProcessor`, `maestroWorkflowRegistrar`, `maestroStartupRecoveryRunner`, `maestroAdminCommandDispatcher`, `maestroSignalSubscriptionRunner`, `maestroGracefulShutdownHandler`, `maestroClient`. **All Maestro-owned types — none collides with a Boot-provided type.** It *consumes* Boot's Jackson 3 `ObjectMapper` (line 54) rather than defining one — correct. Class-level `@ConditionalOnBean(WorkflowStore.class)` (line 46) is the known ordering trap, defused by the store module's `before =` (see below).

### MaestroHealthAutoConfiguration (`…/spring/health/MaestroHealthAutoConfiguration.java`)
`maestroHealthIndicator` (lines 46–51), COMB on type. `@ConditionalOnBean(WorkflowExecutor.class)` at line 34 is safe because of `after = MaestroAutoConfiguration.class` at line 32. No Boot type shadowed. **OK.**

### MaestroObservabilityAutoConfiguration (`…/spring/observe/MaestroObservabilityAutoConfiguration.java`)
`maestroMicrometerEngineObserver` (131–135), `maestroEngineGauges` (143–147), `maestroTracingEngineObserver` (173–177). All Maestro types consuming `MeterRegistry`/`Tracer`/`Propagator`; none shadows Boot. The Issue-(a)-class ordering trap is fixed: `afterName` (lines 100–113) names `MetricsAutoConfiguration`, `CompositeMeterRegistryAutoConfiguration` and all four tracing configs — **every one of those six FQCN strings verified to exist as classes in the 4.0.5 jars** (`org/springframework/boot/micrometer/metrics/autoconfigure/…`, `org/springframework/boot/micrometer/tracing/{autoconfigure,brave/autoconfigure,opentelemetry/autoconfigure}/…`). **OK.**

### PostgresStoreAutoConfiguration (`maestro-store-postgres/…/config/PostgresStoreAutoConfiguration.java`)
`postgresWorkflowStore` (58–68), COMB `WorkflowStore.class`. Declares `before = MaestroAutoConfiguration.class, afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"` (47–49) — FQCN verified in `spring-boot-jdbc-4.0.5.jar`. Consumes `DataSource`, defines none. **OK — except F8 (JNDI/XA gap).**

### PostgresMessagingAutoConfiguration (`maestro-messaging-postgres/…/config/PostgresMessagingAutoConfiguration.java`)
`maestroPostgresNotificationListener` (59–65), `postgresWorkflowMessaging` (67–85), `postgresSignalNotifier` (87–94), `maestroPostgresMessageCleaner` (96–100). All Maestro types; consumes `DataSource`/`ObjectMapper`. **No shadowing. OK** (but participates in F7).

### ValkeyLockAutoConfiguration (`maestro-lock-valkey/…/config/ValkeyLockAutoConfiguration.java`)
`maestroRedisClient` → **Lettuce `RedisClient`** (55–60, COMB on name), `maestroLockConnection` → `StatefulRedisConnection` (62–66), `valkeyDistributedLock` (68–74), `valkeySignalNotifier` (76–82). Spring Boot's Data-Redis auto-configuration does **not** expose a `RedisClient` bean (it builds `LettuceConnectionFactory` internally), and `spring-boot-data-redis` is not even on Maestro's dependency graph (empty in the Gradle cache for this project) — so **no Boot bean is suppressed**. The defect here is the inverse: Maestro builds its own client from only two properties — see F5.

### PostgresLockAutoConfiguration (`maestro-lock-postgres/…/config/PostgresLockAutoConfiguration.java`)
`postgresDistributedLock` (44–48), `postgresLockCleaner` (50–54). Maestro types, consumes `DataSource`. **OK.**

### AdminClientAutoConfiguration (`maestro-admin-client/…/AdminClientAutoConfiguration.java`)
`adminEventPublisher` (42–50), COMB on type — Maestro type, no shadowing. Injects `KafkaTemplate<String, byte[]>` — in a Maestro+Kafka app that resolves to `maestroKafkaTemplate`; in `maestro-admin` (which depends on admin-client + `spring-boot-starter-kafka`, `maestro-admin/build.gradle.kts:8,20`) it resolves to Boot's wildcard-typed `kafkaTemplate`. **OK on shadowing**, but see F9/F10.

### maestro-admin (application, not an auto-configuration)
No `@Bean` methods in `maestro-admin/src/main` at all (grep over the module returns none); it relies on Boot's Kafka auto-configuration, which works there because `maestro-messaging-kafka` is not on its classpath. **OK.**

---

## Part 2 — Findings

### F1 — Issue 23 core facts confirmed and extended to the ConsumerFactory (Critical, KNOWN — Issue 23, refined)
**User-visible symptom:** every `spring.kafka.producer.*` / `spring.kafka.template.*` value binds, shows in `/actuator/configprops`, and does nothing.

Confirmed at `KafkaMessagingAutoConfiguration.java:93–110` against `spring-boot-kafka-4.0.5` bytecode as tabled above. Refinements to what Issue 23 (`docs/open-issues.md:1853–2001`) records:

- Issue 23's property table (`docs/open-issues.md:1898–1904`) omits the third shadowed bean: `maestroKafkaConsumerFactory` (`KafkaMessagingAutoConfiguration.java:112–126`) suppresses Boot's `kafkaConsumerFactory` (COMB on `ConsumerFactory.class`, verified), so **any app code injecting `ConsumerFactory` by type gets Maestro's byte[]-only factory** built from nothing but `spring.kafka.bootstrap-servers` (line 254–256).
- Blast radius nuance (verified in `KafkaAnnotationDrivenConfiguration` bytecode): Boot's `kafkaListenerContainerFactory` is COMB **on name**, injects `ObjectProvider<ConsumerFactory<Object,Object>>`, and Maestro's `ConsumerFactory<String,byte[]>` does **not** satisfy that generic signature — the provider falls back to an internally-built factory from `spring.kafka.consumer.*`. So plain `@KafkaListener` users keep their consumer properties; the loss is confined to Boot's `kafkaConsumerFactory` bean and anything injecting `ConsumerFactory` directly. Worth stating in the issue so the fix cycle doesn't over-claim.
- `spring.kafka.producer.transaction-id-prefix`: Boot's `kafkaTransactionManager` (`@ConditionalOnProperty` on that prefix) injects a `ProducerFactory` — with Boot's factory suppressed it would receive Maestro's, which has no transaction id — Kafka transactions are broken, not just unconfigured.
- The samples themselves carry dead config proving the trap: `maestro-samples/sample-order-service/src/main/resources/application.yml:12–17` (and both loan services) set `spring.kafka.producer.key-serializer` etc. — all voided; it only "works" because the values coincide with Maestro's hardcoded ones (`KafkaMessagingAutoConfiguration.java:98–100`).

### F2 — "Stop shadowing" vs "honour spring.kafka.* ourselves": consumers of the three beans (analysis requested)
Complete list of in-Maestro consumers (main tree):
- `maestroKafkaTemplate` — injected by `kafkaWorkflowMessaging` (`KafkaMessagingAutoConfiguration.java:202,209`); looked up **by name first** in `MaestroSignalListenerBeanPostProcessor.resolveKafkaTemplate` (`MaestroSignalListenerBeanPostProcessor.java:242–247`) for dead-letter publishing, falling back to `ctx.getBean(KafkaTemplate.class)`; injected by-type-and-generics into `AdminClientAutoConfiguration.adminEventPublisher` (`AdminClientAutoConfiguration.java:45`).
- `maestroKafkaProducerFactory` — injected by `maestroKafkaTemplate` (`KafkaMessagingAutoConfiguration.java:107`); injected by every sample override (`ObservedKafkaTemplateConfig.java:65–71` in all three loan services).
- `maestroKafkaConsumerFactory` — injected by `kafkaWorkflowMessaging` (`:203,210`); fetched **raw by type** `ctx.getBean(ConsumerFactory.class)` in `MaestroSignalListenerBeanPostProcessor.java:136`.

**"Stop shadowing Boot's beans" fix** (give Maestro's beans a distinct wrapper type, or order Maestro after `KafkaAutoConfiguration` and reuse Boot's factories): Boot's `kafkaTemplate`/`kafkaProducerFactory` come back to life, so `spring.kafka.*` works again — but it breaks: (a) the documented workaround and all three shipped `ObservedKafkaTemplateConfig` classes, which exist *because* the bean is a plain `KafkaTemplate` overridable by name (`ObservedKafkaTemplateConfig.java:34–36,52–53`); (b) `resolveKafkaTemplate`'s type fallback at `:246` becomes ambiguous once two `KafkaTemplate`s exist (needs the name path or generics); (c) `MaestroSignalListenerBeanPostProcessor.java:136` — `ctx.getBean(ConsumerFactory.class)` throws `NoUniqueBeanDefinitionException` the moment Boot's `ConsumerFactory` coexists with Maestro's (see F3); (d) engine wire-format assumptions: Maestro requires `String`/`byte[]` + `acks=all`; reusing Boot's factories means a user's `spring.kafka.producer.value-serializer` silently corrupts the engine's own topics. So "just stop shadowing" without keeping a dedicated engine-owned factory is not safe.
**"Maestro honours spring.kafka.* itself" fix** (build Maestro's factories from `KafkaProperties`/`KafkaConnectionDetails`, overriding only key/value serializers and acks; propagate `spring.kafka.template.observation-enabled` and listener observation): no bean graph changes, workaround classes keep working, and the property surface stops lying — but security/SSL/SASL settings (`spring.kafka.ssl.*`, `spring.kafka.security.*`, `spring.kafka.properties.*`) are the real payload, and today Maestro cannot talk to a secured cluster at all without a full bean override, which is the strongest argument for this variant. The samples' `ObservedKafkaTemplateConfig` then becomes deletable (observation on by default when a `Tracer` exists, per Issue 23's "How to tackle" step 1, `docs/open-issues.md:1957–1963`).

### F3 — `@MaestroSignalListener` activation fetches `ConsumerFactory` ambiguously (Medium, NEW)
**User-visible symptom:** app startup fails with `NoUniqueBeanDefinitionException: ConsumerFactory` as soon as the application defines its own `ConsumerFactory` (the standard move for JSON `@KafkaListener`s) alongside `@MaestroSignalListener`.

`MaestroSignalListenerBeanPostProcessor.java:136`: `ctx.getBean(ConsumerFactory.class)` — raw, by type, no name preference, no generics filter. This is asymmetric with the same class's own `resolveKafkaTemplate` (`:242–247`), which prefers the `maestroKafkaTemplate` name precisely "so that an application that defines `KafkaTemplate` beans of its own does not make the lookup ambiguous". The identical hazard was seen and handled for the template but not for the consumer factory two hops away. Not covered by Issue 23's text.

### F4 — Issue 23 part 2 confirmed verbatim (Critical, KNOWN — Issue 23)
**User-visible symptom:** signals arriving with a valid `traceparent` are persisted with `trace_context = NULL`; `spring.kafka.listener.*` never reaches the containers.

`MaestroSignalListenerBeanPostProcessor.java:213–219`: hand-built `ContainerProperties(reg.topic())` + `setGroupId` + `AckMode.RECORD` + a lambda listener that reads `record.value()` only. No `setObservationEnabled(...)` anywhere in the file; zero occurrences of `runWithExtractedContext`; headers never touched (`handleMessage`, `:249–288`, deserializes the value and never sees `record.headers()`). Matches `docs/open-issues.md:1908–1949` exactly.

### F5 — Valkey lock ignores `spring.data.redis.host/port/password/…`, and configuration.md's own example uses them (Medium, NEW)
**User-visible symptom:** a user who copies the docs' "Complete Example" gets a lock client pointed at `redis://localhost:6379` no matter what host/port they configured — startup failure at best, silently locking against the wrong local instance at worst.

`ValkeyLockAutoConfiguration.resolveRedisUri` (`ValkeyLockAutoConfiguration.java:84–97`) reads exactly two properties: `spring.data.redis.url`, then `maestro.lock.valkey.uri`, then falls back to `redis://localhost:6379`. `spring.data.redis.host`, `.port`, `.password`, `.database`, `.ssl` are never read by anyone (Boot's Data-Redis auto-config is not on the classpath to read them either). Yet `docs/configuration.md:444–447` — the canonical "Complete Example" — configures `spring.data.redis.host` + `port`, and `:478–479` tells users "you still need to configure … `spring.data.redis`". The samples quietly know better and use `.url` (`sample-order-service/application.yml:20–21`). This is defect class (b)/(c) verbatim: documented property, binds fine, consumed by nothing. Additionally `maestro.lock.valkey.uri` — the property that *does* work — appears nowhere in `docs/configuration.md` (absent from the full file) and is not in `MaestroProperties` (no metadata, no IDE completion).

### F6 — `maestro.retry.*`: four documented tuning knobs bound and read by nothing (Medium, NEW)
**User-visible symptom:** setting `maestro.retry.default-max-attempts: 10` (docs even say "set to 1 to disable retries") changes nothing; activities keep retrying 3×/1s/60s/2.0.

`MaestroProperties.RetryProperties` (`MaestroProperties.java:392–402`) binds `maestro.retry.default-*`, documented at `docs/configuration.md:311–339` ("define the default retry policy applied to activities that do not specify their own `@RetryPolicy`"). `getRetry()` has **zero main-code callers** — the only references are the binding test (`MaestroPropertiesBindingTest`). The actual default comes from hardcoded `RetryPolicy.defaultPolicy()` (`maestro-core/src/main/java/io/b2mash/maestro/core/retry/RetryPolicy.java:63–73` — 3 attempts, 1s, 1m, 2.0), reached via `ActivityProxyFactory`; `MaestroAutoConfiguration.maestroRetryExecutor()` (`MaestroAutoConfiguration.java:58–62`) is constructed with no properties. The values coincide with the documented defaults, which is exactly why nobody has noticed — the property is inert, not wrong. (Compare defect (d): another "reads correctly, does nothing" in the same family.)

### F7 — `maestro.worker.*`: an entire documented config section is inert, including in the docs' "minimal configuration" (Medium, NEW)
**User-visible symptom:** `maestro.worker.task-queues[].concurrency: 1` does not limit anything; the docs' own minimal example is a no-op block.

`MaestroProperties.WorkerProperties`/`TaskQueueProperties` (`MaestroProperties.java:326–346`) bind `maestro.worker.task-queues` with `name` ("**Required**" per `docs/configuration.md:225`), `concurrency`, `activity-concurrency` — documented in full at `docs/configuration.md:217–246` and present in every example including the "minimal configuration" (`:469–475`) and the Postgres-only example (`:461–464`). `getWorker()` has **zero main-code callers** (only `MaestroPropertiesBindingTest.java:149,166`). No queue registration, no semaphore, nothing consumes it. Users believe they are capping concurrency; they are not.

### F8 — `maestro.enabled=false` does not "disable the engine entirely"; with a messaging module present it likely fails startup (Medium, NEW)
**User-visible symptom:** flipping the documented master kill-switch (`docs/configuration.md:25`) on a normal Maestro+Kafka service produces a `NoSuchBeanDefinitionException: MaestroProperties` instead of a clean disable — or, minus that, leaves Maestro's Kafka beans alive and still shadowing Boot's.

`MaestroProperties` is registered solely by `@EnableConfigurationProperties(MaestroProperties.class)` on `MaestroAutoConfiguration` (`MaestroAutoConfiguration.java:45,47`), which backs off under `maestro.enabled=false`. But `KafkaMessagingAutoConfiguration`'s class conditions (`KafkaMessagingAutoConfiguration.java:85–86`) are only `@ConditionalOnClass(KafkaTemplate)` + `maestro.messaging.type=kafka (matchIfMissing)` — no `maestro.enabled` gate, no `@ConditionalOnBean(WorkflowStore/MaestroProperties)` — and `maestroKafkaMessagingConfig(MaestroProperties)` (`:130`) plus `maestroKafkaConsumerFactory(…, KafkaMessagingConfig)` (`:114–115`) are eager singletons that inject the now-absent bean. `PostgresMessagingAutoConfiguration` has the same chain (`PostgresMessagingAutoConfiguration.java:49–51` conditions; `:73` injects `MaestroProperties`). `ValkeyLockAutoConfiguration` (`:48–50`) needs no `MaestroProperties`, so with `maestro.enabled=false` it *succeeds* — and opens three live connections to Valkey (`:64–66, 78–81`) for an engine that is supposedly off. Same ordering-trap family as defect (a): a gate that reads correctly (`maestro.enabled` "disables the engine entirely") and doesn't hold across modules. (Static analysis; the dependency chain is unconditional, but a pinning test should confirm the exact exception.)

### F9 — Store backs off silently for JNDI/XA DataSources (Low, NEW)
**User-visible symptom:** on a JNDI- or XA-sourced `DataSource`, the whole engine is silently absent — no error, no workflows, because `@ConditionalOnBean(DataSource)` evaluated before Boot registered it.

`PostgresStoreAutoConfiguration.java:49` names only `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` in `afterName`. `spring-boot-jdbc-4.0.5.jar` also ships `JndiDataSourceAutoConfiguration` and `XADataSourceAutoConfiguration` in the same package (verified in the jar listing); neither is named, and `io.b2mash…` sorts before `org.springframework…`, so a `DataSource` produced by either is invisible to `@ConditionalOnBean(DataSource.class)` at `:51`, which then cascades into `MaestroAutoConfiguration`'s own `@ConditionalOnBean(WorkflowStore.class)` (`MaestroAutoConfiguration.java:46`) backing off too. Rare configuration, but it is precisely defect class (a), and the fix is two more strings.

### F10 — maestro-admin-client honours only the *deprecated* topic property (Low, NEW)
**User-visible symptom:** a service using `maestro-admin-client` and following CLAUDE.md's guidance ("`maestro.admin.events.topic` is a deprecated alias for `maestro.messaging.topics.admin-events`") publishes to the wrong topic — the canonical property is unknown to the module.

`AdminEventPublisher` gets its topic from `AdminClientProperties.getTopic()` (`AdminClientAutoConfiguration.java:49`; `AdminClientProperties.java:19–20,30,40` — prefix `maestro.admin.events`). The alias-resolution logic that makes `maestro.messaging.topics.admin-events` canonical exists only in `KafkaMessagingAutoConfiguration.resolveAdminEventsTopic` (`KafkaMessagingAutoConfiguration.java:178–197`). The two modules disagree about which property wins. Also: `maestro-admin` pulls admin-client in (`maestro-admin/build.gradle.kts:8`) and never references `AdminEventPublisher` in `src/main` — the auto-configured publisher bean sits unused there (harmless, but with `matchIfMissing=true` at `AdminClientAutoConfiguration.java:30` it instantiates against Boot's wildcard-generic `kafkaTemplate` on every admin start).

### F11 — Documented-property sweep: everything else in configuration.md/CLAUDE.md is genuinely consumed (OK)
Verified consumer for each remaining documented property:
- `maestro.enabled` → `MaestroAutoConfiguration.java:45`, `MaestroObservabilityAutoConfiguration.java:114` (but see F8). `maestro.service-name` → `MaestroAutoConfiguration.java:81–85`; also consumer-group derivation `KafkaMessagingAutoConfiguration.java:134–141`, `MaestroSignalListenerBeanPostProcessor.java:290–302`.
- `maestro.store.type` → `PostgresStoreAutoConfiguration.java:52` (condition); `maestro.store.table-prefix` → `:55–66` (deliberately via `Environment`, comment at 63–65 explains the ordering reason).
- `maestro.messaging.type` → `KafkaMessagingAutoConfiguration.java:86` / `PostgresMessagingAutoConfiguration.java:51`; `consumer-group` → `KafkaMessagingAutoConfiguration.java:133`; `topics.tasks/signals` → `:144–145`, actually used with null-means-dynamic contract in `KafkaWorkflowMessaging.java:275–282`; `topics.admin-events` + deprecated alias → `:178–197` (starter path only — F10); `redelivery.*` → `:142–152`, `PostgresMessagingAutoConfiguration.java:75–84`, `MaestroSignalListenerBeanPostProcessor.java:141,224–230`.
- `maestro.lock.type` → `ValkeyLockAutoConfiguration.java:50` / `PostgresLockAutoConfiguration.java:41`; `key-prefix`/`ttl` → `MaestroAutoConfiguration.java:94,106` and `ActivityStubBeanPostProcessor.java:101–102` (Issue 9's fix holds).
- `maestro.timer.*` → `StartupRecoveryRunner.java:47–48`; `maestro.recovery.*` → `:54–57` + `MaestroHealthAutoConfiguration.java:50`.
- `maestro.shutdown.timeout` / `maestro.signal.wake-recheck-interval` → `MaestroAutoConfiguration.java:96–97,104–108` (Issue 7's fix holds).
- `maestro.admin.events.enabled` → `MaestroAutoConfiguration.java:95`, `ActivityStubBeanPostProcessor.java:111–112` (GatedWorkflowMessaging), `AdminClientAutoConfiguration.java:30` (Issue 6's fix holds).
- `maestro.observability.metrics.enabled` / `tracing.enabled` → `MaestroObservabilityAutoConfiguration.java:119–120,159–160` and `KafkaMessagingAutoConfiguration.java:231–232` (defect (a)'s fix holds; afterName strings all verified — see Part 1).
- `maestro.workflow-packages` → consumed early via `Environment` (`DurableWorkflowBeanRegistrar.java:64`), declared in `MaestroProperties.java:51` for metadata — works, but **undocumented in configuration.md** (absent from the file). Low doc gap, together with `maestro.lock.valkey.uri` (F5).

### F12 — Samples/demo override (`ObservedKafkaTemplateConfig`) — what it does (context for Issue 23)
All three loan services ship an identical `@Configuration` (`maestro-samples/sample-loan-origination/*/config/ObservedKafkaTemplateConfig.java:46–72`): defines a bean **named** `maestroKafkaTemplate` wrapping Maestro's `maestroKafkaProducerFactory` with `setObservationEnabled(true)` (`:68–70`). Maestro's name-based COMB backs off, engine + domain traffic share the observed template, producer spans/`traceparent` restored. Its own Javadoc (`:22–30`) is the clearest in-repo statement of Issue 23's mechanism ("Boot backs that bean off entirely … setting the property has no effect at all"). Producer-side only; the consumer half (F4) has no user-side workaround, as Issue 23 records at `docs/open-issues.md:1992`.

### F13 — RabbitMQ module (worktree-only) is the counter-example that proves the right pattern (OK / informational)
Not on main (excluded from `settings.gradle.kts`; no `src/` in the main-tree module dir). In the `multi-instance-verification` worktree, `RabbitMqMessagingAutoConfiguration` defines **one** bean, `rabbitMqWorkflowMessaging` (COMB on `WorkflowMessaging`), and *injects* Boot's `RabbitTemplate`/`ConnectionFactory` instead of defining its own — so `spring.rabbitmq.*` works untouched. When it lands on main, that is the shape the Kafka module should converge to (modulo the engine's serializer constraints). Note it injects `MaestroProperties` too, so it inherits F8's crash chain under `maestro.enabled=false`.

---

## Summary table

| # | Finding | Severity | Status vs docs/open-issues.md §4 |
|---|---|---|---|
| F1 | Kafka PF/Template/CF shadow Boot's by type; `spring.kafka.producer.*`/`template.*` voided; CF shadowing + `@KafkaListener` fallback nuance + transactions broken | Critical | KNOWN (Issue 23) — CF row, listener-factory nuance, txn detail are refinements |
| F2 | Fix-shape analysis: consumers of the three beans; what each fix variant breaks | — | Analysis for Issue 23's step 3 |
| F3 | `MaestroSignalListenerBeanPostProcessor.java:136` raw `getBean(ConsumerFactory.class)` — ambiguity crash when app defines its own CF | Medium | **NEW** |
| F4 | Hand-built ContainerProperties; no observation, no header extraction | Critical | KNOWN (Issue 23 part 2) — confirmed |
| F5 | `spring.data.redis.host/port/…` never read; docs' Complete Example uses them; the working `maestro.lock.valkey.uri` undocumented | Medium | **NEW** |
| F6 | `maestro.retry.default-*` documented, bound, consumed by nothing (hardcoded `RetryPolicy.defaultPolicy()`) | Medium | **NEW** |
| F7 | `maestro.worker.task-queues*` documented (incl. "minimal config"), bound, consumed by nothing | Medium | **NEW** |
| F8 | `maestro.enabled=false` doesn't gate messaging/lock modules; startup crash via missing `MaestroProperties`, or live Valkey connections with engine off | Medium | **NEW** |
| F9 | `PostgresStoreAutoConfiguration` afterName misses `JndiDataSourceAutoConfiguration`/`XADataSourceAutoConfiguration` — silent engine absence | Low | **NEW** |
| F10 | admin-client reads only deprecated `maestro.admin.events.topic`, not the canonical messaging property; unused publisher bean in maestro-admin | Low | **NEW** |
| F11 | All other documented properties verified consumed; `maestro.workflow-packages` + `maestro.lock.valkey.uri` doc gaps | OK / Low | Sweep result |
| F12 | ObservedKafkaTemplateConfig workaround documented | — | KNOWN (Issue 23 workaround) |
| F13 | RabbitMQ (worktree) uses the non-shadowing pattern | OK | Informational |

## Explicitly NEW findings (not Issue 22/23/24, not resolved issues 1–21)

1. **F3** — signal-listener activation's ambiguous `ConsumerFactory` lookup (`MaestroSignalListenerBeanPostProcessor.java:136`).
2. **F5** — Valkey lock reads only `spring.data.redis.url`/`maestro.lock.valkey.uri`; `docs/configuration.md:444–447,478–479` steers users to properties nothing reads.
3. **F6** — `maestro.retry.*` dead (docs `configuration.md:311–339`; zero consumers of `MaestroProperties.getRetry()`).
4. **F7** — `maestro.worker.*` dead (docs `configuration.md:217–246,469–475`; zero consumers of `getWorker()`).
5. **F8** — `maestro.enabled=false` kill-switch not honoured by kafka/postgres-messaging/valkey-lock modules (`KafkaMessagingAutoConfiguration.java:85–86,130`; `PostgresMessagingAutoConfiguration.java:49–51,73`; `ValkeyLockAutoConfiguration.java:48–50`).
6. **F9** — JNDI/XA DataSource ordering gap (`PostgresStoreAutoConfiguration.java:47–51`).
7. **F10** — admin-client topic-property divergence (`AdminClientAutoConfiguration.java:49`; `AdminClientProperties.java:19,30` vs `KafkaMessagingAutoConfiguration.java:178–197`).
8. (Doc-only, Low) `maestro.workflow-packages` and `maestro.lock.valkey.uri` missing from `docs/configuration.md`; samples carry dead `spring.kafka.producer/consumer.*` blocks (`sample-order-service/application.yml:12–17`).
