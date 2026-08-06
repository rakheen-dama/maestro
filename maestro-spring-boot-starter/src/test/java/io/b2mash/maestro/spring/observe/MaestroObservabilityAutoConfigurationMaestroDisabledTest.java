package io.b2mash.maestro.spring.observe;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Confirms the audit F8 requirement for {@link MaestroObservabilityAutoConfiguration}:
 * unlike its seven sibling classes, this one already carried
 * {@code @ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)}
 * (see the class Javadoc's "Activates when" clause, present since Task 4).
 * No fix was needed here — this test exists purely to close the audit's
 * coverage gap and guard against regression, matching the dedicated
 * {@code <Module>MaestroDisabledTest} pattern used for the other seven
 * classes.
 */
@DisplayName("MaestroObservabilityAutoConfiguration — maestro.enabled=false (audit F8, already correct)")
class MaestroObservabilityAutoConfigurationMaestroDisabledTest {

    @Test
    @DisplayName("maestro.enabled=false disables this module entirely — no observer/gauge beans, no crash")
    void maestroDisabledMeansNoBeansAndNoCrash() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MaestroObservabilityAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("maestro.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(MicrometerEngineObserver.class);
                    assertThat(ctx).doesNotHaveBean(MaestroEngineGauges.class);
                });
    }
}
