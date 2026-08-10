package io.b2mash.maestro.spring.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that every nested block of {@link MaestroProperties} actually binds
 * from configuration.
 *
 * <h2>Why this test exists</h2>
 * <p>The nested blocks are records, so Spring Boot binds them with
 * <em>value-object</em> (constructor) binding. Value-object binding is skipped
 * for any type that also declares a no-argument constructor — Boot then treats
 * it as a JavaBean and, a record having no setters, silently binds nothing.
 * Every {@code maestro.messaging.*}, {@code maestro.lock.*},
 * {@code maestro.timer.*}, {@code maestro.recovery.*}, {@code maestro.retry.*}
 * and {@code maestro.store.*} value was therefore ignored, leaving the defaults
 * in place with no error anywhere. The defect surfaced in the P1 Kafka suite:
 * {@code maestro.messaging.topics.admin-events} had no effect, so lifecycle
 * events went to the default topic and vanished (publishing is fire-and-forget).
 *
 * <p>These assertions are the regression pin: each block must reflect the
 * configured value, not its default.
 */
class MaestroPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    @DisplayName("messaging topics and consumer group bind from configuration")
    void messagingBlockBinds() {
        runner.withPropertyValues(
                        "maestro.service-name=binding-test",
                        "maestro.messaging.type=kafka",
                        "maestro.messaging.consumer-group=custom-group",
                        "maestro.messaging.topics.tasks=custom.tasks",
                        "maestro.messaging.topics.signals=custom.signals",
                        "maestro.messaging.topics.admin-events=custom.admin.events")
                .run(context -> {
                    var messaging = context.getBean(MaestroProperties.class).getMessaging();
                    assertThat(messaging.consumerGroup()).isEqualTo("custom-group");
                    assertThat(messaging.topics().tasks()).isEqualTo("custom.tasks");
                    assertThat(messaging.topics().signals()).isEqualTo("custom.signals");
                    assertThat(messaging.topics().adminEvents()).isEqualTo("custom.admin.events");
                });
    }

    @Test
    @DisplayName("the redelivery block binds from configuration")
    void redeliveryBlockBinds() {
        runner.withPropertyValues(
                        "maestro.service-name=binding-test",
                        "maestro.messaging.redelivery.enabled=false",
                        "maestro.messaging.redelivery.max-attempts=4",
                        "maestro.messaging.redelivery.initial-interval=250ms",
                        "maestro.messaging.redelivery.multiplier=3.5",
                        "maestro.messaging.redelivery.max-interval=90s",
                        "maestro.messaging.redelivery.dead-letter-suffix=.dead")
                .run(context -> {
                    var redelivery = context.getBean(MaestroProperties.class).getMessaging().redelivery();
                    assertThat(redelivery.enabled()).isFalse();
                    assertThat(redelivery.maxAttempts()).isEqualTo(4);
                    assertThat(redelivery.initialInterval()).isEqualTo(Duration.ofMillis(250));
                    assertThat(redelivery.multiplier()).isEqualTo(3.5);
                    assertThat(redelivery.maxInterval()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(redelivery.deadLetterSuffix()).isEqualTo(".dead");
                });
    }

    @Test
    @DisplayName("redelivery.enabled defaults to true when unset")
    void redeliveryEnabledDefaultsTrue() {
        runner.withPropertyValues("maestro.service-name=binding-test")
                .run(context -> {
                    var redelivery = context.getBean(MaestroProperties.class).getMessaging().redelivery();
                    assertThat(redelivery.enabled()).isTrue();
                });
    }

    @Test
    @DisplayName("store, lock, timer, recovery and retry blocks bind from configuration")
    void remainingBlocksBind() {
        runner.withPropertyValues(
                        "maestro.service-name=binding-test",
                        "maestro.store.table-prefix=custom_",
                        "maestro.lock.type=postgres",
                        "maestro.lock.key-prefix=custom:lock:",
                        "maestro.lock.ttl=45s",
                        "maestro.timer.poll-interval=250ms",
                        "maestro.timer.batch-size=7",
                        "maestro.recovery.enabled=false",
                        "maestro.recovery.poll-interval=90s",
                        "maestro.retry.default-max-attempts=5",
                        "maestro.admin.events.enabled=false",
                        "maestro.admin.events.topic=custom.admin",
                        "maestro.shutdown.timeout=15s",
                        "maestro.signal.wake-recheck-interval=5s")
                .run(context -> {
                    var properties = context.getBean(MaestroProperties.class);
                    assertThat(properties.getStore().tablePrefix()).isEqualTo("custom_");
                    assertThat(properties.getLock().type()).isEqualTo("postgres");
                    assertThat(properties.getLock().keyPrefix()).isEqualTo("custom:lock:");
                    assertThat(properties.getLock().ttl()).isEqualTo(Duration.ofSeconds(45));
                    assertThat(properties.getTimer().pollInterval()).isEqualTo(Duration.ofMillis(250));
                    assertThat(properties.getTimer().batchSize()).isEqualTo(7);
                    assertThat(properties.getRecovery().enabled()).isFalse();
                    assertThat(properties.getRecovery().pollInterval()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(properties.getRetry().defaultMaxAttempts()).isEqualTo(5);
                    assertThat(properties.getAdmin().events().enabled()).isFalse();
                    assertThat(properties.getAdmin().events().topic()).isEqualTo("custom.admin");
                    assertThat(properties.getShutdown().timeout()).isEqualTo(Duration.ofSeconds(15));
                    assertThat(properties.getSignal().wakeRecheckInterval()).isEqualTo(Duration.ofSeconds(5));
                });
    }

    @Test
    @DisplayName("the observability block binds from configuration")
    void observabilityBlockBinds() {
        runner.withPropertyValues(
                        "maestro.service-name=binding-test",
                        "maestro.observability.metrics.enabled=false",
                        "maestro.observability.tracing.enabled=false")
                .run(context -> {
                    var observability = context.getBean(MaestroProperties.class).getObservability();
                    assertThat(observability.metrics().enabled()).isFalse();
                    assertThat(observability.tracing().enabled()).isFalse();
                });
    }

    @Test
    @DisplayName("unset nested properties keep their documented defaults")
    void defaultsSurviveWhenNothingIsConfigured() {
        runner.withPropertyValues("maestro.service-name=binding-test").run(context -> {
            var properties = context.getBean(MaestroProperties.class);
            assertThat(properties.getStore().type()).isEqualTo("postgres");
            assertThat(properties.getStore().tablePrefix()).isEqualTo("maestro_");
            assertThat(properties.getMessaging().type()).isEqualTo("kafka");
            assertThat(properties.getMessaging().consumerGroup()).isNull();
            assertThat(properties.getMessaging().topics().tasks()).isNull();
            assertThat(properties.getMessaging().topics().adminEvents()).isEqualTo("maestro.admin.events");
            var redelivery = properties.getMessaging().redelivery();
            assertThat(redelivery.enabled()).isTrue();
            assertThat(redelivery.maxAttempts()).isEqualTo(10);
            assertThat(redelivery.initialInterval()).isEqualTo(Duration.ofSeconds(1));
            assertThat(redelivery.multiplier()).isEqualTo(2.0);
            assertThat(redelivery.maxInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(redelivery.deadLetterSuffix()).isEqualTo(".DLT");
            assertThat(properties.getLock().type()).isEqualTo("valkey");
            assertThat(properties.getLock().ttl()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getTimer().pollInterval()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.getTimer().batchSize()).isEqualTo(100);
            assertThat(properties.getRecovery().enabled()).isTrue();
            assertThat(properties.getRetry().defaultMaxAttempts()).isEqualTo(3);
            assertThat(properties.getWorker().taskQueues()).isEmpty();
            assertThat(properties.getAdmin().events().topic()).isEqualTo("maestro.admin.events");
            assertThat(properties.getShutdown().timeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getSignal().wakeRecheckInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getObservability().metrics().enabled()).isTrue();
            assertThat(properties.getObservability().tracing().enabled()).isTrue();
        });
    }

    @Test
    @DisplayName("worker task queues bind as a list of value objects")
    void workerTaskQueuesBind() {
        runner.withPropertyValues(
                        "maestro.service-name=binding-test",
                        "maestro.worker.task-queues[0].name=orders",
                        "maestro.worker.task-queues[0].concurrency=5")
                .run(context -> {
                    var queues = context.getBean(MaestroProperties.class).getWorker().taskQueues();
                    assertThat(queues).hasSize(1);
                    assertThat(queues.getFirst().name()).isEqualTo("orders");
                    assertThat(queues.getFirst().concurrency()).isEqualTo(5);
                    assertThat(queues.getFirst().activityConcurrency()).isEqualTo(20);
                });
    }

    /** Enables the properties without dragging in the rest of the engine. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class PropertiesConfiguration {
    }
}
