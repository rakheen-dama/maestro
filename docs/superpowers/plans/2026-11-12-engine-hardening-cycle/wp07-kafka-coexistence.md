# WP7 Kafka Coexistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Spring Boot service that already uses Kafka can add Maestro and keep its own Kafka setup. Boot's `kafkaTemplate`, `kafkaProducerFactory`, `kafkaConsumerFactory` and `kafkaTransactionManager` register beside Maestro's three engine beans, and `spring.kafka.*` reaches them. The engine still sends and consumes through its own `String`/`byte[]` beans, with unchanged bytes and group ids. A host that injects a `byte[]` template without a qualifier fails at startup with the fix in the message, instead of failing on every send. The dead `AdminEventPublisher` bean is deleted, and `maestro.admin.events.enabled` defaults to `false`.

**Architecture:** Maestro's three Kafka beans become `@Bean(defaultCandidate = false)`. Every Maestro injection point names its bean with `@Qualifier`, using the constants in a new `MaestroKafkaBeans` holder, so no Maestro code relies on a parameter name or on by-type resolution. Boot's `OnBeanCondition` ignores beans that are not default candidates, so Boot's type-conditioned Kafka beans register again, and a host's unqualified injection points resolve to Boot's beans. A new startup check (`ByteArrayTemplateInjectionCheck`) catches the one silent case this opens up. Admin events: one default flips, and the topic verifier already drops the admin topic when events are off.

**Tech Stack:** Java 25, Spring Framework 7.0.6 (`spring-beans`), Spring Boot 4.0.5 (`spring-boot-kafka`, `spring-boot-autoconfigure`), Spring Kafka 4.0.4, kafka-clients 4.1.2, Jackson 3 (`tools.jackson`), JUnit 5 / AssertJ / Awaitility, Testcontainers 2.0 (`confluentinc/cp-kafka:7.7.1`, auto-create off, via `KafkaTestSupport`).

**Spec:** `docs/superpowers/plans/2026-11-12-engine-hardening-cycle/00-spec.md` (WP7 row, D3, D5, §2 anti-goals, §6). **Evidence:** `evidence/audit-C-host-integration-followups.md` §3.4, §3.11c, "Surprises" 2–3. Read both. This plan argues from them and corrects them where noted.

## Added, changed and removed public surface

**Added**

| Surface | Exact shape |
|---|---|
| `io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans` | `public final class` with a private constructor and three constants: `TEMPLATE = "maestroKafkaTemplate"`, `PRODUCER_FACTORY = "maestroKafkaProducerFactory"`, `CONSUMER_FACTORY = "maestroKafkaConsumerFactory"`. The Javadoc says these are the documented qualifiers and that the beans are not default candidates. It sits beside `MaestroSignalHeaders`, which hosts already import. |
| Bean `maestroKafkaTemplateInjectionCheck` | Type `ByteArrayTemplateInjectionCheck`, a package-private class in `io.b2mash.maestro.messaging.kafka.config`. It is a `SmartInitializingSingleton` registered by `KafkaMessagingAutoConfiguration` (Task 4). It has no property and no override. |

No new configuration property, SPI method or migration.

**Changed**

| Surface | Before | After |
|---|---|---|
| `maestroKafkaProducerFactory`, `maestroKafkaTemplate`, `maestroKafkaConsumerFactory` | Ordinary default candidates. Boot's `kafkaTemplate`, `kafkaProducerFactory` and `kafkaConsumerFactory` back off. | `@Bean(name = MaestroKafkaBeans.X, defaultCandidate = false)`, still `@ConditionalOnMissingBean(name = MaestroKafkaBeans.X)`. Injected only with `@Qualifier(MaestroKafkaBeans.X)`. Boot's beans register. |
| `KafkaMessagingAutoConfiguration` ordering | `beforeName = "…KafkaAutoConfiguration"`, with a comment calling the suppression deliberate | `beforeName` removed. No condition on either side depends on order any more (pinned by `bootsBeansCoexistWhicheverAutoConfigurationRunsFirst`). `after` and `afterName` (tracing) stay. |
| `MaestroSignalListenerBeanPostProcessor` | Resolves the template and consumer factory by name, then falls back to by type | By name only (`ctx.getBean(MaestroKafkaBeans.TEMPLATE, KafkaOperations.class)`, `ctx.getBean(MaestroKafkaBeans.CONSUMER_FACTORY, ConsumerFactory.class)`). The fallback is deleted: the names always exist, and by type it would now pick Boot's beans. |
| `maestro.admin.events.enabled` | default `true` | default `false` (D3). `MaestroProperties.EventsProperties.defaults()` returns `false`. `maestro.admin.events.topic` (deprecated alias) is unchanged. `WorkflowExecutor.Builder.lifecycleEventsEnabled` keeps its `true` default: D3 is a starter default, and the builder default is pinned by `WorkflowExecutorBuilderTest` (WP0). Only its Javadoc changes. |
| Host injection by type (D5, breaking) | An unqualified `KafkaTemplate<String, byte[]>`, `ProducerFactory<String, byte[]>` or `ConsumerFactory<String, byte[]>` receives Maestro's bean. | It receives Boot's bean. If that template cannot serialize `byte[]` (Boot's default `StringSerializer`), startup fails (Task 4). The documented migration is `@Qualifier(MaestroKafkaBeans.TEMPLATE)`. |

**Removed**

- `AdminClientAutoConfiguration` and its `@Bean adminEventPublisher`; `AdminClientProperties`; `maestro-admin-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`. The `AdminEventPublisher` class stays as a plain class nobody auto-configures (see Task 5 for why). The fate of the module goes to the maintainer as a new issue.

**Doc rules (new)**

- "Maestro's Kafka beans are not default candidates. Inject them with `@Qualifier(MaestroKafkaBeans.TEMPLATE)` (or `PRODUCER_FACTORY`, `CONSUMER_FACTORY`). Replace one by declaring a bean with the same name, with `defaultCandidate = false`."
- "Your own Kafka traffic uses Spring Boot's beans and `spring.kafka.*`, as in a service without Maestro."

## Verified at planning time (and how)

Read from the source jars in `~/.gradle/caches/modules-2` (versions above), from the Spring Framework 7.0.5 reference docs (context7, `factory-autowire.adoc` § "Spring 6.2 Variants"), and from the code at `main` @ `834a9da`. Nothing was run.

1. **Boot backs off only for default candidates.** `OnBeanCondition.getMatchingBeans` keeps a type match only if `isCandidate(...)`, which requires `isAutowireCandidate() && isDefaultCandidate()` (spring-boot-autoconfigure 4.0.5, `OnBeanCondition.java:221-226, 280-307`). Name matching (`:246-252`) ignores candidacy, so Maestro's own `@ConditionalOnMissingBean(name = …)` still lets a host replace a bean by name. The same filter covers `@ConditionalOnSingleCandidate` (Boot's `kafkaRetryTopicConfiguration`). **So the fix works:** with Maestro's beans marked `defaultCandidate = false`, Boot's `kafkaTemplate`, `kafkaProducerFactory`, `kafkaConsumerFactory`, `kafkaTransactionManager` and the retry-topic configuration register as in a Maestro-free app. Tests 1, 2 and 5 of Task 1 prove it.
2. **`defaultCandidate = false` vs `autowireCandidate = false`.** The reference docs say a `defaultCandidate=false` bean "will never get injected by plain declared type only, rather by type plus specific qualifier"; an `autowireCandidate=false` bean is never injected by type at all. `QualifierAnnotationAutowireCandidateResolver.isAutowireCandidate` (spring-beans 7.0.6 `:149-168`) returns `checked == TRUE || (checked == null && isDefaultCandidate())`, and a `@Qualifier` `value` falls back to the bean name. So `@Qualifier("maestroKafkaTemplate")` selects the bean, and an unqualified point never does. **The parameter-name shortcut does not rescue it.** `DefaultListableBeanFactory` step 3 (`:1689-1706`) calls the same `isAutowireCandidate`.
3. **The trap is real.** In the fallback pass, `GenericTypeAwareAutowireCandidateResolver.checkGenericTypeMatch` (`:148-158`) accepts a target with unresolvable generics. Boot declares `KafkaTemplate<?, ?>`, `DefaultKafkaProducerFactory<?, ?>` and `DefaultKafkaConsumerFactory<?, ?>`, so an unqualified `KafkaTemplate<String, byte[]>` receives Boot's template. Boot's default value serializer is `StringSerializer` (`KafkaProperties.java:528`). `KafkaProducer.doSend` (kafka-clients 4.1.2 `:1006-1012`) then throws `SerializationException: Can't convert value of class [B to class org.apache.kafka.common.serialization.StringSerializer specified in value.serializer`. It throws synchronously, out of `KafkaTemplate.send` (`KafkaTemplate.java:857`), and only after metadata for the topic arrives. Traced, not run.
4. **`getBean(KafkaTemplate.class)` after the fix** returns Boot's bean, the only default candidate (`resolveNamedBean` → `determineDefaultCandidate`, `:1562-1617, 2231-2243`). With only Maestro's bean present it still returns Maestro's (a single candidate is not filtered).
5. **The transaction-manager claim at `docs/configuration.md:1082-1085` is very likely wrong today.** Boot's `kafkaTransactionManager(ProducerFactory<?, ?>)` is `@ConditionalOnProperty("spring.kafka.producer.transaction-id-prefix")` + `@ConditionalOnMissingBean`, and today its one candidate is Maestro's factory. `KafkaProducer` properties built by Boot do not carry the prefix (`KafkaProperties.Producer.buildProperties`), so Maestro's factory has `transactionCapable() == false` (`DefaultKafkaProducerFactory.java:698`). `new KafkaTransactionManager(pf)` asserts `"The 'ProducerFactory' must support transactions"` (`KafkaTransactionManager.java:92`). Inferred result: startup fails. Task 1 Step 3 runs it before anything changes.
6. **The plain-`@KafkaListener` boundary today rests on a Boot fallback (todo A6).** `KafkaAnnotationDrivenConfiguration.kafkaListenerContainerFactory` takes `ObjectProvider<ConsumerFactory<Object, Object>>`. That provider does not match Maestro's fully resolved `<String, byte[]>` factory, so it falls back to `new DefaultKafkaConsumerFactory<>(properties.buildConsumerProperties())` (`:155-159`). **That inline factory ignores `KafkaConnectionDetails` and every `DefaultKafkaConsumerFactoryCustomizer`.** So today a host using Testcontainers `@ServiceConnection` gets plain `@KafkaListener` consumers that connect to `spring.kafka.bootstrap-servers` instead. Traced; the Task 2 red test settles it. The same constructor takes `ObjectProvider<KafkaTemplate<Object, Object>>.getIfUnique()` for the reply template: none today, Boot's `kafkaTemplate` after.
7. **What the engine injects by type.** Three sites: `maestroKafkaTemplate(ProducerFactory<String, byte[]> …)`, `kafkaWorkflowMessaging(KafkaTemplate<String, byte[]> …, ConsumerFactory<String, byte[]> …)`, and the dead `AdminClientAutoConfiguration.adminEventPublisher(KafkaTemplate<String, byte[]> …)`. `MaestroSignalListenerBeanPostProcessor` looks up by name. `KafkaTopicVerifier` and `KafkaSignalIdentity` are handed the factory. `maestro-spring-boot-starter` main code has no Kafka type. `AdminEventPublisher` has no caller outside its own module (`grep -rn AdminEventPublisher --include='*.java' . | grep -v /build/ | grep -v '^./maestro-admin-client/'` prints nothing). The `maestro-admin` app depends on `maestro-admin-client` but not on `maestro-messaging-kafka`, so 3.4 does not reach it.
8. **D3 dependants.** The samples, `run-e2e.sh`, `demo/scripts/lib/jvm-env.sh` and the chaos cluster (which runs the loan samples) all set `maestro.admin.events.enabled` explicitly or inherit it from sample YAML: order and payment `true`, the three loan services `false`, and `run-e2e.sh:1863` and the demo override with `MAESTRO_ADMIN_EVENTS_ENABLED=true`. **None relies on the default.** Seven existing tests do (Task 6). The verifier already omits the admin topic when events are off (`KafkaTopicVerifier.requiredTopics:147`), so flipping the default is all it takes for startup to stop requiring the topic.
9. **Wire.** Nothing in this package changes a serializer, `acks`, header, topic name or group id on an engine client. Engine-forced invariants stay in `maestroKafkaProducerFactory` and `maestroKafkaConsumerFactory`, pinned unchanged by `KafkaMessagingAutoConfigurationPropertiesTest.engineInvariantsAlwaysWin` and `consumerAutoOffsetResetIsOverridableButGroupIdStaysEngineOwned`. Task 2's broker test compares the bytes. The only wire difference is D3: by default a node of this build writes nothing to `maestro.admin.events`.

## What a host sees

Every row is proved by the named test, with contexts built from real `AutoConfigurations.of(KafkaMessagingAutoConfiguration.class, KafkaAutoConfiguration.class)` (Boot's annotation-driven configuration comes in through `KafkaAutoConfiguration`'s `@Import`). `.withBean` is used only for collaborators that are not under test (`ObjectMapper`, a mocked `WorkflowExecutor`). Host beans are declared in user `@Configuration` classes, as a host would declare them.

| # | Host setup | Today (`main` @ `834a9da`) | After WP7 | Pinned by |
|---|---|---|---|---|
| 1 | No Kafka config of its own | Boot's `kafkaTemplate`, `kafkaProducerFactory` and `kafkaConsumerFactory` do not exist. `spring.kafka.template.*`, `ProducerListener`, `RecordMessageConverter` and the factory customizers reach nothing. | Boot's three exist beside Maestro's three. The engine holds exactly Maestro's instances: `ByteArraySerializer`, `acks=all`. | Task 1 `withNoHostKafkaConfig_bootsBeansExistAndTheEngineKeepsMaestros` |
| 2 | Injects `KafkaTemplate<String, MyDto>` with `spring.kafka.producer.value-serializer` set | Startup fails: no qualifying bean (traced) | Starts. The template is Boot's, with the configured serializer. Maestro's factory still forces `ByteArraySerializer`. A real round trip on both. | Task 1 `aHostTemplateForItsOwnType_startsWithBootsSerializers`; Task 2 round trip |
| 3 | Declares its own `ProducerFactory`/`KafkaTemplate`/`ConsumerFactory<String, MyDto>` | Works. Boot's beans back off. | Same, and Boot's beans back off for the same reason as without Maestro. The engine is unaffected. | Task 1 `aHostsOwnKafkaBeans_replaceBootsAndLeaveTheEngineAlone` (pin; mutation-checked) |
| 3b | Declares only `KafkaTemplate<String, MyDto>` over an injected `ProducerFactory<String, MyDto>` | Startup fails: no matching factory (traced) | Gets Boot's `kafkaProducerFactory` | Task 1 `aHostTemplateOverBootsProducerFactory_getsBootsFactory` |
| 3c | Replaces `maestroKafkaTemplate` by name | Replaced (name condition) | With `defaultCandidate = false`: replaced, and Boot's `kafkaTemplate` exists. With a plain `@Bean`: replaced, and Boot's `kafkaTemplate` backs off, as today. | Task 1 `aHostReplacingMaestrosTemplateByName_isWhatTheEngineUses` |
| 4 | Plain `@KafkaListener` beside `@MaestroSignalListener` | Host listener: Boot's `kafkaListenerContainerFactory` over an inline consumer factory that ignores `KafkaConnectionDetails` and customizers. It uses the host's `CommonErrorHandler` bean and gets no reply template. Maestro listener: `maestroKafkaConsumerFactory`, `ByteArrayDeserializer`, Maestro's dead-lettering handler, group `maestro-<service>`, `AckMode.RECORD`. | Host listener: same factory, now over Boot's `kafkaConsumerFactory` bean (connection details and customizers apply), with `spring.kafka.consumer.*` deserializers, `spring.kafka.listener.*`, its own group, the host's error handler and Boot's `kafkaTemplate` as reply template. **No DLT unless the host configures one.** Maestro listener: unchanged; never sees the host's error handler, deserializer, group or `spring.kafka.listener.*` (except `observation-enabled`). | Task 2 `KafkaListenerBoundaryTest` |
| 5 | Sets `spring.kafka.producer.transaction-id-prefix` | Startup fails: `IllegalArgumentException: The 'ProducerFactory' must support transactions` (inferred; Task 1 Step 3 records what is observed) | `kafkaTransactionManager` exists over Boot's transactional `kafkaProducerFactory`. The engine's producer is not transactional. | Task 1 `aTransactionIdPrefix_givesBootsTransactionManagerOverBootsFactory` |
| 6 | Injects `KafkaTemplate<String, byte[]>` without a qualifier (the docs' old advice; D5) | Gets Maestro's template | Without Task 4: gets Boot's, and every send throws `SerializationException` (traced; §Verified 3). With Task 4: startup fails, naming the bean, the injection point and both fixes. If the host set `spring.kafka.producer.value-serializer=…ByteArraySerializer`, it starts with Boot's template, legitimately. | Task 4 `ByteArrayTemplateInjectionCheckTest` |

Traced only, not pinned: with `spring.kafka.retry.topic.enabled=true`, Boot's `RetryTopicConfiguration` is built over Maestro's `byte[]` template today, and over Boot's after the fix.

## Global Constraints

- Branch `fix/kafka-coexistence`, cut from `origin/main`, in the dedicated worktree `/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp7-kafka-coexistence` (see "Before you start"). This plan file lives on `docs/engine-hardening-cycle`; read it from the main checkout's absolute path.
- `./gradlew --no-watch-fs` always, from the worktree root. No two Gradle runs on one module in one tree. No `git stash`. Never run git commands that mutate the repository in parallel.
- Commit at every checkpoint named below. Chain each git sequence with `&&`. Pass messages as `-m "$(cat <<'EOF' … EOF)"`. End each message with the attribution line the implementer's own session supplies. `git status` must show only the intended files before every commit.
- Any run over about 5 minutes (`./gradlew --no-watch-fs build`, the whole of `:maestro-integration-tests:test`, `:maestro-integration-tests:e2eTest`, `run-e2e.sh`) is a **coordinator gate**. The coordinator launches it detached, with a log whose header records `pwd`, `git rev-parse HEAD` and a timestamp. The implementer runs only the targeted commands in the steps.
- **Never auto-create Kafka topics.** No test or production change may declare a `NewTopic` bean, enable broker auto-create, or rely on a send creating a topic. Every broker test pre-creates its topics, `.DLT` companions included, through `KafkaTestSupport.createTopics` against its container (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`).
- **Never publish a lifecycle event on the workflow thread.** Nothing here touches `LifecycleEventSink` or `GatedWorkflowMessaging`. D3 changes only the property default they already read.
- **Engine wire format and group ids do not change.** No serializer, `acks`, header, topic name or group id changes on `maestroKafkaProducerFactory`, `maestroKafkaConsumerFactory` or any Maestro container (mixed-version fleets, existing topics and committed offsets).
- **An existing test is changed only as a named step** (spec anti-goal 4). The named changes are:
  - Task 1 Step 6: `KafkaMessagingAutoConfigurationPropertiesTest`;
  - Task 3 Step 3: the three loan `*ContextBootTest`s;
  - Task 5 Step 3: four `maestro-admin-client` tests deleted;
  - Task 6 Step 4: `MaestroAutoConfigurationLifecycleEventsTest`, `KafkaTopicVerificationStartupTest`, `KafkaLifecycleEventsIT`, `AdminCommandKafkaIT` and `KafkaDuplicateDeliveryIT`.

  A test that fails for any other reason means stop and ask. Do not adjust it. The reviewer reads the diff of every pre-existing test file in full. Do not use a line-based "no assertion changed" grep.
- Every fix starts from a test that fails on the unfixed code by asserting a positive fact. Each task records its mutation results in the commit body. A mutation is reverted in the same step (`git checkout -- <file>`), and `git diff` must be empty for the mutated file before moving on.
- Documentation states only what a test shows, or is labelled "(traced)". Doc edits that quote a test name must use the real name.
- Jackson 3 (`tools.jackson`) only, except `com.fasterxml.jackson.annotation`. JSpecify `@Nullable`. No Lombok. Javadoc on every public member. Thread-safety note on every new public class.
- `docs/observability.md` is not edited. The k6 branch is changing its meter counts. Nothing in this package changes a meter.
- New issues take the next free numbers in `docs/open-issues.md` at implementation time (49 is the highest on `main` @ `834a9da`).
- Gates before merge (spec §6): Build & Test (`./gradlew --no-watch-fs build`); the loan-origination E2E; the chaos PR-gate ×3. No soak for WP7. The maintainer merges.

## Review Focus

1. **A host that copied the old docs and injects `KafkaTemplate<String, byte[]>` by field, not by constructor**, or through an `@Bean` method parameter. A reasonable person expects the same clear startup failure as for a constructor. → Task 4 `fieldInjection_isCaughtToo`, `beanMethodParameter_isCaughtToo`.
2. **A host whose Boot template legitimately carries `byte[]`** (`spring.kafka.producer.value-serializer=ByteArraySerializer`, as `maestro-admin` does) and injects it without a qualifier. It must start and use Boot's template, not be refused. → Task 4 `bootTemplateConfiguredForByteArray_starts`.
3. **A Testcontainers `@ServiceConnection` (`KafkaConnectionDetails`) user.** Both the host's plain listener and Maestro's engine clients must reach the connection-details broker, not `spring.kafka.bootstrap-servers`. → Task 2 `aPlainKafkaListenerHonoursKafkaConnectionDetails` (red today for the host side).
4. **A dashboard user upgrading in a rolling deploy** who never set `maestro.admin.events.enabled`. Nodes of this build stop publishing, so the dashboard goes quiet with no error. A reasonable person expects the release note to say so and give the one-line fix. → Task 6 Step 7 release-note text, and `byDefault_noLifecycleEventIsPublished` pins the behaviour.
5. **A host that replaces `maestroKafkaConsumerFactory` by name with a plain `@Bean`** (a default candidate). Its unqualified `ConsumerFactory` points and Boot's `kafkaConsumerFactory` back-off then behave as today, and the engine must still use the replacement. → Task 1 `aHostReplacingMaestrosTemplateByName_isWhatTheEngineUses` covers the template. Step 2 adds the consumer-factory variant as `aHostReplacingMaestrosConsumerFactoryByName_isWhatTheEngineUses`.

## Before you start

```bash
git -C /Users/rakheendama/Projects/2026/maestro fetch origin
git -C /Users/rakheendama/Projects/2026/maestro worktree add -b fix/kafka-coexistence \
    /Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp7-kafka-coexistence origin/main
WT=/Users/rakheendama/Projects/2026/maestro/.claude/worktrees/wp7-kafka-coexistence
git -C "$WT" status && git -C /Users/rakheendama/Projects/2026/maestro status   # prove which tree you are in
```

Run every command from `$WT`; all paths below are relative to it. Never `cd` into the main checkout.

## File structure

| File | Responsibility |
|---|---|
| `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/MaestroKafkaBeans.java` (new) | The three bean names: the only spelling of them in main code |
| `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/config/KafkaMessagingAutoConfiguration.java` | `defaultCandidate = false`, qualifiers, no `beforeName`; registers the Task 4 check; Javadoc |
| `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/config/ByteArrayTemplateInjectionCheck.java` (new, Task 4) | Startup failure for an unqualified `byte[]` template whose serializer cannot take `byte[]` |
| `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/listener/MaestroSignalListenerBeanPostProcessor.java` | By-name lookups only; Javadoc wording on the admin topic |
| `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/config/KafkaCoexistenceContextTest.java` (new) | Host setups 1, 2, 3, 3b, 3c, 5 and ordering |
| `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/config/CoexistenceFixtures.java` (new) | Shared test fixtures: `HostDto`, `HostDtoSender`, `PropertiesConfiguration`, `producerConfig(ctx, name)`, `consumerConfig(container)` |
| `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/listener/KafkaListenerBoundaryTest.java` (new) | Host setup 4 (todo A6) and connection details |
| `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/KafkaCoexistenceRoundTripTest.java` (new) | The real broker round trip: host DTO, engine bytes, DLT bytes |
| `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/config/ByteArrayTemplateInjectionCheckTest.java` (new) | Host setup 6 |
| Five sample activity classes, three loan boot tests | D5 migration in the repo (Task 3) |
| `maestro-admin-client/**` | Deletion of the dead bean (Task 5) |
| `maestro-spring-boot-starter/.../MaestroProperties.java`, `maestro-core/.../WorkflowExecutor.java` (Javadoc only) | D3 |
| `docs/configuration.md`, `docs/admin.md`, `docs/getting-started.md`, `docs/cross-service.md`, `docs/release-notes.md`, `docs/open-issues.md`, `demo/RUNBOOK.md`, `CLAUDE.md`, `tasks/todo.md` | Docs, each changed in the task whose behaviour it describes |

---

### Task 1: Maestro's Kafka beans stop suppressing Boot's (3.4)

**Files:**
- Create: `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/MaestroKafkaBeans.java`
- Modify: `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/config/KafkaMessagingAutoConfiguration.java:42-126` (Javadoc, annotation), `:137-227` (three beans), `:319-335` (`kafkaWorkflowMessaging`)
- Modify: `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/listener/MaestroSignalListenerBeanPostProcessor.java:371-401`
- Create: `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/config/CoexistenceFixtures.java`, `KafkaCoexistenceContextTest.java`
- Modify (named step): `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/config/KafkaMessagingAutoConfigurationPropertiesTest.java`
- Modify: `docs/configuration.md:1038-1057, 1078-1087`, `docs/release-notes.md` (Unreleased)

**Interfaces:**
- Produces: `MaestroKafkaBeans.TEMPLATE`, `.PRODUCER_FACTORY`, `.CONSUMER_FACTORY` (Strings, values above).
- Produces (test, package `io.b2mash.maestro.messaging.kafka.config`, `public` so the `listener` and root test packages can use it): `final class CoexistenceFixtures` with
  - `public record HostDto(String name) {}`
  - `public record HostDtoSender(KafkaTemplate<String, HostDto> template) {}`
  - `@Configuration(proxyBeanMethods = false) public static class HostSenderConfiguration { @Bean HostDtoSender hostDtoSender(KafkaTemplate<String, HostDto> template) }`
  - `@Configuration(proxyBeanMethods = false) @EnableConfigurationProperties(MaestroProperties.class) public static class PropertiesConfiguration {}`
  - `public static ApplicationContextRunner coexistenceRunner()`: real `AutoConfigurations.of(KafkaMessagingAutoConfiguration.class, KafkaAutoConfiguration.class)`, `PropertiesConfiguration`, `.withBean(ObjectMapper.class, ObjectMapper::new)`, properties `maestro.service-name=coexist`, `spring.kafka.bootstrap-servers=localhost:1`, `maestro.messaging.topics.verify-on-startup=off`.
  - `public static Map<String, Object> producerConfig(ApplicationContext ctx, String beanName)` (the named `ProducerFactory`'s `getConfigurationProperties()`), and `public static Map<String, Object> consumerConfig(MessageListenerContainer container)` (`((ConsumerFactory<?, ?>) ReflectionTestUtils.getField(container, "consumerFactory")).getConfigurationProperties()`).

- [ ] **Step 1: Add `MaestroKafkaBeans`** (constants only, no behaviour), so the tests below compile against `main`'s behaviour and fail on assertions, not on compilation.

- [ ] **Step 2: Write `KafkaCoexistenceContextTest`** (`@DisplayName("Spring Boot's Kafka beans coexist with Maestro's")`). All tests use `coexistenceRunner()`. `getField` is `ReflectionTestUtils.getField`.

```java
@Test void withNoHostKafkaConfig_bootsBeansExistAndTheEngineKeepsMaestros() {
    coexistenceRunner().run(ctx -> {
        assertThat(ctx).hasNotFailed()
            .hasBean("kafkaTemplate").hasBean("kafkaProducerFactory").hasBean("kafkaConsumerFactory")
            .hasBean("kafkaListenerContainerFactory")
            .hasBean(TEMPLATE).hasBean(PRODUCER_FACTORY).hasBean(CONSUMER_FACTORY);
        var engine = ctx.getBean(KafkaWorkflowMessaging.class);
        assertThat(getField(engine, "kafkaTemplate")).isSameAs(ctx.getBean(TEMPLATE));
        assertThat(getField(engine, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
        assertThat(ctx.getBean(TEMPLATE, KafkaTemplate.class).getProducerFactory()).isSameAs(ctx.getBean(PRODUCER_FACTORY));
        assertThat(producerConfig(ctx, PRODUCER_FACTORY))
            .containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class).containsEntry(ACKS_CONFIG, "all");
        assertThat(producerConfig(ctx, "kafkaProducerFactory")).containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        assertThat(ctx.getBean(KafkaTemplate.class)).as("by type, a host now gets Boot's").isSameAs(ctx.getBean("kafkaTemplate"));
    });
}

@Test void aHostTemplateForItsOwnType_startsWithBootsSerializers() {
    coexistenceRunner().withUserConfiguration(HostSenderConfiguration.class)
        .withPropertyValues("spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JacksonJsonSerializer")
        .run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(HostDtoSender.class).template()).isSameAs(ctx.getBean("kafkaTemplate"));
            assertThat(producerConfig(ctx, "kafkaProducerFactory")).containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
            assertThat(producerConfig(ctx, PRODUCER_FACTORY)).containsEntry(VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        });
}

@Test void aHostsOwnKafkaBeans_replaceBootsAndLeaveTheEngineAlone() {
    // user config HostOwnBeansConfiguration: @Bean ProducerFactory<String, HostDto> hostProducerFactory(),
    //   @Bean KafkaTemplate<String, HostDto> hostTemplate(ProducerFactory<String, HostDto> pf),
    //   @Bean ConsumerFactory<String, HostDto> hostConsumerFactory()   (DefaultKafka*Factory over localhost:1)
    // assert: hasNotFailed; doesNotHaveBean("kafkaTemplate"/"kafkaProducerFactory"/"kafkaConsumerFactory");
    //   hostTemplate.getProducerFactory() isSameAs hostProducerFactory; engine identities exactly as in the first test
}

@Test void aHostTemplateOverBootsProducerFactory_getsBootsFactory() {
    // user config: only @Bean KafkaTemplate<String, HostDto> hostTemplate(ProducerFactory<String, HostDto> pf)
    // assert: hasNotFailed; hostTemplate.getProducerFactory() isSameAs ctx.getBean("kafkaProducerFactory");
    //   doesNotHaveBean("kafkaTemplate")   (Boot's template backs off for the host's, as without Maestro)
}

@Test void aHostReplacingMaestrosTemplateByName_isWhatTheEngineUses() {
    // (a) user @Bean(name = TEMPLATE, defaultCandidate = false) KafkaTemplate<String, byte[]> over a String/ByteArray
    //     DefaultKafkaProducerFactory, with setDefaultTopic("host-replacement") as a marker:
    //     engine's kafkaTemplate isSameAs ctx.getBean(TEMPLATE) and its getDefaultTopic() is "host-replacement"; hasBean("kafkaTemplate")
    // (b) the same bean as a plain @Bean(name = TEMPLATE): engine uses it; doesNotHaveBean("kafkaTemplate")  (as today)
}

@Test void aHostReplacingMaestrosConsumerFactoryByName_isWhatTheEngineUses() {
    // user @Bean(name = CONSUMER_FACTORY) ConsumerFactory<String, byte[]> (plain @Bean, a default candidate):
    // engine's consumerFactory isSameAs it; doesNotHaveBean("kafkaConsumerFactory")
}

@Test void aTransactionIdPrefix_givesBootsTransactionManagerOverBootsFactory() {
    coexistenceRunner().withPropertyValues("spring.kafka.producer.transaction-id-prefix=tx-").run(ctx -> {
        assertThat(ctx).hasNotFailed();
        var tm = ctx.getBean("kafkaTransactionManager", KafkaTransactionManager.class);
        assertThat(tm.getProducerFactory()).isSameAs(ctx.getBean("kafkaProducerFactory"));
        assertThat(tm.getProducerFactory().transactionCapable()).isTrue();
        assertThat(ctx.getBean(PRODUCER_FACTORY, ProducerFactory.class).transactionCapable()).isFalse();
        assertThat(ctx.getBean(TEMPLATE, KafkaTemplate.class).isTransactional()).isFalse();
    });
}

@Test void bootsBeansCoexistWhicheverAutoConfigurationRunsFirst() {
    // new ApplicationContextRunner().withConfiguration(UserConfigurations.of(KafkaAutoConfiguration.class,
    //     KafkaMessagingAutoConfiguration.class)) + PropertiesConfiguration + ObjectMapper + the same three properties.
    // UserConfigurations keep their order, so Boot's conditions are evaluated first here; AutoConfigurations puts
    // Maestro first (alphabetical). Assert the order really was Boot-first:
    //   List.of(ctx.getBeanDefinitionNames()).indexOf("kafkaTemplate") < indexOf(TEMPLATE)
    // then the same bean and identity assertions as the first test.
}
```

- [ ] **Step 3: Run against `main`'s behaviour. Record every outcome, the transaction test's in particular.**

Run: `./gradlew --no-watch-fs :maestro-messaging-kafka:test --tests '*KafkaCoexistenceContextTest'`

Expected:
- FAIL `withNoHostKafkaConfig…` (no bean `kafkaTemplate`).
- FAIL `aHostTemplateForItsOwnType…` (startup failure: no qualifying bean of type `KafkaTemplate<String, HostDto>`).
- PASS `aHostsOwnKafkaBeans…`.
- FAIL `aHostTemplateOverBootsProducerFactory…`.
- FAIL `aHostReplacingMaestrosTemplateByName…`, part (a) (any reason; record it).
- PASS `aHostReplacingMaestrosConsumerFactoryByName…`.
- FAIL `bootsBeansCoexistWhicheverAutoConfigurationRunsFirst`.
- `aTransactionIdPrefix…`: copy the startup failure's root-cause class and message into a note for Step 8 and the commit body. Expected (inferred): `IllegalArgumentException: The 'ProducerFactory' must support transactions`. **If the context starts on `main` instead**, the "never exists" sentence was true before the fix. Then write that into Step 8 and continue.

- [ ] **Step 4: Implement.**
  - `KafkaMessagingAutoConfiguration`:
    - Each of the three beans becomes `@Bean(name = MaestroKafkaBeans.X, defaultCandidate = false)` with `@ConditionalOnMissingBean(name = MaestroKafkaBeans.X)`.
    - `maestroKafkaTemplate`'s factory parameter gets `@Qualifier(MaestroKafkaBeans.PRODUCER_FACTORY)`. `kafkaWorkflowMessaging`'s template and consumer-factory parameters get `@Qualifier(MaestroKafkaBeans.TEMPLATE)` and `@Qualifier(MaestroKafkaBeans.CONSUMER_FACTORY)`.
    - Delete `beforeName` and the comment above it (`:102-115`). In its place, write one comment saying that Maestro's Kafka beans are not default candidates, so Boot's type-conditioned Kafka beans register whichever configuration runs first (`KafkaCoexistenceContextTest.bootsBeansCoexistWhicheverAutoConfigurationRunsFirst`).
    - Class Javadoc: replace "All beans are guarded with `ConditionalOnMissingBean` to allow user overrides" with the two doc rules from "Added surface".
    - Javadoc on each of the three bean methods: "Not a default candidate: inject with `@Qualifier(MaestroKafkaBeans.X)`; replace by declaring a bean of this name."
  - `MaestroSignalListenerBeanPostProcessor.resolveKafkaTemplate`/`resolveConsumerFactory`: by name only, through the constants. Delete the by-type fallback. Rewrite the Javadoc to say why: the name always exists, and by type it would resolve Boot's bean.

- [ ] **Step 5: Run to green.**

Run: `./gradlew --no-watch-fs :maestro-messaging-kafka:test --tests '*KafkaCoexistenceContextTest'`
Expected: PASS (8 tests).

- [ ] **Step 6: Named change to an existing test, `KafkaMessagingAutoConfigurationPropertiesTest`.**
  - Delete `bootsOwnTemplateStaysSuppressed_deliberately`. It asserts the defect. `withNoHostKafkaConfig_bootsBeansExistAndTheEngineKeepsMaestros` asserts its replacement.
  - Reword the class Javadoc and the runner comment. They say Maestro's factories "must register before Boot's type-conditioned ones back off". The new wording: Boot's real `KafkaAutoConfiguration` is included so that `spring.kafka.*` precedence is pinned with Boot's beans present.
  - No other line changes. Then run the module:

Run: `./gradlew --no-watch-fs :maestro-messaging-kafka:test`
Expected: PASS. In particular `engineInvariantsAlwaysWin`, `consumerAutoOffsetResetIsOverridableButGroupIdStaysEngineOwned`, `MaestroSignalListenerContainerConfigTest.userDefinedConsumerFactoryDoesNotBreakActivation_F3` and `KafkaTemplateObservationTest` pass unchanged. If the module takes over ~5 minutes, stop and hand it to the coordinator as a gate.

- [ ] **Step 7: Mutation checks.** Run `--tests '*KafkaCoexistenceContextTest'` after each one. Revert each before the next.
  1. Remove `defaultCandidate = false` from `maestroKafkaTemplate`. Expected FAIL: `withNoHostKafkaConfig…` (`kafkaTemplate` missing).
  2. Remove `@Qualifier(TEMPLATE)` in `kafkaWorkflowMessaging`. Expected FAIL: engine template `isSameAs` (it got Boot's).
  3. Remove `@Qualifier(PRODUCER_FACTORY)` in `maestroKafkaTemplate`. Expected FAIL: `getProducerFactory()` `isSameAs`.
  4. Remove `@Qualifier(CONSUMER_FACTORY)` in `kafkaWorkflowMessaging`. Expected FAIL: consumer identity in the first test, and `aHostsOwnKafkaBeans…` (no qualifying bean).
  5. Remove `defaultCandidate = false` from `maestroKafkaProducerFactory`. Expected FAIL: Boot's `kafkaProducerFactory` backs off, and Boot's `kafkaTemplate(ProducerFactory<Object, Object>)` then finds no factory, so the first test's context fails to start (record the exact failure). The transaction test fails too.

- [ ] **Step 8: Docs.**
  - `docs/configuration.md` § Kafka Client Configuration. Replace `:1038-1057` ("deliberately suppressed" and the two options) with:
    1. Maestro's three beans are not default candidates. Boot's Kafka beans exist beside them, built from the same `spring.kafka.*`. Unqualified injection points get Boot's.
    2. Your own traffic: inject Boot's `KafkaTemplate<String, YourDto>` or declare your own beans, exactly as without Maestro.
    3. To reuse the engine's `byte[]` template, inject it with `@Qualifier(MaestroKafkaBeans.TEMPLATE)`.
    4. To replace an engine bean, declare a bean with its name and `defaultCandidate = false`. A plain `@Bean` also replaces it, but then Boot's bean of that type backs off and your unqualified injection points get yours.
    5. `spring.kafka.producer.*` now configures two producer factories. Each creates its producer on first send, so Boot's opens no connection unless you send through it (traced).

    Replace the transaction sentence in `:1082-1085` with what Step 3 observed. It must say what was true before (the observed failure, quoted) and what is true now: `kafkaTransactionManager` over Boot's transactional factory, and the engine's producer is never transactional, pinned by `aTransactionIdPrefix_givesBootsTransactionManagerOverBootsFactory`. Keep the `default-topic` sentence and narrow it to `maestroKafkaTemplate`. Boot's template now honours `default-topic`.
  - `docs/release-notes.md` Unreleased: add `### Changed — Spring Boot's Kafka beans now coexist with Maestro's (3.4, D5)`, containing:
    - what was wrong (rows 1, 2 and 5 of the table, with the observed transaction failure);
    - what changes;
    - **Breaking:** an unqualified `KafkaTemplate<String, byte[]>`, `ProducerFactory<String, byte[]>` or `ConsumerFactory<String, byte[]>` now receives Boot's bean. Add `@Qualifier(MaestroKafkaBeans.TEMPLATE)` (or `PRODUCER_FACTORY`, `CONSUMER_FACTORY`). A replacement bean declared by name keeps working; declare it with `defaultCandidate = false` to keep Boot's;
    - the wire is unchanged (bytes, `acks`, group ids).

    Task 4 appends the startup-check line.

- [ ] **Step 9: Commit.**

```bash
git add maestro-messaging-kafka/src docs/configuration.md docs/release-notes.md && \
git commit -m "$(cat <<'EOF'
fix(kafka): Maestro's Kafka beans are not default candidates, so Spring Boot's register beside them

<Step 3 observations, including the transaction failure text; Step 7 mutation results>

<attribution line>
EOF
)"
```

---

### Task 2: Pin the plain-`@KafkaListener` boundary (todo A6) and the wire

**Files:**
- Create: `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/listener/KafkaListenerBoundaryTest.java`
- Create: `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/KafkaCoexistenceRoundTripTest.java`
- Modify: `docs/configuration.md:1059-1076` ("Plain `@KafkaListener` consumers"), `tasks/todo.md` (the A6 item: find it by its text "A6 — Boot-compatibility context pin"; it is at `:367` on `main` @ `834a9da`, not `:343`)

**Interfaces:**
- Consumes: `CoexistenceFixtures` (Task 1), `MaestroKafkaBeans`, `KafkaTestSupport` (`kafka`, `createTopics(String...)`, its `byte[]` consumer factory), `MaestroSignalListenerBeanPostProcessor.containersForTesting()`.

- [ ] **Step 1: Write `KafkaListenerBoundaryTest`.**
  - Runner: `coexistenceRunner()` plus `.withBean(WorkflowExecutor.class, () -> mock(WorkflowExecutor.class))` and a user `BoundaryConfiguration` holding:
    - a host bean with `@KafkaListener(id = "host-listener", topics = "host.orders", groupId = "host-group") public void onOrder(Long value)`;
    - a bean with `@MaestroSignalListener(topic = "boundary.signals", signalName = "boundary") public SignalRouting handle(String event)`;
    - `@Bean CommonErrorHandler hostErrorHandler()`, returning `new DefaultErrorHandler(new FixedBackOff(0L, 1L))`.
  - Properties: `maestro.service-name=boundary`, `spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.LongDeserializer`, `spring.kafka.listener.ack-mode=batch`, `spring.kafka.listener.auto-startup=false`.
  - The Maestro container starts and polls `localhost:1` in the background, as in `MaestroSignalListenerContainerConfigTest`. That is expected.

```java
@Test @DisplayName("a plain @KafkaListener rides Boot's factory and beans; a @MaestroSignalListener rides Maestro's; neither leaks into the other")
void aPlainKafkaListenerRidesBootsFactoryAndAMaestroListenerRidesMaestros() {
    runner.run(ctx -> {
        assertThat(ctx).hasNotFailed();
        var host = (ConcurrentMessageListenerContainer<?, ?>) ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("host-listener");
        assertThat(getField(host, "consumerFactory")).isSameAs(ctx.getBean("kafkaConsumerFactory"));
        assertThat(consumerConfig(host)).containsEntry(VALUE_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
        assertThat(host.getContainerProperties().getGroupId()).isEqualTo("host-group");
        assertThat(host.getContainerProperties().getAckMode()).isEqualTo(AckMode.BATCH);
        assertThat(host.getCommonErrorHandler()).isSameAs(ctx.getBean("hostErrorHandler"));
        assertThat(getField(ctx.getBean("kafkaListenerContainerFactory"), "replyTemplate")).isSameAs(ctx.getBean("kafkaTemplate"));

        var maestro = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting().getFirst();
        assertThat(getField(maestro, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
        assertThat(consumerConfig(maestro)).containsEntry(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        assertThat(maestro.getContainerProperties().getGroupId()).isEqualTo("maestro-boundary");
        assertThat(maestro.getContainerProperties().getAckMode()).isEqualTo(AckMode.RECORD);
        assertThat(maestro.getCommonErrorHandler()).isNotSameAs(ctx.getBean("hostErrorHandler")).isInstanceOf(DefaultErrorHandler.class);
    });
}

@Test @DisplayName("KafkaConnectionDetails reach a plain @KafkaListener's consumer and Maestro's")
void aPlainKafkaListenerHonoursKafkaConnectionDetails() {
    // + user @Bean KafkaConnectionDetails connectionDetails() { return () -> List.of("details-host:9092"); }
    // assert consumerConfig(host).get(BOOTSTRAP_SERVERS_CONFIG) hasToString "[details-host:9092]"
    //    and consumerConfig(maestro container) the same
}
```

- [ ] **Step 2: Write `KafkaCoexistenceRoundTripTest`** (`extends KafkaTestSupport`, against a real broker with auto-create off).
  - `@BeforeEach`: `createTopics(HOST_TOPIC, "maestro.signals." + svc, "maestro.signals." + svc + ".DLT", POISON, POISON + ".DLT")`, where `svc = "coexist-" + suffix` and every topic name carries the suffix.
  - Runner: real auto-configurations, `PropertiesConfiguration`, `HostSenderConfiguration`, a `PoisonListenerConfiguration` (`@MaestroSignalListener(topic = POISON, signalName = "poison")`), `.withBean(ObjectMapper.class, ObjectMapper::new)`, and a mocked `WorkflowExecutor` whose `deliverSignal` throws `IllegalStateException("poison")`.
  - Properties:
    - `spring.kafka.bootstrap-servers=<kafka>`, `maestro.service-name=<svc>`;
    - `spring.kafka.producer.value-serializer=…JacksonJsonSerializer`;
    - `maestro.admin.events.enabled=false` (explicit, so Task 6 does not change this test);
    - `maestro.messaging.redelivery.max-attempts=1`;
    - verify left at its default `fail` (every required topic exists).

```java
@Test @DisplayName("on a real broker: the host's template writes JSON, the engine writes its own bytes, a dead-lettered signal keeps its bytes")
void bootsTemplateServesTheHostAndMaestrosServesTheEngine() {
    runner.run(ctx -> {
        assertThat(ctx).hasNotFailed();
        ctx.getBean(HostDtoSender.class).template().send(HOST_TOPIC, "k", new HostDto("widget")).get(10, SECONDS);
        assertThat(new String(readOne(HOST_TOPIC).value(), UTF_8)).contains("\"name\":\"widget\"");

        var mapper = ctx.getBean(ObjectMapper.class);
        var message = new SignalMessage("wf-1", "go", mapper.valueToTree(Map.of("n", 1)), "client:coexist-1");
        ctx.getBean(KafkaWorkflowMessaging.class).publishSignal(svc, message);
        var engineRecord = readOne("maestro.signals." + svc);
        assertThat(engineRecord.value()).isEqualTo(mapper.writeValueAsBytes(message));   // no tracing in this context
        assertThat(engineRecord.key()).isEqualTo("wf-1");

        var poison = "\"boom\"".getBytes(UTF_8);
        kafkaTemplate.send(new ProducerRecord<>(POISON, "p", poison)).get(10, SECONDS);   // KafkaTestSupport's own byte[] template field
        assertThat(readOne(POISON + ".DLT").value()).isEqualTo(poison);
    });
}
```

`readOne(topic)` is a private helper. It subscribes a fresh `KafkaTestSupport` `byte[]` consumer from `earliest`, polls until one record arrives or 30 s pass, and fails with the topic name.

- [ ] **Step 3: Prove all three tests red against the unfixed code.** Restore `main`'s production code for the module, keeping the tests, run, and restore:

```bash
git checkout origin/main -- maestro-messaging-kafka/src/main && \
./gradlew --no-watch-fs :maestro-messaging-kafka:test --tests '*KafkaListenerBoundaryTest' --tests '*KafkaCoexistenceRoundTripTest'; \
git checkout HEAD -- maestro-messaging-kafka/src/main && git status --short maestro-messaging-kafka/src/main
```

(`MaestroKafkaBeans` does not exist on `main`. `git checkout origin/main -- dir` leaves it in place, so the tests still compile.)

Expected FAILs:
- `aPlainKafkaListenerRides…`: host `consumerFactory` is not the `kafkaConsumerFactory` bean, because no such bean exists.
- `aPlainKafkaListenerHonoursKafkaConnectionDetails`: the host side is `[localhost:1]`. This proves §Verified 6.
- `bootsTemplateServesTheHost…`: the context fails (no `KafkaTemplate<String, HostDto>`).

The last command must print nothing. If the connection-details test passes on `main`, record that §Verified 6 was wrong and drop that row from the docs.

- [ ] **Step 4: Run green.**

Run: `./gradlew --no-watch-fs :maestro-messaging-kafka:test --tests '*KafkaListenerBoundaryTest' --tests '*KafkaCoexistenceRoundTripTest'`
Expected: PASS (3 tests).

- [ ] **Step 5: Mutation check.** In `MaestroSignalListenerBeanPostProcessor.resolveKafkaTemplate`, return `(KafkaOperations<String, byte[]>) ctx.getBean(KafkaTemplate.class)`. Expected: `bootsTemplateServesTheHost…` FAILS on the DLT bytes, because Boot's JSON template re-encodes the dead letter. Revert.

- [ ] **Step 6: Docs.**
  - Rewrite "Plain `@KafkaListener` consumers" in `docs/configuration.md` as a two-column table (plain `@KafkaListener` | `@MaestroSignalListener`) with these rows: container factory, consumer factory, deserializers, group id, `spring.kafka.listener.*`, error handler and DLT, reply template, `KafkaConnectionDetails`. Fill it from row 4 of the host table. Name `KafkaListenerBoundaryTest` as the pin.
  - State that before this release a plain listener ignored `KafkaConnectionDetails` and customizers. Write it only if Step 3 showed it.
  - Delete the sentence "Injecting `ConsumerFactory` by type resolves Maestro's `byte[]` factory".
  - Keep the samples sentence.
  - Add the release-notes bullet for the `KafkaConnectionDetails` fix, if Step 3 showed it.
  - In `tasks/todo.md`, tick A6 with "done in WP7: `KafkaListenerBoundaryTest`, `KafkaCoexistenceRoundTripTest`".

- [ ] **Step 7: Commit** (`test(kafka): pin the plain @KafkaListener boundary and the engine's bytes beside Boot's beans`, with the Step 3 and Step 5 results in the body).

---

### Task 3: The samples inject Maestro's template by qualifier (D5 in the repo)

**Files:**
- Modify: the constructor parameter `KafkaTemplate<String, byte[]> kafkaTemplate` in
  `maestro-samples/sample-order-service/src/main/java/io/b2mash/maestro/samples/order/activity/impl/KafkaMessagingActivities.java:30`,
  `maestro-samples/sample-payment-gateway/src/main/java/io/b2mash/maestro/samples/payment/activity/impl/KafkaPaymentMessagingActivities.java:36`,
  `maestro-samples/sample-loan-origination/underwriting-service/src/main/java/io/b2mash/maestro/samples/loan/underwriting/activity/impl/KafkaDecisionMessagingActivities.java:37`,
  `maestro-samples/sample-loan-origination/verification-gateway-service/src/main/java/io/b2mash/maestro/samples/loan/verification/activity/impl/KafkaVerificationMessagingActivities.java:40`,
  `maestro-samples/sample-loan-origination/loan-application-service/src/main/java/io/b2mash/maestro/samples/loan/application/activity/impl/KafkaLoanMessagingActivities.java:38`
- Modify (named step): `LoanApplicationServiceContextBootTest`, `UnderwritingServiceContextBootTest`, `VerificationGatewayContextBootTest` (under each service's `src/test/java/…`)
- Modify: `demo/RUNBOOK.md:984-987`

Why the boot tests change: they exclude `KafkaMessagingAutoConfiguration` (their Javadoc says the activities' template "is still created by Boot's own Kafka auto-configuration"). Once the activities ask for `maestroKafkaTemplate` by qualifier, no such bean exists in those contexts, and they fail with `NoSuchBeanDefinitionException`. These tests are about workflow registration, not Kafka.

- [ ] **Step 1: Write the change in the samples:** `@Qualifier(MaestroKafkaBeans.TEMPLATE) KafkaTemplate<String, byte[]> kafkaTemplate`. No other line.

- [ ] **Step 2: Run the loan boot tests to see the expected failure.**

Run: `./gradlew --no-watch-fs :maestro-samples:sample-loan-origination:loan-application-service:test :maestro-samples:sample-loan-origination:underwriting-service:test :maestro-samples:sample-loan-origination:verification-gateway-service:test`
Expected: the three `*ContextBootTest`s FAIL with `No qualifying bean … maestroKafkaTemplate`. Every other test passes.

- [ ] **Step 3: Named change to three existing tests.**
  - Add a nested `@TestConfiguration(proxyBeanMethods = false) static class MaestroTemplateStandIn` to each. It declares `@Bean(name = MaestroKafkaBeans.TEMPLATE, defaultCandidate = false) KafkaTemplate<String, byte[]> maestroKafkaTemplate()` over `new DefaultKafkaProducerFactory<>(Map.of(BOOTSTRAP_SERVERS_CONFIG, "localhost:1", KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class, VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class))`. It connects lazily, so no broker is needed.
  - Replace the Javadoc sentence about the `KafkaTemplate` with: "the activities' `maestroKafkaTemplate` is a stand-in declared below, because the auto-configuration that declares it is excluded; it connects lazily, so no broker is needed."
  - No assertion changes.

- [ ] **Step 4: Run green.** Same command as Step 2, plus `:maestro-samples:sample-order-service:test :maestro-samples:sample-payment-gateway:test`. Expected: PASS.

- [ ] **Step 5: `demo/RUNBOOK.md:984-987`.** "(which the domain activities also inject, since it is the only `KafkaTemplate<String, byte[]>` bean in the context)" becomes "(which the domain activities also inject, with `@Qualifier(MaestroKafkaBeans.TEMPLATE)`)".

- [ ] **Step 6: Commit** (`fix(samples): inject maestroKafkaTemplate by qualifier`). The loan E2E and the chaos gate exercise these sends end to end (coordinator gates).

---

### Task 4: Fail at startup on an unqualified `byte[]` template that cannot send `byte[]` (D5 guard)

**Files:**
- Create: `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/config/ByteArrayTemplateInjectionCheck.java`
- Modify: `KafkaMessagingAutoConfiguration.java` (one bean)
- Create: `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/config/ByteArrayTemplateInjectionCheckTest.java`
- Modify: `docs/configuration.md` (the Task 1 qualifier paragraph), `docs/release-notes.md`

**Interfaces:**
- Produces: `final class ByteArrayTemplateInjectionCheck implements SmartInitializingSingleton, BeanFactoryAware` (package-private, no-arg constructor), registered as `@Bean ByteArrayTemplateInjectionCheck maestroKafkaTemplateInjectionCheck()`.
- Produces (package-private, static, unit-testable): `static @Nullable Class<?> valueSerializerTarget(KafkaOperations<?, ?> template)` and `static List<String> unqualifiedByteArrayTemplatePoints(Class<?> beanClass, @Nullable Method factoryMethod)`.

**The rule** (the plan fixes it; the body is the implementer's):
- For each bean name of type `KafkaOperations` that has a bean definition, is a default candidate, and is already a singleton:
  - Compute `valueSerializerTarget`:
    - take the producer factory's `getValueSerializer()` class, else its `value.serializer` config (a `Class` or a class name);
    - resolve `ResolvableType.forClass(c).as(Serializer.class).getGeneric(0).resolve()`;
    - return `null` when anything there is unknown.
  - If it is non-null and **not assignable from `byte[]`**, check each of `getDependentBeans(name)`: inspect the dependent's resolved factory method parameters, all its declared constructors' parameters, and its `@Autowired` fields and method parameters.
  - An injection point is a violation when its type is assignable to `KafkaOperations`, `ResolvableType…as(KafkaOperations.class).getGeneric(1).resolve() == byte[].class`, and no annotation on it is or is meta-annotated with `@Qualifier` (`MergedAnnotations.from(element).isPresent(Qualifier.class)`).
- Violations throw one `IllegalStateException` listing each.
- Any exception while inspecting a bean skips that bean at `DEBUG`. The check fails startup only on a confirmed violation.
- `ObjectProvider` and `getBean` lookups, lazy beans and prototypes are out of reach. The Javadoc says so.

Exact message, one block per violation:

```text
Bean '<dependent>' injects a KafkaTemplate<…, byte[]> without a qualifier (<point>) and received '<template bean>', whose value serializer <serializer class> cannot serialize byte[]: every send through it would fail. Maestro's Kafka beans are not injected by type. Inject Maestro's template with @Qualifier(MaestroKafkaBeans.TEMPLATE) ("maestroKafkaTemplate"), or set spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.ByteArraySerializer to send byte[] through Spring Boot's template. See docs/configuration.md § Kafka Client Configuration.
```

`<point>` is `constructor parameter <i> of <class>`, `field <name> of <class>`, `parameter <i> of @Bean method <class>.<method>` or `parameter <i> of method <class>.<method>`.

- [ ] **Step 1: Write the six context tests** (`coexistenceRunner()`). The host beans are not under test. Register the constructor- and field-injected ones with `.withBean("<name>", <Class>.class)`, which autowires the single constructor and `@Autowired` fields and records the dependency. Register the `@Bean`-method case in a user `@Configuration`.

```java
@Test void unqualifiedConstructorInjection_failsStartupNamingTheBeanAndTheFix() {
    // .withBean("legacyBytesSender", LegacyBytesSender.class), where LegacyBytesSender(KafkaTemplate<String, byte[]> maestroKafkaTemplate)
    // assert ctx.hasFailed(); rootCause IllegalStateException with message containing
    //   "'legacyBytesSender'", "constructor parameter 0", "'kafkaTemplate'",
    //   "StringSerializer", "@Qualifier(MaestroKafkaBeans.TEMPLATE)", "ByteArraySerializer"
}
@Test void fieldInjection_isCaughtToo()          // .withBean("fieldSender", FieldSender.class), @Autowired KafkaTemplate<String, byte[]> template; -> failed, "field template of"
@Test void beanMethodParameter_isCaughtToo()     // @Bean Object x(KafkaTemplate<String, byte[]> t) -> failed, "parameter 0 of @Bean method"
@Test void qualifiedInjection_startsAndGetsMaestrosTemplate()   // @Qualifier(TEMPLATE) -> hasNotFailed; injected isSameAs ctx.getBean(TEMPLATE)
@Test void bootTemplateConfiguredForByteArray_starts()          // + spring.kafka.producer.value-serializer=...ByteArraySerializer,
                                                                // unqualified -> hasNotFailed; injected isSameAs ctx.getBean("kafkaTemplate")
@Test void aTemplateForAnotherValueType_isNotChecked()          // HostSenderConfiguration (KafkaTemplate<String, HostDto>) -> hasNotFailed
```

- [ ] **Step 2: Run red.**

Run: `./gradlew --no-watch-fs :maestro-messaging-kafka:test --tests '*ByteArrayTemplateInjectionCheckTest'`
Expected: the three `…isCaught…`/`…failsStartup…` tests FAIL: the context starts, because on this branch the host silently gets Boot's template. The other three PASS.

- [ ] **Step 3: Implement** the class and the bean. Then add the unit test:

```java
@Test void valueSerializerTargetResolvesDeclaredTypes()   // StringSerializer -> String.class; ByteArraySerializer -> byte[].class;
                                                          // JacksonJsonSerializer -> null; a factory without value.serializer -> null
```

- [ ] **Step 4: Run green.**

Run: `./gradlew --no-watch-fs :maestro-messaging-kafka:test`
Expected: PASS for the whole module (no Maestro test context trips the check).

- [ ] **Step 5: Mutation checks.** Revert each.
  1. Skip fields. Expected: `fieldInjection_isCaughtToo` FAILS.
  2. Treat a qualified point as a violation. Expected: `qualifiedInjection…` FAILS.
  3. Make `valueSerializerTarget` return `String.class` always. Expected: `bootTemplateConfiguredForByteArray_starts` FAILS.

- [ ] **Step 6: Docs.**
  - In the Task 1 qualifier paragraph of `docs/configuration.md`, add: a service that injects a `byte[]` template without a qualifier, and whose Boot template cannot serialize `byte[]`, fails at startup with the message above; quote its first sentence. Also add the reach limits (constructor, field, `@Bean` and `@Autowired` method parameters of beans created at startup).
  - Append a bullet to the Task 1 release-notes entry saying the same.

- [ ] **Step 7: Commit** (`feat(kafka): fail startup when an unqualified byte[] KafkaTemplate gets a template that cannot send byte[]`).

---

### Task 5: Delete the dead `AdminEventPublisher` bean

**Files:**
- Delete: `maestro-admin-client/src/main/java/io/b2mash/maestro/admin/client/AdminClientAutoConfiguration.java`, `AdminClientProperties.java`, `maestro-admin-client/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Delete (named step): `maestro-admin-client/src/test/java/io/b2mash/maestro/admin/client/AdminClientAutoConfigurationTest.java`, `AdminClientAutoConfigurationMaestroDisabledTest.java`, `AdminClientTopicResolutionTest.java`, `AdminClientPropertiesTest.java`
- Create: `maestro-admin-client/src/test/java/io/b2mash/maestro/admin/client/AdminClientContributesNoAutoConfigurationTest.java`
- Modify: `AdminEventPublisher.java` (Javadoc), `package-info.java`, `maestro-admin-client/build.gradle.kts` (drop `spring.boot.autoconfigure` and the configuration processor if the module still compiles without them), `KafkaMessagingAutoConfiguration.resolveAdminEventsTopic` Javadoc (drop the "twin" paragraph), `docs/admin.md:151-160, 335-343`, `docs/configuration.md:32`, `docs/release-notes.md`

Why delete rather than qualify, and why keep the class:
- Nothing calls the bean (§Verified 7). Lifecycle events go `GatedWorkflowMessaging` → `KafkaWorkflowMessaging.publishLifecycleEvent`.
- Qualifying it with `maestroKafkaTemplate` would break `maestro-admin`, which has admin-client but no Maestro template.
- With the bean gone, `AdminClientAutoConfiguration` and `AdminClientProperties` configure nothing ("config that reads correctly and does nothing", lessons 2026-08-04), so they go too.
- `AdminEventPublisher` stays as a plain class with its unit test. Deleting it would empty a published module, and removing a module is the maintainer's call (Task 7 files it).
- `maestro.admin.events.*` keeps binding through `MaestroProperties` in the starter.

- [ ] **Step 1: Prove it is dead.**

Run: `grep -rn 'AdminEventPublisher\|AdminClientProperties\|AdminClientAutoConfiguration' --include='*.java' --include='*.kts' --include='*.imports' . | grep -v /build/ | grep -v '^./maestro-admin-client/'`
Expected: only the Javadoc line in `KafkaMessagingAutoConfiguration.java` ("Twin of `AdminClientAutoConfiguration.resolveTopic`"). Anything else means stop and report.

- [ ] **Step 2: Write the red test.**

```java
@Test @DisplayName("maestro-admin-client contributes no auto-configuration: lifecycle events are published by the engine's WorkflowMessaging")
void contributesNoAutoConfiguration() {
    assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
        .noneMatch(name -> name.startsWith("io.b2mash.maestro.admin.client"));
}
```

Run: `./gradlew --no-watch-fs :maestro-admin-client:test --tests '*AdminClientContributesNoAutoConfigurationTest'`
Expected: FAIL, listing `io.b2mash.maestro.admin.client.AdminClientAutoConfiguration`.

- [ ] **Step 3: Named deletion of four existing tests and the production files.** Delete the files listed above. Update the Javadocs: the publisher is not auto-configured, and the engine publishes lifecycle events through `maestro-messaging-kafka`.

- [ ] **Step 4: Run green.**

Run: `./gradlew --no-watch-fs :maestro-admin-client:test :maestro-admin:test`
Expected: PASS (`maestro-admin` never referenced the bean).

- [ ] **Step 5: Docs.**
  - `docs/admin.md` § "Enabling Event Publishing in Services": a service needs no extra dependency. It sets `maestro.admin.events.enabled: true`; Task 6 makes that necessary. The admin-client properties table goes.
  - `docs/configuration.md:32`: drop admin-client from the cross-module `maestro.enabled` note.
  - Release note `### Removed — the unused AdminEventPublisher bean`: it was never called, it injected the engine's template by type, and the `AdminEventPublisher` class remains for manual use.

- [ ] **Step 6: Commit** (`refactor(admin-client): delete the unused AdminEventPublisher auto-configuration`).

---

### Task 6: `maestro.admin.events.enabled` defaults to `false` (3.11c, D3)

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroProperties.java:565-590`
- Modify (Javadoc/comments only): `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowExecutor.java:390-393`, `MaestroSignalListenerBeanPostProcessor.java:50-59, 174-176`
- Test (new methods): `MaestroPropertiesBindingTest`, `MaestroAutoConfigurationLifecycleEventsTest`, `KafkaTopicVerificationStartupTest`
- Modify (named step): `MaestroAutoConfigurationLifecycleEventsTest`, `KafkaTopicVerificationStartupTest`, `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/kafka/{KafkaLifecycleEventsIT,AdminCommandKafkaIT,KafkaDuplicateDeliveryIT}.java`
- Modify: `docs/configuration.md:384, 854-864, 916-942 (example comment), 1108, 1125`, `docs/admin.md:139`, `docs/getting-started.md:131`, `docs/cross-service.md:257`, `docs/release-notes.md`, `CLAUDE.md` ("Configuration Namespace", the `maestro.admin.events.enabled` clause)

What D3 touches, item by item:
- **The verifier.** It already requires the admin topic only when events are enabled. With the default flipped, a fresh service no longer needs the topic. No code change; the new test pins it.
- **The `maestro.admin.events.topic` alias.** Unchanged.
- **The samples, `run-e2e.sh`, the demo and the chaos cluster.** They set the property explicitly (§Verified 8). No change; their topic-creation lines stay, and an unused topic does no harm.
- **The `maestro-admin` dashboard.** No code change. It shows nothing from a service that has not set `true`, which is what the docs and the release note say.
- **The engine builder.** Its default stays `true` (see "Changed surface").

- [ ] **Step 1: Write the new tests.**

```java
// MaestroPropertiesBindingTest
@Test @DisplayName("admin lifecycle events are off unless enabled (D3)")
void adminEventsAreOffByDefault() {
    runner.withPropertyValues("maestro.service-name=binding-test").run(ctx ->
        assertThat(ctx.getBean(MaestroProperties.class).getAdmin().events().enabled()).isFalse());
    assertThat(MaestroProperties.EventsProperties.defaults().enabled()).isFalse();
}

// MaestroAutoConfigurationLifecycleEventsTest
@Test @DisplayName("by default no lifecycle event is published, ACTIVITY_* included")
void byDefault_noLifecycleEventIsPublished() {
    runner.run(ctx -> {
        var result = ctx.getBean(MaestroClient.class).newWorkflow(ActivityWorkflow.class, options("lifecycle-default-1"))
            .startAndWait("world", Duration.ofSeconds(10), String.class);
        assertThat(result).isEqualTo("hello world");
        var messaging = (InMemoryWorkflowMessaging) ctx.getBean(WorkflowMessaging.class);
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(2)).until(() -> messaging.getLifecycleEvents().isEmpty());
    });
}

// KafkaTopicVerificationStartupTest (broker)
@Test @DisplayName("fail: by default the admin-events topic is not required")
void failMode_adminTopicIsNotRequiredByDefault() throws Exception {
    // service "verify-noadmin-" + suffix(); createTopics(signal, signal.DLT, NOADMIN_TOPIC, NOADMIN_TOPIC + ".DLT");
    // a NoAdminListenerConfiguration with @MaestroSignalListener(topic = NOADMIN_TOPIC); no admin property at all
    // assert hasNotFailed; log contains "Verified 4 Kafka topic(s)"
}
```

- [ ] **Step 2: Run red.**

Run: `./gradlew --no-watch-fs :maestro-spring-boot-starter:test --tests '*MaestroPropertiesBindingTest' --tests '*MaestroAutoConfigurationLifecycleEventsTest'` and `./gradlew --no-watch-fs :maestro-messaging-kafka:test --tests '*KafkaTopicVerificationStartupTest'`
Expected:
- FAIL `adminEventsAreOffByDefault` (`true`).
- FAIL `byDefault_noLifecycleEventIsPublished` (events recorded).
- FAIL `failMode_adminTopicIsNotRequiredByDefault` (startup fails naming `maestro.admin.events`).
- Everything else passes.

- [ ] **Step 3: Flip the default.** `@DefaultValue("false") boolean enabled`; `defaults()` returns `new EventsProperties(false, "maestro.admin.events")`. Javadoc: off by default, because it costs two producer sends per live activity (started, then completed or failed) plus the workflow, signal, timer and compensation events, and because it requires the admin topic to exist. Dashboard users set it on every service. Update the `WorkflowExecutor.Builder.lifecycleEventsEnabled` Javadoc: "Default here: `true`. The starter's `maestro.admin.events.enabled` defaults to `false` and is passed in explicitly." Update the two `MaestroSignalListenerBeanPostProcessor` comments: "the admin-events topic, when lifecycle events are enabled".

- [ ] **Step 4: Named change to existing tests that encode the old default.** Add the property; no assertion changes.
  - `MaestroAutoConfigurationLifecycleEventsTest.adminEventsEnabledByDefault_publishesLifecycleEvents` → rename `adminEventsEnabled_publishesLifecycleEvents`, add `.withPropertyValues("maestro.admin.events.enabled=true")`, set DisplayName "lifecycle publishing happens when enabled". Do the same for `adminEventsEnabledByDefault_publishesActivityLifecycleEvents` → `adminEventsEnabled_publishesActivityLifecycleEvents`. Update the "positive control" comment to say "enabled".
  - `KafkaTopicVerificationStartupTest.failMode_missingTopicsFailStartup`, `failMode_allPresentStartsWithFatalContainers` and `warnMode_startsAndWarns`: add `"maestro.admin.events.enabled=true"` beside their `maestro.messaging.topics.admin-events` property.
  - `KafkaLifecycleEventsIT`, `AdminCommandKafkaIT`, `KafkaDuplicateDeliveryIT`: add `"maestro.admin.events.enabled=true"` to `@SpringBootTest(properties = …)`. In `KafkaLifecycleEventsIT`'s Javadoc, say that the suite enables events explicitly because they are off by default.

  Before running, confirm the list is complete:
  `grep -rlE 'getLifecycleEvents|recorderFor\(ADMIN_TOPIC\)|LifecycleEventType\.' --include='*.java' maestro-*/src/test maestro-samples | xargs grep -LE 'admin\.events\.enabled'`
  Inspect every file printed that builds a Spring context (`ApplicationContextRunner`, `@SpringBootTest`). Files that build a `WorkflowExecutor` directly use the builder's `true` default and are unaffected. Any other Spring-context file that expects events means stop and ask.

- [ ] **Step 5: Run green.**

Run: `./gradlew --no-watch-fs :maestro-spring-boot-starter:test` then `./gradlew --no-watch-fs :maestro-messaging-kafka:test --tests '*KafkaTopicVerificationStartupTest'` then `./gradlew --no-watch-fs :maestro-integration-tests:test --tests '*KafkaLifecycleEventsIT' --tests '*AdminCommandKafkaIT' --tests '*KafkaDuplicateDeliveryIT'`
Expected: PASS.

- [ ] **Step 6: Mutation check.** Put `@DefaultValue("true")` back. Expected: the three Step 1 tests FAIL. Revert.

- [ ] **Step 7: Docs.**
  - `docs/configuration.md`:
    - `:854` default → `false`;
    - replace the closing paragraph with the cost, the required topic, and "set `true` on every service the dashboard should show";
    - `:384` "(default)" → "(default `false`)";
    - `:1108` → "lifecycle events enabled";
    - `:1125` → "only with `maestro.admin.events.enabled: true`";
    - add the comment `# off by default; on here for the dashboard` to the Complete Example's `enabled: true`.
  - `docs/admin.md:139`: invert the note (set `true` in each service the dashboard should observe).
  - `docs/getting-started.md:131`: "(only with `maestro.admin.events.enabled: true`, for the dashboard)".
  - `docs/cross-service.md:257`: "(when `maestro.admin.events.enabled`)".
  - `CLAUDE.md`: "`maestro.admin.events.enabled` (default `false` since WP7; …)".
  - Release note `### Changed — admin lifecycle events are off by default (3.11c, D3)`:
    - the cost;
    - the topic is no longer required at startup;
    - **Upgrade:** dashboard users set `maestro.admin.events.enabled: true` on every service, at the latest with this upgrade. During a rolling deploy, nodes of this build publish nothing unless set, so the dashboard shows only the old nodes' events;
    - the alias is unchanged;
    - the engine builder's default is unchanged.

- [ ] **Step 8: Commit** (`feat(config)!: maestro.admin.events.enabled defaults to false`).

---

### Task 7: Issues, final docs pass, and the gates

**Files:** `docs/open-issues.md`, `docs/release-notes.md`, `docs/configuration.md`

- [ ] **Step 1: `docs/open-issues.md`.**
  - In Issue 23's header block, add a note: Boot's beans are no longer suppressed (WP7), `spring.kafka.*` reaches them, and the engine's clients still ignore Boot's customizers (see the new issue below).
  - File, at the next free numbers:
    1. **"`maestro-admin-client` has no wired component"** (Kind: product decision; Low). Since WP7 it holds only an unregistered `AdminEventPublisher`. Remove the module (settings, BOM, `maestro-admin`'s dependency, `RELEASING.md`, README, CLAUDE.md, architecture doc), or give it a purpose.
    2. **"The engine's Kafka clients ignore Boot's factory customizers, and take only bootstrap servers from `KafkaConnectionDetails`"** (Low; traced in `KafkaMessagingAutoConfiguration:137-227`). No Micrometer client metrics for engine traffic (`KafkaMetricsAutoConfiguration` customizers), and no security protocol or SSL bundle from connection details.
  - Add both to the §4 index table.

- [ ] **Step 2: Read the whole Unreleased entry set** that this package added. Every claim must name its test or say "(traced)". Every test name quoted in the docs must exist: `grep -rn '<name>' maestro-*/src/test` for each.

- [ ] **Step 3: Commit** (`docs(issues): file the admin-client and engine-customizer follow-ups from WP7`).

- [ ] **Step 4: Coordinator gates** (the implementer does not start these):
  1. `./gradlew --no-watch-fs build`, detached, with a log header (`pwd`, `git rev-parse HEAD`, timestamp). Expected: BUILD SUCCESSFUL.
  2. Push the branch, then `gh workflow run e2e-nightly.yml --ref fix/kafka-coexistence`. This runs the loan E2E (scenarios 1–10, including the admin-events proof at `run-e2e.sh:1981-1992`, which enables events explicitly) and the chaos PR-gate three times. The same dispatch also starts the 2 h soak job, which is not a WP7 gate. Expected: both gate jobs green.
  3. Open the PR, with the release-note entries in its body. The maintainer merges.

## After merge: hand-off to `test/k6-load-foundation` (WP11)

The coordinator sends this to the WP11 branch, and the spec (C4) requires it:
- Today the harness runs with admin events **on** (`PERF_ADMIN_EVENTS` default `true`, described as "today's engine default"), and its baselines were taken that way. After WP7 the engine default is `false`. Change the harness default to `false` in `perf/run.sh` (`PERF_ADMIN_EVENTS="${PERF_ADMIN_EVENTS:-true}"`), `perf/compose.yml` (`PERF_ADMIN_EVENTS: ${PERF_ADMIN_EVENTS:-true}`), `maestro-samples/sample-perf/src/main/resources/application.yml` (`${PERF_ADMIN_EVENTS:true}`), the `workflow_dispatch` `admin_events` input default, and the WP11 plan sentence.
- Rebase onto the merged `main`. Re-run and re-record the scenario 1 (`s1-throughput`) and scenario 3 (`s3-parked`) baselines (`--record-baseline`) with `PERF_ADMIN_EVENTS=false`. Keep one run of each with `PERF_ADMIN_EVENTS=true` and record both results, which shows what admin events cost.
- `sample-perf` injects no `KafkaTemplate` at `130b9ea`, so 3.4 does not affect it. If it ever injects the engine's template, it needs `@Qualifier(MaestroKafkaBeans.TEMPLATE)`, and `ByteArrayTemplateInjectionCheck` fails its startup otherwise.
- WP7 does not edit `docs/observability.md`, so the meter-count changes on that branch do not conflict.

## Review section

(The coordinator fills this in after the whole-branch review: gate results, the observed `main` transaction failure, every red and mutation result, and the issue numbers filed.)
