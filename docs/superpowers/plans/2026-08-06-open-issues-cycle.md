# Open-Issues Cycle Implementation Plan (Issues 23, 24, 22 + audit findings)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close Issues 23 (Kafka config shadowing + dropped trace context), 24 (`.DLT` document/detect/gate), 22 (terminate-vs-compensation race); fix audit findings F3, F5, F6, F8, F9, F10; record the Issue 16 ruling; close two inherited items; file F7 and `finaliseInstance` as new issues.

**Architecture:** Maestro keeps suppressing Boot's Kafka beans by type but the suppression becomes deliberate (`beforeName`) and honest: Maestro's factories are built *from* Boot's bound `KafkaProperties`/`KafkaConnectionDetails` with the engine's wire-format invariants forced last. Trace context is propagated at both ends of the `@MaestroSignalListener` hop. Everything else is small, local fixes with RED-first pins.

**Tech Stack:** Java 25, Spring Boot 4.0.x (spring-boot-kafka 4.0.5), Spring Kafka 4, Jackson 3 (`tools.jackson`), JUnit 5, Testcontainers 2, Gradle 9 Kotlin DSL.

**Spec:** `docs/superpowers/specs/2026-08-06-open-issues-cycle-design.md`. Read it before starting any task.

## Global Constraints

- `maestro-core` must NEVER import Spring. All Spring wiring lives in the starter/backend modules.
- Jackson 3 only (`tools.jackson.*`), never `com.fasterxml.jackson`. `jakarta.*`, never `javax.*`. No Lombok. JSpecify `@Nullable` on public APIs. Javadoc + thread-safety notes on public classes.
- **RED-first discipline:** every new pin must be run against the *unfixed* code and observed to fail on a positive assertion (an expected value, not a bare absence / no-exception) before the fix is applied. Quote the failure output in the commit or ledger.
- Optimistic locking convention: the **caller** pre-increments (`version = current + 1`); the store CASes against `version - 1`.
- Kafka topics are never auto-created by Maestro; they are pre-declared in configuration/compose.
- Context tests that pin auto-configuration **ordering** must use real `AutoConfigurations.of(...)` — `.withBean(...)` registers user beans before auto-configuration and hides ordering bugs (lesson 2026-08-03).
- Commit per item (every green pin + fix pair), never per task. In a worktree: never `cd` into the main checkout; before the first edit run `git -C <worktree> status` and `git -C <main> status` to prove which tree changes.
- Behaviour changes get a `docs/release-notes.md` entry (Task 14 consolidates; each task notes its entry in the ledger).

---

### Task 1: Kafka factories honour `spring.kafka.*` (Issue 23 part 1 — producer/consumer factories)

**Files:**
- Modify: `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/config/KafkaMessagingAutoConfiguration.java`
- Test: `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/config/KafkaMessagingAutoConfigurationPropertiesTest.java` (new)
- Modify (build): `maestro-messaging-kafka/build.gradle.kts` — add `implementation("org.springframework.boot:spring-boot-kafka")` if not already a transitive `api` dependency (check first: `./gradlew :maestro-messaging-kafka:dependencies --configuration compileClasspath | grep spring-boot-kafka`)

**Interfaces:**
- Produces: `maestroKafkaProducerFactory` / `maestroKafkaConsumerFactory` beans, same names and generic types (`<String, byte[]>`) as today — later tasks and the samples' override rely on these names.
- Consumes: Boot's `org.springframework.boot.kafka.autoconfigure.KafkaProperties` (verified present in spring-boot-kafka-4.0.5; has `Map<String,Object> buildProducerProperties()` / `buildConsumerProperties()`) and `org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails` via `ObjectProvider`.

**Background (read first):** Today both factories hand-build config maps from nothing but `spring.kafka.bootstrap-servers` (lines 93–126), so every other `spring.kafka.producer.*`/`consumer.*` property is silently void. Boot's own factories back off because Maestro's typed beans register first — purely by alphabetical accident. The fix keeps the suppression (deliberately) and builds Maestro's maps from Boot's bound properties. **Do NOT add `afterName` on Boot's `KafkaAutoConfiguration`** — that would resurrect Boot's typed beans next to Maestro's and break the context with `NoUniqueBeanDefinitionException`.

- [ ] **Step 1: Write the failing tests**

```java
package io.b2mash.maestro.messaging.kafka.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins Issue 23 part 1: spring.kafka.producer.* / consumer.* reach Maestro's
 * engine clients, while the engine's wire-format invariants stay forced.
 */
class KafkaMessagingAutoConfigurationPropertiesTest {

    // Real auto-configurations, Boot's INCLUDED, so the suppression-plus-
    // property-honouring contract is pinned against the genuine ordering.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    KafkaMessagingAutoConfiguration.class,
                    KafkaAutoConfiguration.class))
            .withPropertyValues(
                    "maestro.service-name=props-test",
                    "spring.kafka.bootstrap-servers=broker-from-props:9092");

    @Test
    void producerPropertiesReachMaestroFactory() {
        runner.withPropertyValues(
                        "spring.kafka.producer.compression-type=gzip",
                        "spring.kafka.producer.batch-size=32768",
                        "spring.kafka.producer.properties.linger.ms=7")
                .run(ctx -> {
                    var pf = (DefaultKafkaProducerFactory<?, ?>)
                            ctx.getBean("maestroKafkaProducerFactory");
                    var cfg = pf.getConfigurationProperties();
                    assertThat(cfg).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
                    assertThat(cfg).containsEntry(ProducerConfig.LINGER_MS_CONFIG, "7");
                    assertThat(cfg.get(ProducerConfig.BATCH_SIZE_CONFIG)).hasToString("32768");
                    assertThat(cfg.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
                            .hasToString("[broker-from-props:9092]");
                });
    }

    @Test
    void engineInvariantsAlwaysWin() {
        runner.withPropertyValues(
                        // A user serializer must never corrupt engine topics
                        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                        "spring.kafka.producer.acks=1",
                        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer")
                .run(ctx -> {
                    var pf = (DefaultKafkaProducerFactory<?, ?>)
                            ctx.getBean("maestroKafkaProducerFactory");
                    assertThat(pf.getConfigurationProperties())
                            .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                            .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)
                            .containsEntry(ProducerConfig.ACKS_CONFIG, "all");
                    var cf = (DefaultKafkaConsumerFactory<?, ?>)
                            ctx.getBean("maestroKafkaConsumerFactory");
                    assertThat(cf.getConfigurationProperties()
                            .get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                            .isEqualTo(org.apache.kafka.common.serialization.ByteArrayDeserializer.class);
                });
    }

    @Test
    void bootsOwnTemplateStaysSuppressed_deliberately() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("maestroKafkaTemplate");
            assertThat(ctx).doesNotHaveBean("kafkaTemplate");
            assertThat(ctx).doesNotHaveBean("kafkaProducerFactory");
        });
    }
}
```

Note: `maestroKafkaConsumerFactory` needs `KafkaMessagingConfig`, which needs `MaestroProperties` — check whether the runner needs `MaestroAutoConfiguration` in the `AutoConfigurations.of(...)` list plus a `WorkflowStore` stub, or whether it is simpler to register `MaestroProperties` via `@EnableConfigurationProperties` in a small `@Configuration` test class added with `.withUserConfiguration(...)`. Follow whatever the existing `KafkaMessagingAutoConfigurationAdminTopicAliasTest`-style tests in this module already do (grep for them) — do not invent a new fixture style.

- [ ] **Step 2: Run the tests, verify they fail on the positive assertions**

Run: `./gradlew :maestro-messaging-kafka:test --tests 'KafkaMessagingAutoConfigurationPropertiesTest' 2>&1 | tee /tmp/task1-red.log`
Expected: `producerPropertiesReachMaestroFactory` FAILS with the map missing `compression.type=gzip` (assertion shows the actual map containing only bootstrap/serializers/acks). `engineInvariantsAlwaysWin` may pass today (invariants are hardcoded) — that is acceptable; it exists to stay green after the fix. `bootsOwnTemplateStaysSuppressed_deliberately` should pass today (pins the status quo).

- [ ] **Step 3: Implement**

In `KafkaMessagingAutoConfiguration`:

```java
// class-level annotations gain:
@AutoConfiguration(after = MaestroAutoConfiguration.class,
        beforeName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        afterName = { /* existing four tracing entries unchanged */ })
@EnableConfigurationProperties(KafkaProperties.class)
```

(keep the existing comment block about tracing `afterName`; add a sibling comment explaining `beforeName`: Maestro's typed factories must register before Boot's type-conditioned ones evaluate, so the suppression that used to be alphabetical accident is now pinned — and Boot's bound `KafkaProperties` is consumed at *instantiation* time, which ordering does not affect.)

```java
@Bean
@ConditionalOnMissingBean(name = "maestroKafkaProducerFactory")
public ProducerFactory<String, byte[]> maestroKafkaProducerFactory(
        KafkaProperties kafkaProperties,
        ObjectProvider<KafkaConnectionDetails> connectionDetails
) {
    var props = new HashMap<String, Object>(kafkaProperties.buildProducerProperties());
    var details = connectionDetails.getIfAvailable();
    if (details != null) {
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, details.getProducerBootstrapServers());
    }
    props.putIfAbsent(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, DEFAULT_BOOTSTRAP_SERVERS);
    // Engine wire-format invariants — forced LAST, overriding any user value.
    // Documented precedence: docs/configuration.md § Kafka client configuration.
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    props.put(ProducerConfig.ACKS_CONFIG, "all");
    return new DefaultKafkaProducerFactory<>(props);
}
```

Consumer factory analogous: start from `buildConsumerProperties()`, apply `details.getConsumerBootstrapServers()` when present, then force `StringDeserializer`/`ByteArrayDeserializer`, `putIfAbsent` `AUTO_OFFSET_RESET_CONFIG=earliest` (a user's explicit `spring.kafka.consumer.auto-offset-reset` wins — it is not a wire-format invariant), and keep the existing `GROUP_ID_CONFIG` default from `messagingConfig.consumerGroup()` (forced, engine-owned). Check the exact `KafkaConnectionDetails` accessor names with `javap -cp ~/.gradle/caches/**/spring-boot-kafka-4.0.5.jar org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails` before writing them.

Delete `resolveBootstrapServers(Environment)` and the `Environment` parameters if nothing else uses them. Update the class Javadoc: the "resolved from spring.kafka.bootstrap-servers, falling back to localhost:9092" paragraph becomes a description of the KafkaProperties + invariants model.

- [ ] **Step 4: Run the module's full test suite**

Run: `./gradlew :maestro-messaging-kafka:test 2>&1 | tail -20`
Expected: all green, including the three new tests and every pre-existing test (the admin-topic-alias and redelivery tests must not regress).

- [ ] **Step 5: Commit**

```bash
git add maestro-messaging-kafka
git commit -m "fix(kafka): build engine producer/consumer factories from spring.kafka.* (Issue 23 pt 1)"
```

---

### Task 2: Template + listener observation, inbound trace extraction, F3 (Issue 23 part 2)

**Files:**
- Modify: `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/config/KafkaMessagingAutoConfiguration.java` (template bean)
- Modify: `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/listener/MaestroSignalListenerBeanPostProcessor.java`
- Test: `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/config/KafkaTemplateObservationTest.java` (new)
- Test: `maestro-messaging-kafka/src/test/java/io/b2mash/maestro/messaging/kafka/listener/MaestroSignalListenerContainerConfigTest.java` (new or extend existing BPP test — grep for an existing `MaestroSignalListener*Test` first and extend it)

**Interfaces:**
- Consumes: `KafkaTracePropagation.runWithExtractedContext(Headers, Runnable)` (`maestro-messaging-kafka/.../KafkaTracePropagation.java:146`), `io.micrometer.tracing.Tracer`.
- Produces: `maestroKafkaTemplate` with observation enabled under the rule below; listener containers with `observationEnabled` set and header extraction wrapped around `handleMessage`.

**The observation rule (both ends, same rule):** enabled when the corresponding Spring property (`spring.kafka.template.observation-enabled` / `spring.kafka.listener.observation-enabled`) is `true`, **or** when the property is unset and a `Tracer` bean is present. An explicit `false` always wins. Implement once as a package-visible static helper so template and listener cannot drift:

```java
// in KafkaMessagingAutoConfiguration:
static boolean observationEnabled(@Nullable Boolean configured, boolean tracerPresent) {
    return configured != null ? configured : tracerPresent;
}
```

- [ ] **Step 1: Write the failing template test**

```java
@Test
void observationDefaultsOnWhenTracerPresent() {
    runner.withBean(io.micrometer.tracing.Tracer.class, () -> mock(Tracer.class))
            .run(ctx -> {
                var template = (KafkaTemplate<?, ?>) ctx.getBean("maestroKafkaTemplate");
                assertThat(template.isObservationEnabled()).isTrue();
            });
}

@Test
void explicitFalseWinsEvenWithTracer() {
    runner.withBean(io.micrometer.tracing.Tracer.class, () -> mock(Tracer.class))
            .withPropertyValues("spring.kafka.template.observation-enabled=false")
            .run(ctx -> {
                var template = (KafkaTemplate<?, ?>) ctx.getBean("maestroKafkaTemplate");
                assertThat(template.isObservationEnabled()).isFalse();
            });
}

@Test
void observationOffWithoutTracerAndWithoutProperty() {
    runner.run(ctx -> {
        var template = (KafkaTemplate<?, ?>) ctx.getBean("maestroKafkaTemplate");
        assertThat(template.isObservationEnabled()).isFalse();
    });
}
```

(`KafkaTemplate.isObservationEnabled()` — verify the getter exists in Spring Kafka 4; if it does not, assert via the template's `ObservationRegistry` effect or reflection on the `observationEnabled` field, but check first: `javap -cp <spring-kafka jar> org.springframework.kafka.core.KafkaTemplate | grep -i observation`. Using a `Tracer` mock bean via `.withBean` is fine here — these tests pin *bean configuration*, not auto-config ordering.)

- [ ] **Step 2: Run, verify RED**

Run: `./gradlew :maestro-messaging-kafka:test --tests 'KafkaTemplateObservationTest' 2>&1 | tee /tmp/task2-red-template.log`
Expected: `observationDefaultsOnWhenTracerPresent` FAILS (`expected: true but was: false`).

- [ ] **Step 3: Implement the template half**

`maestroKafkaTemplate` gains parameters `ObjectProvider<Tracer> tracer` and `Environment env` (or bind `spring.kafka.template.observation-enabled` via `KafkaProperties.getTemplate().isObservationEnabled()` — check whether `KafkaProperties.Template` distinguishes unset from false; if it defaults to `false` without a tri-state, read the raw property from `Environment` as `env.getProperty("spring.kafka.template.observation-enabled", Boolean.class)` which returns `null` when unset — the tri-state matters for the default-on rule):

```java
@Bean
@ConditionalOnMissingBean(name = "maestroKafkaTemplate")
public KafkaTemplate<String, byte[]> maestroKafkaTemplate(
        ProducerFactory<String, byte[]> maestroKafkaProducerFactory,
        ObjectProvider<Tracer> tracer,
        Environment env
) {
    var template = new KafkaTemplate<>(maestroKafkaProducerFactory);
    var configured = env.getProperty("spring.kafka.template.observation-enabled", Boolean.class);
    template.setObservationEnabled(observationEnabled(configured, tracer.getIfAvailable() != null));
    return template;
}
```

`Tracer` is on the classpath only optionally — this class already guards Micrometer types in the nested `TracePropagationConfiguration` via `@ConditionalOnClass`. A `Tracer` method parameter on the outer class would break classpath-optionality. **Solution:** move the observation decision into the nested tracing-guarded configuration OR use `ObjectProvider<Object>` keyed by class name. Simplest safe shape: give the outer bean method an `ObjectProvider<KafkaTracePropagation>` parameter instead (Maestro's own type, always loadable, present exactly when Tracer + Propagator beans exist and tracing is enabled — the same condition the spec names):

```java
public KafkaTemplate<String, byte[]> maestroKafkaTemplate(
        ProducerFactory<String, byte[]> maestroKafkaProducerFactory,
        ObjectProvider<KafkaTracePropagation> tracePropagation,
        Environment env
) {
    var template = new KafkaTemplate<>(maestroKafkaProducerFactory);
    var configured = env.getProperty("spring.kafka.template.observation-enabled", Boolean.class);
    template.setObservationEnabled(
            observationEnabled(configured, tracePropagation.getIfAvailable() != null));
    return template;
}
```

Adjust the Step-1 tests accordingly: instead of a raw `Tracer` mock, register both `Tracer` and `Propagator` mocks (so the real nested `TracePropagationConfiguration` creates `KafkaTracePropagation`), or register a `KafkaTracePropagation` mock directly. Prefer the former — it exercises the real condition chain.

- [ ] **Step 4: Run template tests green, commit**

```bash
./gradlew :maestro-messaging-kafka:test --tests 'KafkaTemplateObservationTest'
git add maestro-messaging-kafka && git commit -m "fix(kafka): engine template observation on by default when tracing is active (Issue 23 pt 1)"
```

- [ ] **Step 5: Write the failing listener tests (container config + F3 + extraction seam)**

Container observation + F3 are testable without a broker by driving the BPP in a context test; header extraction is pinned end-to-end in Task 3 and unit-pinned here:

```java
@Test
void containerObservationFollowsTheSharedRule() { /* build ctx with BPP, a
    @MaestroSignalListener bean, Tracer+Propagator mocks; after
    afterSingletonsInstantiated, fetch the container (BPP exposes none — add a
    package-visible List<ConcurrentMessageListenerContainer<String, byte[]>>
    accessor `containersForTesting()` if none exists) and assert
    container.getContainerProperties().isObservationEnabled() */ }

@Test
void userDefinedConsumerFactoryDoesNotBreakActivation_F3() {
    // context contains BOTH maestroKafkaConsumerFactory and a second
    // ConsumerFactory bean (any); today afterSingletonsInstantiated throws
    // NoUniqueBeanDefinitionException; after the fix it starts and uses the
    // maestro-named one.
}

@Test
void inboundTraceparentReachesTheHandlerContext() {
    // Unit-level: invoke the BPP's message-listener lambda (extract it from
    // containerProps.getMessageListener()) with a ConsumerRecord whose headers
    // carry traceparent; with a KafkaTracePropagation mock, verify
    // runWithExtractedContext(headers, …) was called and the runnable ran.
}
```

Write these as real code following the existing BPP test file's fixture style (grep `MaestroSignalListenerBeanPostProcessor` under `src/test` — a test exists from the redelivery work; reuse its listener bean and context scaffolding). The F3 pin must assert the container's consumer factory **is** the maestro-named bean (positive fact), not merely "no exception".

- [ ] **Step 6: Run, verify RED**

Run: `./gradlew :maestro-messaging-kafka:test --tests '*SignalListener*' 2>&1 | tee /tmp/task2-red-listener.log`
Expected: F3 test fails with `NoUniqueBeanDefinitionException`; observation test fails `expected: true but was: false`; extraction test fails with zero interactions on the mock.

- [ ] **Step 7: Implement the listener half**

In `MaestroSignalListenerBeanPostProcessor`:

```java
// afterSingletonsInstantiated: replace line 136 with
var consumerFactory = resolveConsumerFactory(ctx);
// and add, mirroring resolveKafkaTemplate:
@SuppressWarnings("unchecked")
private static ConsumerFactory<String, byte[]> resolveConsumerFactory(ApplicationContext ctx) {
    if (ctx.containsBean("maestroKafkaConsumerFactory")) {
        return (ConsumerFactory<String, byte[]>)
                ctx.getBean("maestroKafkaConsumerFactory", ConsumerFactory.class);
    }
    return (ConsumerFactory<String, byte[]>) ctx.getBean(ConsumerFactory.class);
}
```

Also resolve `var tracePropagation = ctx.getBeanProvider(KafkaTracePropagation.class).getIfAvailable();` and `var listenerObservation = ctx.getEnvironment().getProperty("spring.kafka.listener.observation-enabled", Boolean.class);` in `afterSingletonsInstantiated`, pass both into `createListenerContainer`, and there:

```java
containerProps.setObservationEnabled(
        KafkaMessagingAutoConfiguration.observationEnabled(listenerObservation, tracePropagation != null));
containerProps.setMessageListener((MessageListener<String, byte[]>) record -> {
    if (tracePropagation != null) {
        tracePropagation.runWithExtractedContext(record.headers(),
                () -> handleMessage(record.value(), reg, executor, objectMapper));
    } else {
        handleMessage(record.value(), reg, executor, objectMapper);
    }
});
```

(If the static-helper cross-package reference is awkward — listener package vs config package — move `observationEnabled` to a small package-private static method on `KafkaTracePropagation` or duplicate the 1-line ternary with a comment naming its twin; do not create a new public API for it.)

- [ ] **Step 8: Run module suite green, commit**

```bash
./gradlew :maestro-messaging-kafka:test
git add maestro-messaging-kafka && git commit -m "fix(kafka): listener containers extract inbound trace context, honour observation, resolve ConsumerFactory by name (Issue 23 pt 2, F3)"
```

---

### Task 3: Cross-service trace pin over real Kafka (Issue 23 done-when (b)+(c))

**Files:**
- Test: `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/observability/SignalListenerTraceContextIT.java` (new)
- Reference (read, follow style): `maestro-integration-tests/src/test/java/io/b2mash/maestro/integration/observability/KafkaTraceLinkageIT.java` and `.../support/OtelTracingFixture.java`

**Interfaces:**
- Consumes: Task 2's listener extraction; the module's Testcontainers Kafka + Postgres singletons (see `maestro-integration-tests` `SPEC.md` for the fixtures).

**The pin:** a record published to a `@MaestroSignalListener` topic with a valid W3C `traceparent` header ends up as a signal row whose `trace_context` is non-NULL and carries the *same trace id*.

- [ ] **Step 1: Write the test (RED against pre-Task-2 code)**

Shape (follow `KafkaTraceLinkageIT`'s fixture conventions exactly — real broker, real store, `OtelTracingFixture` for a working `Tracer`/`Propagator` pair):

```java
@Test
void inboundTraceparentIsPersistedOnTheSignalRow() {
    var traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    var producerRecord = new ProducerRecord<String, byte[]>(TOPIC, "wf-1", payloadBytes);
    producerRecord.headers().add("traceparent",
            ("00-" + traceId + "-00f067aa0ba902b7-01").getBytes(StandardCharsets.UTF_8));
    rawKafkaTemplate.send(producerRecord).get(10, TimeUnit.SECONDS);

    await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
        var stored = fetchSignalTraceContext(store, "wf-1", SIGNAL_NAME); // SQL or store API
        assertThat(stored).as("trace_context on the persisted signal row")
                .isNotNull()
                .contains(traceId);
    });
}
```

The assertion is on the **collected value containing the trace id** — not on "non-null" alone and not via a bare `await(condition)` (lesson: a hang must fail with `expected: <..4bf92f...> but was: <null>`, so structure the await as `untilAsserted` over the value read once the row exists; first await row existence, then assert the value outside an await if the ConditionTimeout ambiguity bites).

- [ ] **Step 2: Verify RED against the pre-fix library**

This task runs after Tasks 1–2 land in the worktree, so RED must be demonstrated by stashing the fix: `git stash` the Task-2 listener change (or `git checkout origin/main -- maestro-messaging-kafka/src/main/java/.../MaestroSignalListenerBeanPostProcessor.java`), run the IT, record `was: <null>`, restore (`git checkout <worktree-branch> -- …`). Archive the red log to the SDD workspace evidence dir.

Run: `./gradlew :maestro-integration-tests:test --tests 'SignalListenerTraceContextIT' 2>&1 | tee <sdd-workspace>/evidence/task3-red.log`

- [ ] **Step 3: Run green against the fixed library, 3× consecutive**

Run: `./gradlew :maestro-integration-tests:test --tests 'SignalListenerTraceContextIT' --rerun-tasks` three times.
Expected: green ×3 (integration-suite standard).

- [ ] **Step 4: Commit**

```bash
git add maestro-integration-tests
git commit -m "test(integration): pin inbound traceparent -> signal trace_context over real Kafka (Issue 23)"
```

---

### Task 4: Remove the workaround; retract the docs scope-limit; write the precedence doc (Issue 23 close-out)

**Files:**
- Delete: `maestro-samples/sample-loan-origination/loan-application-service/src/main/java/**/config/ObservedKafkaTemplateConfig.java` (and the identical class in `verification-gateway-service` and `underwriting-service` — locate all with `grep -rl ObservedKafkaTemplateConfig maestro-samples demo`)
- Modify: the three services' `application.yml` — remove `spring.kafka.producer.key-serializer`/`value-serializer`/`acks` and `consumer.*-deserializer` entries (Maestro forces those); keep any other `spring.kafka.*` values (they now genuinely apply). Same sweep in `maestro-samples/sample-order-service/src/main/resources/application.yml:12-17` and `sample-payment-gateway`.
- Modify: `docs/observability.md` — §"Cross-service trace propagation (Kafka)": delete the scope-limit paragraph added by the demo cycle (grep for "scope limit" / the paragraph naming `ObservedKafkaTemplateConfig`), and replace with the new contract: producer observation on-by-default with a Tracer; `@MaestroSignalListener` extracts inbound `traceparent`; `spring.kafka.template.observation-enabled=false` / `spring.kafka.listener.observation-enabled=false` opt out.
- Modify: `docs/configuration.md` — new section "Kafka client configuration": Maestro's engine clients are built from `spring.kafka.*` (producer, consumer, ssl, security, properties.*); the three invariants (String key / byte[] value serialization, `acks=all`) always win; Boot's own `kafkaTemplate`/factories are deliberately not created — define your own `KafkaTemplate` bean for application traffic with custom types, or reuse `maestroKafkaTemplate` for byte[] traffic.
- Modify: `docs/release-notes.md` — entry under Unreleased: behaviour change for every Maestro+Kafka user (spring.kafka.* now honoured; observation defaults; workaround class obsolete).

**Steps:**

- [ ] **Step 1:** Delete the three `ObservedKafkaTemplateConfig` classes; sweep sample yml files as above. `grep -rn "ObservedKafkaTemplateConfig" .` must return only docs/history references (open-issues.md's record is fine — Task 14 annotates it).
- [ ] **Step 2:** Make the three doc edits. For observability.md, verify the retracted paragraph's claims are each now false (that is the point of retracting) — cite Task 1–3's pins in the ledger.
- [ ] **Step 3:** Build the touched samples: `./gradlew :maestro-samples:sample-loan-origination:loan-application-service:build :maestro-samples:sample-loan-origination:verification-gateway-service:build :maestro-samples:sample-loan-origination:underwriting-service:build :maestro-samples:sample-order-service:build`
Expected: green — proves the samples compile and their tests pass without the workaround.
- [ ] **Step 4:** Commit: `git commit -m "chore(samples,docs): remove Issue-23 workaround, retract observability scope limit, document spring.kafka precedence"`

---

### Task 5: Issue 24 — `.DLT`: document, detect, gate

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/config/MaestroProperties.java` — `RedeliveryProperties` gains `@DefaultValue("true") boolean enabled` as the FIRST record component; update `defaults()` and Javadoc.
- Modify: `maestro-messaging-kafka/.../KafkaMessagingConfig.java` (carries redelivery fields — add `redeliveryEnabled`), `KafkaMessagingAutoConfiguration.maestroKafkaMessagingConfig` (pass it), `KafkaWorkflowMessaging` + `MaestroSignalListenerBeanPostProcessor.createListenerContainer` (when disabled: do **not** install the dead-lettering error handler; install `new DefaultErrorHandler(new FixedBackOff(0L, 0L))` so a failing record is retried zero times, logged by the handler, and **skipped** — document in Javadoc that disabling redelivery restores at-most-once handler semantics and is the operator's explicit choice).
- Modify: `maestro-messaging-postgres/.../PostgresWorkflowMessaging.java` + its auto-configuration — when disabled: single attempt; on failure mark the row `FAILED` (pre-Issue-1 semantics) — grep the current attempt/backoff code and gate it.
- Create: `maestro-messaging-kafka/src/main/java/io/b2mash/maestro/messaging/kafka/KafkaDeadLetterTopicCheck.java` — warn-only startup probe.
- Modify: both compose files — `maestro-samples/sample-loan-origination/docker-compose.yml` and `demo/docker-compose.yml`: add a `.DLT` companion for every topic an application consumer subscribes to (the loan stack's `@MaestroSignalListener` topics — enumerate them from the three services' source: `grep -rn "MaestroSignalListener(topic" maestro-samples/sample-loan-origination`), created in the same pre-create block as the existing 11.
- Modify: `docs/configuration.md` (redelivery section: the flag, the `.DLT` pre-creation checklist), `docs/release-notes.md`.
- Test: `maestro-messaging-kafka/src/test/java/.../KafkaDeadLetterTopicCheckTest.java`, extension of the redelivery tests for the flag, and a Postgres-side flag test in `maestro-messaging-postgres`.

**Interfaces:**
- Produces: `maestro.messaging.redelivery.enabled` (default `true`); `KafkaDeadLetterTopicCheck.warnOnMissing(Admin admin, Collection<String> topics, String suffix)` returning the list of missing `.DLT` names (for testability), called at listener/subscription startup with a bounded (5s) `describeTopics`.

**The check's contract:** it WARNs (`"Dead-letter topic '{}' does not exist — redelivery for '{}' will exhaust its attempts and then fail to publish; pre-create it or set maestro.messaging.redelivery.enabled=false"`), never throws, and its own probe failure logs DEBUG. Wire it where subscriptions are made: `KafkaWorkflowMessaging.subscribe`/`subscribeSignals` and the BPP's `afterSingletonsInstantiated`, building the `Admin` client from the consumer factory's bootstrap servers, closing it after the probe.

- [ ] **Step 1 (RED):** In the existing redelivery IT (`KafkaAckOnFailureIT` region — grep it), add a test asserting that with no `.DLT` topic pre-created, container startup emits the WARN naming `<topic>.DLT` (capture via a logback list appender following whatever log-capture idiom the module's tests already use; the assertion is on the message containing the exact topic name). Run — fails (no such warning exists).
- [ ] **Step 2 (RED):** Flag test: `maestro.messaging.redelivery.enabled=false` → the container's error handler is a `DefaultErrorHandler` with zero-retry backoff and **no** `DeadLetterPublishingRecoverer` (assert handler type/config via the container accessor from Task 2), and the Postgres transport marks a failing signal row `FAILED` after exactly 1 attempt (extend `PostgresWorkflowMessagingTest` — assert `attempts == 1` and status, positive values). Run — both fail (flag doesn't exist; binding error is the expected RED shape for the property).
- [ ] **Step 3:** Implement all of the above. Compose files updated in the same commit as the check so the stacks stop violating the guarantee the check now warns about.
- [ ] **Step 4:** `./gradlew :maestro-messaging-kafka:test :maestro-messaging-postgres:test :maestro-spring-boot-starter:test` green; docs written.
- [ ] **Step 5:** Commit per item: flag (`feat(messaging): maestro.messaging.redelivery.enabled`), check (`feat(kafka): warn-only startup check for missing .DLT topics`), compose+docs (`docs: .DLT pre-creation checklist; compose stacks pre-create .DLT companions`).

---

### Task 6: Issue 22 — terminate racing `transitionToCompensating` (bounded retry + stand-down)

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/saga/SagaManager.java` — `transitionToCompensating` (`:540`)
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/saga/SagaManagerTerminateRaceTest.java` (new; or the existing SagaManager test file if one has the fixture — grep first)

**Interfaces:**
- Consumes: `InstanceStatusWriter.write` (`maestro-core/.../engine/InstanceStatusWriter.java`) as the proven idiom — read its Javadoc and loop before writing anything. `STATUS_WRITE_ATTEMPTS = 5` is the shared budget.
- Produces: unchanged public API. New behaviour contract: a `TERMINATED` observed on **any** attempt throws `WorkflowTerminatedException`; exhaustion abandons the run **without compensating** by rethrowing the last `OptimisticLockException`.

**Exhaustion policy (differs from `InstanceStatusWriter`, deliberately):** `InstanceStatusWriter` stands down and lets the run continue because its statuses are advisory. Here the write gates entry into the compensation phase; proceeding to compensate against a row we could not read-confirm is exactly the defect. On exhaustion: log ERROR and rethrow the final `OptimisticLockException`. Propagation path (verified in source): `compensate()` → `handleWorkflowFailure` (its try only catches `CompensationException`, `WorkflowExecutor.java:1716`) → out of `executeWorkflow`'s failure handler → the run thread unwinds; `executeWorkflow`'s `finally` releases the instance lock; the instance stays in its pre-compensation **active** status, so recovery re-runs it, replays, and re-attempts compensation with a fresh read. Nothing terminal is written, no compensation runs.

- [ ] **Step 1: Write the RED pin**

Deterministic interposition — no latches, no sleeps. Wrap the in-memory store (`maestro-test`'s in-memory `WorkflowStore`, same fixture the existing `WorkflowExecutor*Test`s use) with a delegating store whose `updateInstance` injects a cross-node terminate *between the guard's read and the CAS*:

```java
/** Injects a cross-node TERMINATED write just before the first COMPENSATING CAS. */
final class TerminateInterposingStore extends /* delegate pattern over */ WorkflowStore {
    private final WorkflowStore delegate;
    private final AtomicBoolean injected = new AtomicBoolean();

    @Override
    public void updateInstance(WorkflowInstance instance) {
        if (instance.status() == WorkflowStatus.COMPENSATING && injected.compareAndSet(false, true)) {
            // What WorkflowExecutor.terminateWorkflow on another node does:
            var current = delegate.getInstance(instance.workflowId()).orElseThrow();
            delegate.updateInstance(current.toBuilder()
                    .status(WorkflowStatus.TERMINATED)
                    .updatedAt(Instant.now())
                    .version(current.version() + 1)
                    .build());
            // Now the caller's CAS (built against the pre-terminate version) must lose.
        }
        delegate.updateInstance(instance);
    }
    // every other method: plain delegation
}
```

Workflow under test: two activities with a registered compensation for the first; the second throws, entering `handleWorkflowFailure` → `compensate()`. Compensation invocations are counted by the test's activity impl (an `AtomicInteger` the compensation lambda increments).

RED assertions (against unfixed code — run and archive):
```java
assertThat(compensationRuns.get())
        .as("compensations invoked despite operator terminate")
        .isPositive();                       // documents today's defect
var events = store.getEvents(instanceId);
assertThat(events).extracting(WorkflowEvent::eventType)
        .contains(EventType.COMPENSATION_STARTED);   // the write ledger
```

GREEN assertions (the same test, flipped after the fix — this is one test whose expected values change with the fix; keep the RED version's output in the evidence log):
```java
assertThat(compensationRuns.get()).isZero();
assertThat(events).extracting(WorkflowEvent::eventType)
        .doesNotContain(EventType.COMPENSATION_STARTED);
assertThat(store.getInstance(workflowId).orElseThrow().status())
        .isEqualTo(WorkflowStatus.TERMINATED);        // operator's write stands
```

Also pin `WorkflowTerminatedException` propagation at the `SagaManager.compensate` level (call it directly with the interposing store; `assertThatThrownBy(...).isInstanceOf(WorkflowTerminatedException.class)`), and add an exhaustion test: a delegating store whose `updateInstance` *always* throws `OptimisticLockException` for `COMPENSATING` writes while `getInstance` keeps returning an active status → assert `OptimisticLockException` propagates out of `compensate()` after exactly `STATUS_WRITE_ATTEMPTS` update attempts (count them) and zero compensations ran.

- [ ] **Step 2: Run, archive RED**

Run: `./gradlew :maestro-core:test --tests 'SagaManagerTerminateRaceTest' 2>&1 | tee <sdd-workspace>/evidence/task6-red.log`
Expected: the race test shows `compensationRuns` positive today. The exhaustion test fails differently (compensations run after the swallow).

- [ ] **Step 3: Implement**

Replace the guard + single-CAS + swallow (`SagaManager.java:541–568`) with:

```java
private void transitionToCompensating(WorkflowContext ctx, WorkflowInstance instance) {
    OptimisticLockException lastConflict = null;
    for (var attempt = 1; attempt <= InstanceStatusWriter.STATUS_WRITE_ATTEMPTS; attempt++) {
        var latest = store.getInstance(ctx.workflowId()).orElse(instance);
        if (latest.status() == WorkflowStatus.TERMINATED) {
            logger.info("Workflow '{}' is TERMINATED — not starting compensation; abandoning this run",
                    ctx.workflowId());
            throw new WorkflowTerminatedException(ctx.workflowId(), null);
        }
        if (latest.status().isTerminal()) {
            logger.warn("Workflow '{}' is already {} — another runner finalised it first; "
                            + "not transitioning to COMPENSATING", ctx.workflowId(), latest.status());
            return;
        }
        try {
            store.updateInstance(latest.toBuilder()
                    .status(WorkflowStatus.COMPENSATING)
                    .updatedAt(Instant.now())
                    .version(latest.version() + 1)
                    .build());
            return;
        } catch (OptimisticLockException e) {
            lastConflict = e;
            logger.debug("Lost COMPENSATING CAS for workflow '{}' (attempt {}/{}) — re-reading",
                    ctx.workflowId(), attempt, InstanceStatusWriter.STATUS_WRITE_ATTEMPTS);
        }
    }
    // Exhaustion: this write gates ENTRY into the compensation phase, so unlike
    // InstanceStatusWriter we must not proceed against a row we could not
    // read-confirm — the row is being written by someone whose intent we keep
    // missing, and that someone may be a terminate. Abandon the local run:
    // nothing terminal was written, the instance stays active, and recovery
    // re-attempts compensation from a fresh read.
    logger.error("Could not transition workflow '{}' to COMPENSATING after {} attempts — "
            + "abandoning this run without compensating; recovery will retry",
            ctx.workflowId(), InstanceStatusWriter.STATUS_WRITE_ATTEMPTS);
    throw lastConflict;
}
```

`InstanceStatusWriter` is in `io.b2mash.maestro.core.engine`; `STATUS_WRITE_ATTEMPTS` is package-private there — either widen it to public (it is a documented constant) or duplicate `5` with a comment naming the twin; prefer widening. Keep the existing method Javadoc, updating the paragraph that documents the swallow (it currently *documents Issue 22*; rewrite to describe the retry + the exhaustion policy). Drop the now-dead broad `catch (Exception e)` only if nothing else needs it — a non-OLE store exception should keep its current behaviour (WARN + return); check git blame/Javadoc before removing.

- [ ] **Step 4: Run green + full core suite**

Run: `./gradlew :maestro-core:test`
Expected: new tests green with the GREEN assertions; no regressions (SagaManager has many tests).

- [ ] **Step 5: Mutation round (mandatory — Issue 21 precedent)**

In a scratch copy of the worktree (`git worktree add` a throwaway or `git stash` cycles), verify each pin is load-bearing:
1. Revert the loop to single-attempt-with-swallow → race test must go red.
2. Remove the in-loop `TERMINATED` re-check (keep only a pre-loop check) → race test must go red (the terminate lands after the first read).
3. Make exhaustion fall through to `return` (proceed to compensate) → exhaustion test must go red.
Record each mutation → red output in the ledger. If any pin stays green under its mutation, fix the pin before proceeding.

- [ ] **Step 6: Commit**

```bash
git add maestro-core
git commit -m "fix(core): terminate racing transitionToCompensating no longer runs compensations (Issue 22)"
```

---

### Task 7: F8 — `maestro.enabled=false` disables every Maestro module

**Files:**
- Modify (add one class-level annotation each): `KafkaMessagingAutoConfiguration`, `PostgresMessagingAutoConfiguration` (`maestro-messaging-postgres/.../config/`), `ValkeyLockAutoConfiguration` (`maestro-lock-valkey/.../config/`), `PostgresLockAutoConfiguration` (`maestro-lock-postgres/.../config/`), `PostgresStoreAutoConfiguration` (`maestro-store-postgres/.../config/`), `AdminClientAutoConfiguration` (`maestro-admin-client/.../`), `MaestroHealthAutoConfiguration`, `MaestroObservabilityAutoConfiguration` (starter).
- Test: one context-runner test per touched module, named `<Module>MaestroDisabledTest`, plus assertion additions to existing auto-config tests where a file already exists.

**The annotation (identical everywhere, matching `MaestroAutoConfiguration.java:45`):**

```java
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
```

Note for `AdminClientAutoConfiguration`: it already has `@ConditionalOnProperty` on `maestro.admin.events.enabled` — the new `maestro.enabled` gate is **additional** (both must hold); multiple `@ConditionalOnProperty` annotations cannot repeat on one class, so use the multi-name form or `@ConditionalOnBooleanProperty` if the repo's Boot version offers it — simplest: one `@ConditionalOnProperty` with two entries is not expressible; instead add `havingValue` pair via `@ConditionalOnProperty(name = {"maestro.enabled", "maestro.admin.events.enabled"}, matchIfMissing = true)` — verify semantics (ALL named properties must match) in the Boot Javadoc before relying on it; if unclear, guard with a small static `@Conditional` — but prefer the array form, it is documented as AND.

- [ ] **Step 1 (RED):** per module, a test like:

```java
@Test
void maestroDisabledMeansNoBeansAndNoCrash() {
    new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
            .withPropertyValues("maestro.enabled=false", "maestro.service-name=x")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();          // RED today: NoSuchBeanDefinitionException chain
                assertThat(ctx).doesNotHaveBean(WorkflowMessaging.class);
                assertThat(ctx).doesNotHaveBean("maestroKafkaTemplate");
            });
}
```

For `maestro-lock-valkey` the RED shape differs: the context *succeeds* today and opens connections — assert `doesNotHaveBean(RedisClient.class)` (RED: bean exists). No real Valkey needed: bean *definition* absence is the pin; do not let the test actually connect (today's test would try — if `RedisClient.create` connects lazily, the bean exists without I/O and the test is safe to run RED; Lettuce connects on `.connect()`, which `maestroLockConnection` calls, so assert on definitions via `ctx.getBeanFactory().containsBeanDefinition(...)` if instantiation is the failure mode).

- [ ] **Step 2:** Run all new tests, verify RED per module. `tee` one combined log to evidence.
- [ ] **Step 3:** Add the annotation to all eight classes.
- [ ] **Step 4:** Green: run each touched module's test task. Also run `:maestro-spring-boot-starter:test` fully (health/observability configs are load-bearing elsewhere).
- [ ] **Step 5:** Commit: `fix(auto-config): maestro.enabled=false now disables every Maestro module (audit F8)`.

---

### Task 8: F9 — JNDI/XA DataSource auto-config ordering

**Files:**
- Modify: `maestro-store-postgres/src/main/java/io/b2mash/maestro/store/postgres/config/PostgresStoreAutoConfiguration.java:47-49`
- Test: extend the module's existing auto-config ordering test (grep `afterName` or `DataSourceAutoConfiguration` under its `src/test`)

- [ ] **Step 1:** Extend `afterName` to:

```java
afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
        "org.springframework.boot.jdbc.autoconfigure.XADataSourceAutoConfiguration"
}
```

(All three FQCNs verified present in spring-boot-jdbc-4.0.5.)

- [ ] **Step 2 (test):** A true RED pin needs a JNDI context — disproportionate. Pin what is pinnable: a context test with real `AutoConfigurations.of(PostgresStoreAutoConfiguration.class, XADataSourceAutoConfiguration.class)` where the DataSource comes from a config class that sorts *after* `io.b2mash…` alphabetically… **Simpler honest pin:** assert the annotation's contents reflectively:

```java
@Test
void afterNameCoversAllBootDataSourceAutoConfigs() {
    var afterNames = PostgresStoreAutoConfiguration.class
            .getAnnotation(AutoConfiguration.class).afterName();
    assertThat(afterNames).contains(
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.JndiDataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.XADataSourceAutoConfiguration");
    // Guard against typos: every named class must exist on the test classpath.
    for (var name : afterNames) {
        assertThatCode(() -> Class.forName(name, false, getClass().getClassLoader()))
                .as("afterName entry %s must be a real class", name).doesNotThrowAnyException();
    }
}
```

RED first (run before the annotation edit: contains-assertion fails). The `Class.forName` half is the part that catches the F-class bug (a misspelled FQCN is silently ignored by the sorter).

- [ ] **Step 3:** Green + commit: `fix(store-postgres): order after JNDI/XA DataSource auto-configs (audit F9)`.

---

### Task 9: F10 — admin-client honours the canonical admin-events topic property

**Files:**
- Modify: `maestro-admin-client/src/main/java/io/b2mash/maestro/admin/client/AdminClientAutoConfiguration.java`
- Test: `maestro-admin-client/src/test/java/io/b2mash/maestro/admin/client/AdminClientTopicResolutionTest.java` (new; the module has a suite — follow its style)

**Design:** `maestro-admin-client` must not depend on the starter, so it cannot share `KafkaMessagingAutoConfiguration.resolveAdminEventsTopic` or `MaestroProperties`. Mirror the resolution over `Environment`, with a comment naming the twin (`KafkaMessagingAutoConfiguration.resolveAdminEventsTopic`) at both sites:

```java
private static final String DEFAULT_TOPIC = "maestro.admin.events";

static String resolveTopic(Environment env, AdminClientProperties properties) {
    var messagingTopic = env.getProperty("maestro.messaging.topics.admin-events", DEFAULT_TOPIC);
    var aliasTopic = properties.getTopic();   // maestro.admin.events.topic, deprecated
    var messagingCustomized = !messagingTopic.equals(DEFAULT_TOPIC);
    var aliasCustomized = !aliasTopic.equals(DEFAULT_TOPIC);
    if (aliasCustomized && messagingCustomized && !aliasTopic.equals(messagingTopic)) {
        logger.warn("Both maestro.messaging.topics.admin-events ('{}') and the deprecated "
                + "maestro.admin.events.topic ('{}') are configured — the messaging property wins.",
                messagingTopic, aliasTopic);
        return messagingTopic;
    }
    return aliasCustomized && !messagingCustomized ? aliasTopic : messagingTopic;
}
```

`adminEventPublisher(...)` gains an `Environment` parameter and passes `resolveTopic(env, properties)`.

- [ ] **Step 1 (RED):** four context tests — neither set → default; only messaging set → messaging value (**RED today**: publisher gets the default); only alias → alias; both, different → messaging + the WARN. Assert the topic the `AdminEventPublisher` actually holds (add a package-private getter or assert via the publisher's existing accessor — check the class; if none, add `String topic()` package-private).
- [ ] **Step 2:** Run RED (`only messaging set` fails), implement, green.
- [ ] **Step 3:** Also update `AdminClientProperties.topic` Javadoc: deprecated alias, canonical property named.
- [ ] **Step 4:** Commit: `fix(admin-client): honour maestro.messaging.topics.admin-events (audit F10)`.

---

### Task 10: F5 — Valkey lock reads the documented connection properties

**Files:**
- Modify: `maestro-lock-valkey/src/main/java/io/b2mash/maestro/lock/valkey/config/ValkeyLockAutoConfiguration.java` — `resolveRedisUri`
- Modify: `docs/configuration.md:444-447` (Complete Example) and `:478-479`, plus a new row documenting `maestro.lock.valkey.uri`
- Test: extend the module's auto-config test (grep for an existing one; else create `ValkeyLockAutoConfigurationUriTest`)

**Resolution order (existing steps unchanged, one inserted):** 1. `spring.data.redis.url`; 2. `maestro.lock.valkey.uri`; 3. **new** — build from parts when `spring.data.redis.host` is set:

```java
private static String resolveRedisUri(Environment env) {
    var standard = env.getProperty("spring.data.redis.url");
    if (standard != null && !standard.isBlank()) return standard;
    var custom = env.getProperty("maestro.lock.valkey.uri");
    if (custom != null && !custom.isBlank()) return custom;
    var host = env.getProperty("spring.data.redis.host");
    if (host != null && !host.isBlank()) {
        var builder = RedisURI.builder()
                .withHost(host)
                .withPort(env.getProperty("spring.data.redis.port", Integer.class, 6379))
                .withSsl(env.getProperty("spring.data.redis.ssl.enabled", Boolean.class, false))
                .withDatabase(env.getProperty("spring.data.redis.database", Integer.class, 0));
        var password = env.getProperty("spring.data.redis.password");
        if (password != null && !password.isBlank()) {
            var username = env.getProperty("spring.data.redis.username");
            if (username != null && !username.isBlank()) {
                builder.withAuthentication(username, password.toCharArray());
            } else {
                builder.withPassword((CharSequence) password);
            }
        }
        return builder.build().toURI().toString();
    }
    return ValkeyLockConfig.DEFAULT_REDIS_URI;
}
```

(Verify the exact Lettuce `RedisURI.Builder` method names against the version on the classpath before writing — `withSsl(boolean)`, `withAuthentication(String, char[])` exist in Lettuce 6; adjust if the API differs.)

- [ ] **Step 1 (RED):** test that `spring.data.redis.host=lock-host` + `port=7000` produces a `RedisURI` of `redis://lock-host:7000` — assert by calling the (make it package-visible for tests) `resolveRedisUri` directly with a `MockEnvironment`; RED today: returns `redis://localhost:6379`. Add cases: url wins over host; maestro uri wins over host; password/ssl/database appear in the URI.
- [ ] **Step 2:** Implement, green.
- [ ] **Step 3:** Fix `docs/configuration.md` — Complete Example uses `spring.data.redis.url` (or the now-working host/port — since host/port now work, the example may stand; still add `.url` and `maestro.lock.valkey.uri` to the reference table with the precedence order). Every property shown in the docs must now be one this resolver reads — re-read the section end-to-end after editing.
- [ ] **Step 4:** Commit: `fix(lock-valkey): honour spring.data.redis host/port/password/ssl/database (audit F5)`.

---

### Task 11: F6 — wire `maestro.retry.*` into the default activity retry policy

**Files:**
- Modify: `maestro-spring-boot-starter/src/main/java/io/b2mash/maestro/spring/proxy/ActivityStubBeanPostProcessor.java` (around `:154`)
- Modify: `docs/configuration.md` retry section (`:311-339`) — clarify: applies when `@ActivityStub` leaves `retryPolicy` at its annotation defaults; an explicitly-attributed annotation wins.
- Test: extend `maestro-spring-boot-starter`'s existing stub BPP / config-seam tests (grep `MaestroAutoConfigurationConfigSeamsTest` and any `ActivityStubBeanPostProcessor*Test`)

**Design:** the annotation cannot distinguish "absent" from "explicitly all-default", so the rule is: a `retryPolicy()` whose six attributes all equal the annotation defaults (`3`, `"PT1S"`, `"PT1M"`, `2.0`, `{}`, `{}`) resolves to the policy built from `MaestroProperties.getRetry()`; anything else resolves via `RetryPolicy.fromAnnotation` as today. Document that rule in both the annotation's and the property's Javadoc.

```java
// in ActivityStubBeanPostProcessor (it already resolves MaestroProperties from ctx
// for the lock prefix — reuse that):
private static RetryPolicy resolveRetryPolicy(
        io.b2mash.maestro.core.annotation.RetryPolicy annotation,
        MaestroProperties.RetryProperties defaults) {
    if (isAnnotationDefault(annotation)) {
        return new RetryPolicy(
                defaults.defaultMaxAttempts(),
                defaults.defaultInitialInterval(),
                defaults.defaultMaxInterval(),
                defaults.defaultBackoffMultiplier(),
                List.of(), List.of());
    }
    return RetryPolicy.fromAnnotation(annotation);
}

private static boolean isAnnotationDefault(io.b2mash.maestro.core.annotation.RetryPolicy a) {
    return a.maxAttempts() == 3
            && "PT1S".equals(a.initialInterval())
            && "PT1M".equals(a.maxInterval())
            && a.backoffMultiplier() == 2.0
            && a.retryableExceptions().length == 0
            && a.nonRetryableExceptions().length == 0;
}
```

- [ ] **Step 1 (RED):** context test: `maestro.retry.default-max-attempts=1` + a `@DurableWorkflow` bean whose `@ActivityStub` has no explicit retryPolicy → the injected proxy's policy has `maxAttempts() == 1`. Getting at the proxy's policy: `ActivityInvocationHandler` holds it (`retryPolicy` field, `:76`) — extract via `Proxy.getInvocationHandler(proxyField)` and a package-private accessor on the handler (add `RetryPolicy retryPolicyForTesting()` if none exists; it is in `maestro-core`, keep it package-private + a test helper in the starter is not same-package — instead assert behaviourally: an activity impl that always throws is invoked exactly once (`AtomicInteger`), RED today: 3 times). Prefer the behavioural pin — it survives refactors and needs no seam.
- [ ] **Step 2:** Second test: an `@ActivityStub(retryPolicy = @RetryPolicy(maxAttempts = 5))` keeps 5 attempts even with `maestro.retry.default-max-attempts=1` (annotation wins when customized).
- [ ] **Step 3:** Run RED (attempt count `expected: 1 but was: 3`), implement, green. Full starter suite.
- [ ] **Step 4:** Commit: `fix(starter): maestro.retry.default-* now provides the default @ActivityStub retry policy (audit F6)`.

---

### Task 12: Inherited — `startRenewerIfNeeded` outside the NO_BACKEND try

**Files:**
- Modify: `maestro-core/src/main/java/io/b2mash/maestro/core/engine/WorkflowInstanceLockManager.java:138-149`
- Test: `maestro-core/src/test/java/io/b2mash/maestro/core/engine/WorkflowInstanceLockManagerTest.java` (exists — extend)

**The defect:** `startRenewerIfNeeded()` (`:144`) sits inside the `try` whose `catch (Exception e)` (`:145-149`) maps any throw to `Acquisition.NO_BACKEND` — but by that point the lock IS in `heldLocks` (`:143`), so a renewer-start failure makes the caller skip `release()`, the lock renews/holds forever, and the workflowId is blocked cluster-wide. Identical shape to the observer bug already fixed at `:150-155` (read that comment).

- [ ] **Step 1:** Restructure so only the backend call is in the try:

```java
try {
    var handle = distributedLock.tryAcquire(keyPrefix + WORKFLOW_KEY_SEGMENT + workflowId, ttl);
    if (handle.isEmpty()) {
        return Acquisition.HELD_ELSEWHERE;
    }
    heldLocks.put(workflowId, handle.get());
} catch (Exception e) {
    logger.warn("Instance lock backend unavailable for workflow '{}' — proceeding unlocked: {}",
            workflowId, e.getMessage());
    return Acquisition.NO_BACKEND;
}
// Outside the backend try — the lock IS held from here on, so neither a
// renewer-start failure nor a throwing observer may be reported as NO_BACKEND
// (the caller would skip release() and block the workflowId cluster-wide).
startRenewerIfNeeded();
emit("instanceLockAcquired", workflowId, () -> observer.instanceLockAcquired(workflowId));
return Acquisition.ACQUIRED;
```

But `startRenewerIfNeeded` can now throw out of `tryAcquire` — decide the contract: it must NOT propagate (the method's Javadoc says "never throws"). Wrap it in its own try that logs ERROR and continues (lock held, renewal absent, TTL will expire it — same degradation as a renewal failure):

```java
try {
    startRenewerIfNeeded();
} catch (Exception e) {
    logger.error("Instance lock renewer failed to start for '{}' — lock will expire via TTL: {}",
            workflowId, e.getMessage(), e);
}
```

- [ ] **Step 2 (RED first):** the seam: `startRenewerIfNeeded` builds a thread via `Thread.ofVirtual()` — inject failure by extracting a package-private `Supplier<Thread>`-style factory field (`renewerThreadStarter`) defaulting to current behaviour; the test replaces it with a throwing one. Write the test against unfixed code: a lock backend that acquires successfully + a throwing renewer-starter → today returns `NO_BACKEND` **while `isHeld(workflowId)` is true** (assert both — that pair is the defect); after the fix → returns `ACQUIRED`, `isHeld` true, and a subsequent `release(workflowId)` works. Run RED (assert `ACQUIRED`, observe `NO_BACKEND`), then fix, then green.
- [ ] **Step 3:** Full `:maestro-core:test`; commit: `fix(core): renewer-start failure no longer misreported as NO_BACKEND with the lock held (inherited item)`.

---

### Task 13: Issue 16 ruling docs + demo jar-name verify-and-close

**Files:**
- Modify: `docs/admin.md` — after the `COMPENSATED_NOT_RETRYABLE` description (grep it; Issue 16's guard documented it), add an "Operator path: retry says COMPENSATED_NOT_RETRYABLE" subsection: start a **new** workflow instance with a new workflowId carrying the business inputs; the old instance stays TERMINATED/FAILED as the audit record of the compensated run; do not attempt to resurrect the old id (the engine will refuse).
- Modify: `docs/open-issues.md` §Issue 16 — add a dated ruling callout: "**Ruling (2026-08-06):** the guard is the supported behaviour. Retry-after-compensation stays unsupported; the operator path is a new instance (docs/admin.md §…). Neither relaunch direction is planned."
- Verify: `demo/scripts/v1-to-v2-move.sh` — the handover says it "hardcodes a versioned jar name", but the main tree shows `V1_JAR` resolved by glob (lines 60–73, comment says the fix landed in the demo cycle's final wave; evidence `task-7-v1-jar-glob.log`) and `V2_JAR` uses the version-independent fixed name `loan-application-v2.jar` (produced by `v2BootJar` with `archiveFileName` pinned, `loan-application-service/build.gradle.kts:81`).

- [ ] **Step 1:** Make the two doc edits.
- [ ] **Step 2:** Jar item — verify, don't assume: `bash -n demo/scripts/v1-to-v2-move.sh`; then `grep -n 'loan-application-service-[0-9]' demo/scripts/*.sh demo/scripts/lib/*.sh` (a literal version digit after the artifact name = residual hardcoding; expect zero hits). If zero: record in the ledger that the handover item was already resolved by the demo cycle's final wave (glob at `v1-to-v2-move.sh:69`, fixed name at `:74`) and note it in `tasks/todo.md`'s inherited-items section. If any hit: apply the same glob pattern used at `:69` and re-run `bash -n`.
- [ ] **Step 3:** Commit: `docs: Issue 16 ruling + operator path; close inherited demo jar-name item (verified already fixed)`.

---

### Task 14: File new issues; Resolved callouts; release notes

**Files:**
- Modify: `docs/open-issues.md` — §4 index rows + new sections + Resolved callouts
- Modify: `docs/release-notes.md`
- Modify: `tasks/todo.md` — cycle summary + review section

- [ ] **Step 1:** Resolved callouts (follow the house pattern — a `> **Resolved.**` blockquote at the top of each section naming mechanism, commits, and pinning tests — copy the voice of Issue 21's callout):
  - Issue 22 → resolved (Task 6; name the pins and the mutation round).
  - Issue 23 → resolved (Tasks 1–4; name the beforeName decision, the precedence rule, both trace pins, the workaround removal). Update the issue's property table with the ConsumerFactory row the audit added (F1 refinement) so the record is complete.
  - Issue 24 → resolved (Task 5; all three measures).
  - Issue 16 → ruling callout (Task 13 wrote it; verify the index row says "Open — ruled, guard is the supported behaviour" rather than plain Open).
- [ ] **Step 2:** New sections, numbered next-in-sequence (25, 26, 27), each with Kind/Severity/What's wrong/file:line/Done-when, from the audit report (`tasks/audit-2026-08-05-inert-config.md`):
  - **Issue 25 (from F7):** `maestro.worker.*` documented but wholly unimplemented (`MaestroProperties.java:326-346` bound, zero consumers; `docs/configuration.md:217-246,469-475`). Product decision: implement task-queue concurrency or retract the docs. Include the interim step actually taken this cycle: **mark the configuration.md worker section with a prominent "not yet implemented" warning** (do that edit in this task — it is the one part of F7 that cannot wait, since the minimal example teaches a no-op) — the issue tracks the real decision.
  - **Issue 26 (from the demo-cycle deferred item):** terminal instance write + terminal event append are two non-transactional calls; proposed `WorkflowStore.finaliseInstance(instance, terminalEvent)` both-or-neither contract; explicitly warn against append-then-status reordering (two converging runners both append; `getRecoverableInstances()` re-invokes a completed workflow).
  - **Issue 27 (doc gap, Low):** `maestro.workflow-packages` consumed (`DurableWorkflowBeanRegistrar.java:64`) but absent from `docs/configuration.md`. (Or simply add the missing row to configuration.md in this task and skip filing — prefer fixing: add the row, no new issue.)
- [ ] **Step 3:** `docs/release-notes.md` Unreleased section, one entry per behaviour change: spring.kafka.* honoured + observation defaults (Issue 23), redelivery flag + DLT check (Issue 24), terminate-vs-compensation (Issue 22), maestro.enabled cross-module (F8), retry defaults wiring (F6), Valkey connection properties (F5), admin-client topic property (F10). Each entry names its migration note where applicable (e.g. "if you relied on Maestro ignoring spring.kafka.producer.*, your properties now apply").
- [ ] **Step 4:** Cross-check sweep (case-insensitive, lesson 2026-08-03): `grep -rin "ObservedKafkaTemplateConfig\|scope limit\|spring.kafka.producer" docs/ | grep -v open-issues` — every remaining hit must be intentional. Commit: `docs: resolved callouts for Issues 22-24, Issue 16 ruling, new Issues 25-26, release notes`.

---

### Task 15: Verification gates (whole-branch)

**Files:** none (verification only; fixes loop back into the owning task's files)

Run each gate from the worktree, foreground, bounded, with identity-stamped logs into the SDD workspace evidence dir (`pwd`, `git rev-parse HEAD`, timestamp echoed at the top of each log — lesson 2026-07-30):

- [ ] **Gate 1:** `./gradlew build 2>&1 | tee evidence/gate1-build.log` — green, repo-wide (includes maestro-integration-tests' 65+ ITs; needs Docker).
- [ ] **Gate 2:** loan E2E: `cd <worktree>/maestro-samples/sample-loan-origination && ./e2e/run-e2e.sh 2>&1 | tee ../../<evidence>/gate2-loan-e2e.log` — **10/10 scenarios**, and verify process identity per the house rule (PIDs in service logs match the run's pid files; ports confirmed free beforehand — the script does this; confirm its own checks passed in the log).
- [ ] **Gate 3:** chaos PR-gate: `./gradlew :maestro-integration-tests:e2eTest --rerun-tasks 2>&1 | tee evidence/gate3-chaos.log` — VERDICT: PASS, violations: []. This exercises the changed Kafka wiring under a real six-node cluster.
- [ ] **Gate 4:** demo preflight **cold**: `cd <worktree>/demo && docker compose down -v && ./scripts/preflight.sh 2>&1 | tee ../<evidence>/gate4-preflight-cold.log` — passes from a clean stack (the demo depends on the exact wiring Tasks 1–2, 5 changed; `down -v` first is mandatory — a warm re-run masks cold-start defects, lesson 2026-08-04).
- [ ] **Step 5:** Any red gate: stop, diagnose via superpowers:systematic-debugging, fix in the owning task's scope with its own RED pin, re-run the failed gate, then re-run Gate 1.
- [ ] **Step 6:** Final commit of evidence index; hand over to the whole-branch final review per superpowers:subagent-driven-development, then superpowers:finishing-a-development-branch (integration menu — no merge without the user's answer).

---

## Self-Review (performed at write time)

- **Spec coverage:** Issue 23 → Tasks 1–4; Issue 24 → Task 5; Issue 22 → Task 6; F8/F9/F10/F5/F6 → Tasks 7–11; F3 → Task 2; Issue 16 + inherited → Tasks 12–13; filing → Task 14; gates → Task 15. The spec's `afterName`-on-KafkaAutoConfiguration line was found wrong during planning and corrected in the spec (beforeName; see spec §1 "Ordering correction").
- **Known judgment points for implementers** (flagged, not placeholders): exact `KafkaConnectionDetails` accessor names (verify via javap, Task 1); `KafkaTemplate.isObservationEnabled` getter availability (Task 2); Boot's multi-name `@ConditionalOnProperty` AND-semantics (Task 7); Lettuce `RedisURI.Builder` method names (Task 10). Each has a verification command in its task.
- **Type consistency:** `observationEnabled(Boolean, boolean)` defined in Task 2 and referenced only there + BPP; `STATUS_WRITE_ATTEMPTS` widened in Task 6 where referenced; bean names `maestroKafkaProducerFactory`/`maestroKafkaTemplate`/`maestroKafkaConsumerFactory` unchanged across Tasks 1–5.
