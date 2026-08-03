package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.messaging.kafka.KafkaTracePropagation;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.b2mash.maestro.spring.config.MaestroProperties;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration pins for the Kafka side of the trace-propagation contract
 * (observability design doc §7.2).
 */
@DisplayName("KafkaMessagingAutoConfiguration wires KafkaTracePropagation")
class KafkaMessagingAutoConfigurationTracingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("maestro.service-name=kafka-tracing-test");

    /**
     * The same ordering exposure Task 4's fix round 1 found for meters and Task
     * 5 found for the starter: {@code io.b2mash.maestro.messaging.kafka.config}
     * sorts before {@code org.springframework.boot.micrometer.tracing.*}, so
     * without an explicit {@code afterName} the {@code
     * @ConditionalOnBean({Tracer.class, Propagator.class})} gate is evaluated
     * before Boot has registered either bean and every published record silently
     * loses its trace context. A {@code withBean} Tracer cannot reproduce that.
     */
    @Test
    @DisplayName("registers through the real Boot tracing auto-configuration chain, not a withBean Tracer stub")
    void wiresThroughRealBootTracingAutoConfigurationChain() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        OpenTelemetrySdkAutoConfiguration.class,
                        MicrometerTracingAutoConfiguration.class,
                        OpenTelemetryTracingAutoConfiguration.class,
                        KafkaMessagingAutoConfiguration.class))
                .withUserConfiguration(PropertiesConfiguration.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("maestro.service-name=kafka-tracing-chain-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context)
                            .as("Boot's tracing chain must supply Tracer and Propagator for this "
                                    + "test to be meaningful")
                            .hasSingleBean(Tracer.class);
                    assertThat(context).hasSingleBean(Propagator.class);
                    assertThat(context).hasSingleBean(KafkaTracePropagation.class);
                });
    }

    @Test
    @DisplayName("no KafkaTracePropagation bean when no Tracer bean is present")
    void absentWithoutTracerBean() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(KafkaTracePropagation.class);
        });
    }

    @Test
    @DisplayName("no KafkaTracePropagation bean when Micrometer Tracing is not on the classpath")
    void absentWithoutTracerOnClasspath() {
        runner.withClassLoader(new FilteredClassLoader(Tracer.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(KafkaTracePropagation.class);
                });
    }

    @Test
    @DisplayName("maestro.observability.tracing.enabled=false — no KafkaTracePropagation bean")
    void absentWhenTracingDisabled() {
        runner.withBean(Tracer.class, KafkaMessagingAutoConfigurationTracingTest::tracer)
                .withBean(Propagator.class, KafkaMessagingAutoConfigurationTracingTest::propagator)
                .withPropertyValues("maestro.observability.tracing.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(KafkaTracePropagation.class);
                });
    }

    private static Tracer tracer() {
        var provider = SdkTracerProvider.builder().build();
        return new OtelTracer(provider.get("t"), new OtelCurrentTraceContext(), event -> { });
    }

    private static Propagator propagator() {
        var provider = SdkTracerProvider.builder().build();
        return new OtelPropagator(
                ContextPropagators.create(W3CTraceContextPropagator.getInstance()), provider.get("t"));
    }

    /** Binds {@link MaestroProperties} the way the starter's auto-configuration would. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class PropertiesConfiguration {
    }
}
