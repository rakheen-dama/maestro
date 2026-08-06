package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.spring.config.MaestroProperties;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins Issue 23 part 2's shared observation rule
 * ({@link KafkaMessagingAutoConfiguration#observationEnabled}) for the
 * publish-side {@code maestroKafkaTemplate} bean: observation defaults on
 * exactly when a {@link io.b2mash.maestro.messaging.kafka.KafkaTracePropagation}
 * collaborator exists (i.e. tracing is actually wired), unless the user has
 * set {@code spring.kafka.template.observation-enabled} explicitly — in which
 * case that value always wins.
 *
 * <p>{@link KafkaTemplate} exposes no {@code isObservationEnabled()} getter
 * (verified via {@code javap} against spring-kafka 4.0.4 — only the setter
 * exists), so the private {@code observationEnabled} field is read via
 * {@link ReflectionTestUtils}.
 */
class KafkaTemplateObservationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("maestro.service-name=template-observation-test");

    @Test
    @DisplayName("observation defaults on when a Tracer+Propagator pair is present")
    void observationDefaultsOnWhenTracerPresent() {
        runner.withBean(Tracer.class, () -> mock(Tracer.class))
                .withBean(Propagator.class, () -> mock(Propagator.class))
                .run(ctx -> {
                    var template = (KafkaTemplate<?, ?>) ctx.getBean("maestroKafkaTemplate");
                    assertThat(isObservationEnabled(template)).isTrue();
                });
    }

    @Test
    @DisplayName("an explicit spring.kafka.template.observation-enabled=false wins even with a Tracer")
    void explicitFalseWinsEvenWithTracer() {
        runner.withBean(Tracer.class, () -> mock(Tracer.class))
                .withBean(Propagator.class, () -> mock(Propagator.class))
                .withPropertyValues("spring.kafka.template.observation-enabled=false")
                .run(ctx -> {
                    var template = (KafkaTemplate<?, ?>) ctx.getBean("maestroKafkaTemplate");
                    assertThat(isObservationEnabled(template)).isFalse();
                });
    }

    @Test
    @DisplayName("observation stays off with neither a Tracer nor the property set")
    void observationOffWithoutTracerAndWithoutProperty() {
        runner.run(ctx -> {
            var template = (KafkaTemplate<?, ?>) ctx.getBean("maestroKafkaTemplate");
            assertThat(isObservationEnabled(template)).isFalse();
        });
    }

    @Test
    @DisplayName("an explicit spring.kafka.template.observation-enabled=true wins with no Tracer present")
    void explicitTrueWinsWithoutTracer() {
        runner.withPropertyValues("spring.kafka.template.observation-enabled=true")
                .run(ctx -> {
                    var template = (KafkaTemplate<?, ?>) ctx.getBean("maestroKafkaTemplate");
                    assertThat(isObservationEnabled(template)).isTrue();
                });
    }

    private static boolean isObservationEnabled(KafkaTemplate<?, ?> template) {
        return (boolean) ReflectionTestUtils.getField(template, "observationEnabled");
    }

    /** Binds {@link MaestroProperties} the way the starter's auto-configuration would. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class PropertiesConfiguration {
    }
}
