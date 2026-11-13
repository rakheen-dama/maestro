package io.b2mash.maestro.messaging.kafka.listener;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.LongDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.List;
import java.util.function.Function;

import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.CONSUMER_FACTORY;
import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.TEMPLATE;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.coexistenceRunner;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.consumerConfig;
import static org.apache.kafka.clients.consumer.ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG;
import static org.apache.kafka.clients.consumer.ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.util.ReflectionTestUtils.getField;

/**
 * Pins the boundary between a host's plain {@code @KafkaListener} and a
 * {@code @MaestroSignalListener} in one application (WP7, todo A6).
 *
 * <p>A plain {@code @KafkaListener} is Spring Boot's: Boot's
 * {@code kafkaListenerContainerFactory} over Boot's {@code kafkaConsumerFactory}
 * bean, {@code spring.kafka.consumer.*} deserializers, {@code spring.kafka.listener.*},
 * its own group, the host's {@code CommonErrorHandler} bean and Boot's
 * {@code kafkaTemplate} as reply template. A {@code @MaestroSignalListener} is
 * Maestro's: a container the post-processor builds itself (not through any
 * container factory or the endpoint registry) over {@code maestroKafkaConsumerFactory},
 * with the forced {@code ByteArrayDeserializer}, the engine-owned group
 * {@code maestro-<service>}, {@code AckMode.RECORD} and Maestro's dead-lettering
 * error handler publishing through {@code maestroKafkaTemplate}. Neither leaks
 * into the other.
 *
 * <p>Built from the real auto-configurations ({@code CoexistenceFixtures.coexistenceRunner()}).
 * The mocked {@link WorkflowExecutor} is a collaborator, not under test. No
 * broker: the host container is not started ({@code spring.kafka.listener.auto-startup=false}),
 * and the Maestro container starts and polls an unreachable address in the
 * background, as in {@code MaestroSignalListenerContainerConfigTest}; the wiring
 * asserted here is fixed before it polls.
 */
@DisplayName("The plain @KafkaListener / @MaestroSignalListener boundary")
class KafkaListenerBoundaryTest {

    /**
     * The connection-details broker: a resolvable address that differs from
     * {@code spring.kafka.bootstrap-servers} ({@code localhost:1}). Nothing
     * listens there. It must resolve, because the Maestro container constructs
     * its consumer during context refresh, and a consumer refuses an
     * unresolvable bootstrap host ({@code ConfigException: No resolvable
     * bootstrap urls}).
     */
    static final String DETAILS_BOOTSTRAP = "127.0.0.1:19092";

    private final ApplicationContextRunner runner = coexistenceRunner()
            .withUserConfiguration(BoundaryConfiguration.class)
            .withBean(WorkflowExecutor.class, () -> mock(WorkflowExecutor.class))
            .withPropertyValues(
                    "maestro.service-name=boundary",
                    "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.LongDeserializer",
                    "spring.kafka.listener.ack-mode=batch",
                    "spring.kafka.listener.auto-startup=false");

    @Test
    @DisplayName("a plain @KafkaListener rides Boot's factory and beans; a @MaestroSignalListener rides Maestro's; neither leaks into the other")
    void aPlainKafkaListenerRidesBootsFactoryAndAMaestroListenerRidesMaestros() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var registry = ctx.getBean(KafkaListenerEndpointRegistry.class);
            assertThat(registry.getListenerContainerIds())
                    .as("only the plain listener is built through Boot's container factory and registry")
                    .containsExactly("host-listener");

            // ── The plain @KafkaListener: Boot's container factory and beans ──
            var host = (ConcurrentMessageListenerContainer<?, ?>) registry.getListenerContainer("host-listener");
            var bootFactory = ctx.getBean("kafkaListenerContainerFactory", ConcurrentKafkaListenerContainerFactory.class);
            assertThat(bootFactory.getConsumerFactory()).isSameAs(ctx.getBean("kafkaConsumerFactory"));
            assertThat(getField(host, "consumerFactory")).isSameAs(ctx.getBean("kafkaConsumerFactory"));
            assertThat(consumerConfig(host))
                    .containsEntry(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                    .containsEntry(VALUE_DESERIALIZER_CLASS_CONFIG, LongDeserializer.class);
            assertThat(host.getContainerProperties().getGroupId()).isEqualTo("host-group");
            assertThat(host.getContainerProperties().getAckMode()).isEqualTo(AckMode.BATCH);
            assertThat(host.isAutoStartup()).isFalse();
            assertThat(host.getCommonErrorHandler()).isSameAs(ctx.getBean("hostErrorHandler"));
            assertThat(recoverer(host.getCommonErrorHandler()))
                    .as("no dead-letter topic unless the host configures one")
                    .isNotInstanceOf(DeadLetterPublishingRecoverer.class);
            assertThat(getField(bootFactory, "replyTemplate")).isSameAs(ctx.getBean("kafkaTemplate"));

            // ── The @MaestroSignalListener: Maestro's consumer factory, group, ack mode, DLT ──
            var maestro = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting().getFirst();
            assertThat(getField(maestro, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
            assertThat(consumerConfig(maestro))
                    .containsEntry(KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
                    .containsEntry(VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class)
                    .containsEntry(GROUP_ID_CONFIG, "maestro-boundary");
            assertThat(maestro.getContainerProperties().getGroupId()).isEqualTo("maestro-boundary");
            assertThat(maestro.getContainerProperties().getAckMode()).isEqualTo(AckMode.RECORD);
            assertThat(maestro.isRunning())
                    .as("spring.kafka.listener.auto-startup=false does not reach a Maestro container")
                    .isTrue();
            assertThat(maestro.getCommonErrorHandler())
                    .isNotSameAs(ctx.getBean("hostErrorHandler"))
                    .isInstanceOf(DefaultErrorHandler.class);
            var maestroRecoverer = recoverer(maestro.getCommonErrorHandler());
            assertThat(maestroRecoverer).isInstanceOf(DeadLetterPublishingRecoverer.class);
            @SuppressWarnings("unchecked")
            var templateResolver = (Function<ProducerRecord<?, ?>, ?>) getField(maestroRecoverer, "templateResolver");
            assertThat(templateResolver.apply(new ProducerRecord<>("boundary.signals.DLT", "k", new byte[] {1})))
                    .as("dead letters go through Maestro's byte[] template")
                    .isSameAs(ctx.getBean(TEMPLATE));
        });
    }

    @Test
    @DisplayName("KafkaConnectionDetails reach a plain @KafkaListener's consumer and Maestro's")
    void aPlainKafkaListenerHonoursKafkaConnectionDetails() {
        runner.withUserConfiguration(ConnectionDetailsConfiguration.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var host = ctx.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("host-listener");
            assertThat(consumerConfig(host).get(BOOTSTRAP_SERVERS_CONFIG)).hasToString("[" + DETAILS_BOOTSTRAP + "]");
            var maestro = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting().getFirst();
            assertThat(consumerConfig(maestro).get(BOOTSTRAP_SERVERS_CONFIG)).hasToString("[" + DETAILS_BOOTSTRAP + "]");
        });
    }

    private static Object recoverer(CommonErrorHandler handler) {
        return getField(getField(handler, "failureTracker"), "recoverer");
    }

    /** A host's plain {@code @KafkaListener} for its own {@code Long} payload. */
    public static class HostOrderListener {
        @KafkaListener(id = "host-listener", topics = "host.orders", groupId = "host-group")
        public void onOrder(Long value) {
        }
    }

    /** A {@code @MaestroSignalListener} in the same application. */
    public static class BoundarySignalListener {
        @MaestroSignalListener(topic = "boundary.signals", signalName = "boundary")
        public SignalRouting handle(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class BoundaryConfiguration {

        @Bean
        HostOrderListener hostOrderListener() {
            return new HostOrderListener();
        }

        @Bean
        BoundarySignalListener boundarySignalListener() {
            return new BoundarySignalListener();
        }

        @Bean
        CommonErrorHandler hostErrorHandler() {
            return new DefaultErrorHandler(new FixedBackOff(0L, 1L));
        }
    }

    /** What a Testcontainers {@code @ServiceConnection} would contribute. */
    @Configuration(proxyBeanMethods = false)
    static class ConnectionDetailsConfiguration {

        @Bean
        KafkaConnectionDetails connectionDetails() {
            return () -> List.of(DETAILS_BOOTSTRAP);
        }
    }
}
