package io.b2mash.maestro.messaging.kafka.listener;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.messaging.kafka.KafkaTestSupport;
import io.b2mash.maestro.messaging.kafka.KafkaTopicVerifier;
import io.b2mash.maestro.messaging.kafka.KafkaWorkflowMessaging;
import io.b2mash.maestro.messaging.kafka.config.KafkaMessagingAutoConfiguration;
import io.b2mash.maestro.spring.annotation.MaestroSignalListener;
import io.b2mash.maestro.spring.annotation.SignalRouting;
import io.b2mash.maestro.spring.config.MaestroProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The Kafka transport's startup gate against a real broker with auto-create
 * <b>off</b> (the {@link KafkaTestSupport} broker): a context whose required
 * topics do not exist must refuse to start under the default
 * {@code maestro.messaging.topics.verify-on-startup=fail}, naming every
 * missing topic; must start under {@code warn}, saying the same at WARN; and,
 * when every topic exists, must start with the consumer containers wired to
 * stay fatal on a topic that later disappears.
 *
 * <p>Each test uses its own service name so the engine's signal topic is
 * genuinely absent for the failure cases even though the broker is shared by
 * the whole class.
 */
@DisplayName("Startup topic verification against a real broker with auto-create off")
class KafkaTopicVerificationStartupTest extends KafkaTestSupport {

    /**
     * One listener topic per test: {@code @MaestroSignalListener.topic} is a
     * compile-time constant (the processor reads it verbatim, no SpEL), and the
     * broker is shared by the class, so each test owns a distinct topic whose
     * {@code .DLT} it does or does not create.
     */
    static final String FAIL_TOPIC = "verify.startup.fail.events";
    static final String OK_TOPIC = "verify.startup.ok.events";
    static final String WARN_TOPIC = "verify.startup.warn.events";
    static final String MIN_TOPIC = "verify.startup.min.events";

    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        verifierLogger().addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        verifierLogger().detachAppender(logAppender);
    }

    private ApplicationContextRunner runner(String service, Class<?> listenerConfiguration) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class, listenerConfiguration)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(WorkflowExecutor.class, () -> mock(WorkflowExecutor.class))
                .withPropertyValues(
                        "maestro.service-name=" + service,
                        "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers());
    }

    @Test
    @DisplayName("fail (the default): a missing topic fails context refresh, naming every missing topic and a create line each")
    void failMode_missingTopicsFailStartup() throws Exception {
        var service = "verify-fail-" + suffix();
        // The listener topic exists; its .DLT, the engine signal topic and its .DLT do not.
        createTopics(FAIL_TOPIC);

        runner(service, FailListenerConfiguration.class)
                .withPropertyValues("maestro.messaging.topics.admin-events=verify.admin." + service)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    // afterSingletonsInstantiated's exception propagates out of refresh()
                    // unwrapped — it is the startup failure itself, not a cause of one.
                    var failure = ctx.getStartupFailure();
                    assertThat(failure)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("maestro.signals." + service + ".DLT")
                            .hasMessageContaining("maestro.signals." + service)
                            .hasMessageContaining("verify.admin." + service)
                            .hasMessageContaining(FAIL_TOPIC + ".DLT")
                            .hasMessageContaining("kafka-topics.sh --bootstrap-server " + kafka.getBootstrapServers()
                                    + " --create --topic maestro.signals." + service + ".DLT")
                            .hasMessageContaining(KafkaTopicVerifier.PROPERTY + "=warn");
                });
    }

    @Test
    @DisplayName("fail: with every required topic present the context starts and containers are fatal on missing topics")
    void failMode_allPresentStartsWithFatalContainers() throws Exception {
        var service = "verify-ok-" + suffix();
        createTopics("maestro.signals." + service, "maestro.signals." + service + ".DLT",
                "verify.admin." + service, OK_TOPIC, OK_TOPIC + ".DLT");

        runner(service, OkListenerConfiguration.class)
                .withPropertyValues("maestro.messaging.topics.admin-events=verify.admin." + service)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var bpp = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class);
                    var containers = bpp.containersForTesting();
                    assertThat(containers).hasSize(1);
                    var props = containers.get(0).getContainerProperties();
                    assertThat(props.isMissingTopicsFatal())
                            .as("under fail, once the probe confirmed every topic, a topic that vanishes "
                                    + "after startup must fail the container, not leave it polling nothing")
                            .isTrue();
                    assertThat(ReflectionTestUtils.getField(ctx.getBean(KafkaWorkflowMessaging.class),
                            "missingTopicsFatal"))
                            .as("the engine signal channel was handed the same confirmed-present outcome")
                            .isEqualTo(true);
                    assertThat(props.getAuthExceptionRetryInterval()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(containers.get(0).isRunning()).isTrue();
                    assertThat(warnMessages()).isEmpty();
                    assertThat(logAppender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                            .contains("Verified 5 Kafka topic(s)"));
                });
    }

    @Test
    @DisplayName("warn: the context starts, the missing topics are named at WARN, and containers stay tolerant")
    void warnMode_startsAndWarns() throws Exception {
        var service = "verify-warn-" + suffix();
        // The subscribed topics exist (a container on a missing topic would have
        // nothing to poll either way); the .DLTs and admin topic are the gap.
        createTopics("maestro.signals." + service, WARN_TOPIC);

        runner(service, WarnListenerConfiguration.class)
                .withPropertyValues(
                        "maestro.messaging.topics.admin-events=verify.admin." + service,
                        "maestro.messaging.topics.verify-on-startup=warn")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(warnMessages()).anySatisfy(m -> assertThat(m)
                            .contains("maestro.signals." + service + ".DLT")
                            .contains(WARN_TOPIC + ".DLT")
                            .contains("verify.admin." + service)
                            .contains("kafka-topics.sh"));
                    var props = ctx.getBean(MaestroSignalListenerBeanPostProcessor.class)
                            .containersForTesting().get(0).getContainerProperties();
                    assertThat(props.isMissingTopicsFatal()).isFalse();
                });
    }

    @Test
    @DisplayName("fail: admin events disabled and redelivery disabled shrink the requirement to the subscribed topics")
    void failMode_disabledFeaturesAreNotRequired() throws Exception {
        var service = "verify-min-" + suffix();
        createTopics("maestro.signals." + service, MIN_TOPIC);

        runner(service, MinListenerConfiguration.class)
                .withPropertyValues(
                        "maestro.admin.events.enabled=false",
                        "maestro.messaging.redelivery.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).as("no .DLT and no admin topic may be demanded when neither feature is on")
                            .hasNotFailed();
                    assertThat(logAppender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                            .contains("Verified 2 Kafka topic(s)"));
                });
    }

    @Test
    @DisplayName("fail: verification runs with no @MaestroSignalListener at all — the engine's own topics are still required")
    void failMode_runsWithoutAnyListenerRegistration() {
        var service = "verify-nolistener-" + suffix();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues(
                        "maestro.service-name=" + service,
                        "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers())
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("maestro.signals." + service);
                });
    }

    // ── Fixtures ──────────────────────────────────────────────────────────

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public static class FailRoutingBean {
        @MaestroSignalListener(topic = FAIL_TOPIC, signalName = "verify.signal")
        public SignalRouting handle(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    public static class OkRoutingBean {
        @MaestroSignalListener(topic = OK_TOPIC, signalName = "verify.signal")
        public SignalRouting handle(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    public static class WarnRoutingBean {
        @MaestroSignalListener(topic = WARN_TOPIC, signalName = "verify.signal")
        public SignalRouting handle(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    public static class MinRoutingBean {
        @MaestroSignalListener(topic = MIN_TOPIC, signalName = "verify.signal")
        public SignalRouting handle(String event) {
            return SignalRouting.builder().workflowId("wf-" + event).build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailListenerConfiguration {
        @Bean
        FailRoutingBean failRoutingBean() {
            return new FailRoutingBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OkListenerConfiguration {
        @Bean
        OkRoutingBean okRoutingBean() {
            return new OkRoutingBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WarnListenerConfiguration {
        @Bean
        WarnRoutingBean warnRoutingBean() {
            return new WarnRoutingBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MinListenerConfiguration {
        @Bean
        MinRoutingBean minRoutingBean() {
            return new MinRoutingBean();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class PropertiesConfiguration {
    }

    private static ch.qos.logback.classic.Logger verifierLogger() {
        return (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(KafkaTopicVerifier.class);
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(event -> "WARN".equals(event.getLevel().toString()))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
