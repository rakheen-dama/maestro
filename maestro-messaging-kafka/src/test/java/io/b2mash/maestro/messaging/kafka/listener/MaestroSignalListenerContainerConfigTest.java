package io.b2mash.maestro.messaging.kafka.listener;

import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.messaging.kafka.KafkaTracePropagation;
import io.b2mash.maestro.messaging.kafka.config.KafkaMessagingAutoConfiguration;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import io.b2mash.maestro.spring.config.MaestroProperties;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.FixedBackOff;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Context-level pins for {@link MaestroSignalListenerBeanPostProcessor}
 * activation (Issue 23 part 2, finding F3):
 * <ul>
 *   <li>listener containers follow the same observation-enablement rule as
 *       {@code maestroKafkaTemplate}
 *       ({@link KafkaMessagingAutoConfiguration#observationEnabled});</li>
 *   <li>a user-defined {@link ConsumerFactory} bean must not make the engine's
 *       consumer-factory lookup ambiguous (F3) — the maestro-named bean must
 *       always be the one actually used;</li>
 *   <li>an inbound {@code traceparent} header reaches the handler via
 *       {@link KafkaTracePropagation#runWithExtractedContext}.</li>
 * </ul>
 *
 * <p>No broker is required: {@link ConcurrentMessageListenerContainer#start()}
 * only spins up the background consumer thread, which fails asynchronously
 * against an unreachable {@code spring.kafka.bootstrap-servers} without
 * blocking context refresh — the wiring under test here (which factory,
 * which observation flag, which listener lambda) is fully determined before
 * that thread ever polls.
 */
class MaestroSignalListenerContainerConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class, SignalListenerBeanConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(WorkflowExecutor.class, () -> mock(WorkflowExecutor.class))
            .withPropertyValues(
                    "maestro.service-name=signal-listener-container-test",
                    "spring.kafka.bootstrap-servers=localhost:19092");

    @Test
    @DisplayName("container observationEnabled follows the shared rule: on when a Tracer+Propagator pair is present")
    void containerObservationFollowsTheSharedRule() {
        runner.withBean(Tracer.class, () -> mock(Tracer.class))
                .withBean(Propagator.class, () -> mock(Propagator.class))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var bpp = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class);
                    var containers = bpp.containersForTesting();
                    assertThat(containers).hasSize(1);
                    assertThat(containers.get(0).getContainerProperties().isObservationEnabled()).isTrue();
                });
    }

    @Test
    @DisplayName("container observationEnabled stays off with no Tracer and no property set")
    void containerObservationOffWithoutTracer() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var bpp = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class);
            var containers = bpp.containersForTesting();
            assertThat(containers).hasSize(1);
            assertThat(containers.get(0).getContainerProperties().isObservationEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("F3: a user-defined ConsumerFactory bean does not break activation — the maestro-named one is used")
    void userDefinedConsumerFactoryDoesNotBreakActivation_F3() {
        runner.withBean("someOtherConsumerFactory", ConsumerFactory.class, () -> mock(ConsumerFactory.class))
                .run(ctx -> {
                    assertThat(ctx)
                            .as("a second ConsumerFactory bean must not make afterSingletonsInstantiated's "
                                    + "by-type lookup ambiguous")
                            .hasNotFailed();
                    var bpp = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class);
                    var containers = bpp.containersForTesting();
                    assertThat(containers).hasSize(1);

                    var expected = ctx.getBean("maestroKafkaConsumerFactory", ConsumerFactory.class);
                    var actual = ReflectionTestUtils.getField(containers.get(0), "consumerFactory");
                    assertThat(actual)
                            .as("the listener container must use the maestro-named consumer factory, "
                                    + "not whichever ConsumerFactory bean the ambiguous lookup happened to prefer")
                            .isSameAs(expected);
                });
    }

    @Test
    @DisplayName("an inbound traceparent header reaches the handler via KafkaTracePropagation.runWithExtractedContext")
    void inboundTraceparentReachesTheHandlerContext() {
        var tracePropagation = mock(KafkaTracePropagation.class);
        when(tracePropagation.extractTraceparent(any())).thenReturn(
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        // A mock's void method is a no-op by default — the handler must actually
        // run for the deliverSignal assertion below to be meaningful.
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return null;
        }).when(tracePropagation).runWithExtractedContext(any(), any());

        runner.withBean(KafkaTracePropagation.class, () -> tracePropagation)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var bpp = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class);
                    var containers = bpp.containersForTesting();
                    assertThat(containers).hasSize(1);

                    @SuppressWarnings("unchecked")
                    var listener = (MessageListener<String, byte[]>)
                            containers.get(0).getContainerProperties().getMessageListener();

                    var value = "\"hello\"".getBytes(StandardCharsets.UTF_8);
                    var record = new ConsumerRecord<String, byte[]>("test.topic", 0, 0L, "key", value);
                    record.headers().add("traceparent",
                            "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"
                                    .getBytes(StandardCharsets.UTF_8));

                    listener.onMessage(record);

                    verify(tracePropagation).runWithExtractedContext(eq(record.headers()), any());
                    // Confirms the runnable passed to runWithExtractedContext actually ran —
                    // not just that runWithExtractedContext was called.
                    var executor = ctx.getBean(WorkflowExecutor.class);
                    verify(executor).deliverSignal(eq("wf-hello"), eq("test.signal"), any());
                });
    }

    @Test
    @DisplayName("maestro.messaging.redelivery.enabled=false installs a zero-retry handler with no dead-letter recoverer")
    void redeliveryDisabled_installsZeroRetryHandlerWithNoDeadLetterRecoverer() {
        runner.withPropertyValues("maestro.messaging.redelivery.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var bpp = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class);
                    var containers = bpp.containersForTesting();
                    assertThat(containers).hasSize(1);

                    var handler = containers.get(0).getCommonErrorHandler();
                    assertThat(handler).isInstanceOf(DefaultErrorHandler.class);

                    // FailedRecordProcessor (DefaultErrorHandler's superclass) has no public
                    // getter for its tracker's recoverer/backOff — reflection is the only way
                    // to pin the actual handler shape from outside the package.
                    var tracker = ReflectionTestUtils.getField(handler, "failureTracker");
                    var recoverer = ReflectionTestUtils.getField(tracker, "recoverer");
                    assertThat(recoverer)
                            .as("redelivery disabled must not install a DeadLetterPublishingRecoverer — "
                                    + "nothing should ever try to publish to a .DLT topic")
                            .isNotInstanceOf(DeadLetterPublishingRecoverer.class);

                    var backOff = ReflectionTestUtils.getField(tracker, "backOff");
                    assertThat(backOff).isInstanceOf(FixedBackOff.class);
                    var fixedBackOff = (FixedBackOff) backOff;
                    assertThat(fixedBackOff.getInterval()).isZero();
                    assertThat(fixedBackOff.getMaxAttempts()).isZero();
                });
    }

    /** Valid @MaestroSignalListener bean so registrations is non-empty and a container is created. */
    public static class SignalListenerBean {
        @MaestroSignalListener(topic = "test.topic", signalName = "test.signal")
        public SignalRouting handle(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SignalListenerBeanConfiguration {
        @Bean
        SignalListenerBean signalListenerBean() {
            return new SignalListenerBean();
        }
    }

    /** Binds {@link MaestroProperties} the way the starter's auto-configuration would. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class PropertiesConfiguration {
    }
}
