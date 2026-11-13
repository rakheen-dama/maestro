package io.b2mash.maestro.messaging.kafka.listener;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

import java.util.function.Function;

import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.CONSUMER_FACTORY;
import static io.b2mash.maestro.messaging.kafka.MaestroKafkaBeans.TEMPLATE;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.coexistenceRunner;
import static io.b2mash.maestro.messaging.kafka.config.CoexistenceFixtures.consumerConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.util.ReflectionTestUtils.getField;

/**
 * With Spring Boot's Kafka beans present beside Maestro's (WP7, 3.4), a
 * {@code @MaestroSignalListener} container still consumes through Maestro's
 * consumer factory and dead-letters through Maestro's {@code byte[]} template,
 * not Boot's, which by type is now the only default candidate.
 *
 * <p>Built from the real auto-configurations ({@code CoexistenceFixtures.coexistenceRunner()}).
 * The mocked {@link WorkflowExecutor} is a collaborator, not under test. No
 * broker: the container's consumer thread fails asynchronously against
 * {@code localhost:1}; the wiring asserted here is fixed before it polls.
 */
@DisplayName("A @MaestroSignalListener container uses Maestro's Kafka beans while Boot's exist")
class MaestroSignalListenerCoexistenceTest {

    @Test
    void theListenerContainerDeadLettersThroughMaestrosTemplate() {
        coexistenceRunner()
                .withUserConfiguration(ListenerConfiguration.class)
                .withBean(WorkflowExecutor.class, () -> mock(WorkflowExecutor.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed()
                            .as("Boot's beans exist, so a by-type lookup would find them")
                            .hasBean("kafkaTemplate").hasBean("kafkaConsumerFactory");
                    var containers = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class).containersForTesting();
                    assertThat(containers).hasSize(1);
                    var container = containers.get(0);

                    assertThat(getField(container, "consumerFactory")).isSameAs(ctx.getBean(CONSUMER_FACTORY));
                    assertThat(consumerConfig(container))
                            .containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

                    var handler = container.getCommonErrorHandler();
                    assertThat(handler).isInstanceOf(DefaultErrorHandler.class);
                    var recoverer = getField(getField(handler, "failureTracker"), "recoverer");
                    assertThat(recoverer).isInstanceOf(DeadLetterPublishingRecoverer.class);
                    @SuppressWarnings("unchecked")
                    var templateResolver = (Function<ProducerRecord<?, ?>, ?>) getField(recoverer, "templateResolver");
                    var deadLetterTemplate = templateResolver.apply(
                            new ProducerRecord<>("signals.topic.DLT", "key", new byte[] {1}));
                    assertThat(deadLetterTemplate)
                            .as("dead letters go through Maestro's byte[] template")
                            .isSameAs(ctx.getBean(TEMPLATE));
                });
    }

    /** A valid {@code @MaestroSignalListener}, so the post-processor builds one container. */
    public static class Listener {
        @MaestroSignalListener(topic = "signals.topic", signalName = "test.signal")
        public SignalRouting handle(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenerConfiguration {
        @Bean
        Listener listener() {
            return new Listener();
        }
    }
}
