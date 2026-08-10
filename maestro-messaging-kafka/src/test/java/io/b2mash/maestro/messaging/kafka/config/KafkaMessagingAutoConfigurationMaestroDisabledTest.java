package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.messaging.kafka.KafkaMessagingConfig;
import io.b2mash.maestro.messaging.kafka.KafkaWorkflowMessaging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the audit F8 fix: {@code maestro.enabled=false} is documented as the
 * master kill-switch (see {@code MaestroAutoConfiguration}), but before this
 * fix only {@code MaestroAutoConfiguration} itself honoured it — this module
 * kept wiring a real {@code KafkaTemplate}, producer/consumer factories, and
 * {@link KafkaWorkflowMessaging}, and crashed trying to resolve
 * {@code MaestroProperties} (a bean only {@code MaestroAutoConfiguration}
 * registers) once the engine itself had backed off.
 *
 * <p>This test loads {@link KafkaMessagingAutoConfiguration} in isolation —
 * exactly the shape that reproduces the crash — and asserts the fixed
 * behaviour: {@code maestro.enabled=false} makes the whole class back off,
 * no beans, no crash.
 */
@DisplayName("KafkaMessagingAutoConfiguration — maestro.enabled=false (audit F8)")
class KafkaMessagingAutoConfigurationMaestroDisabledTest {

    @Test
    @DisplayName("maestro.enabled=false disables this module entirely — no beans, no crash")
    void maestroDisabledMeansNoBeansAndNoCrash() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(KafkaMessagingAutoConfiguration.class))
                .withPropertyValues("maestro.enabled=false", "maestro.service-name=x")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(WorkflowMessaging.class);
                    assertThat(ctx).doesNotHaveBean("maestroKafkaTemplate");
                    assertThat(ctx).doesNotHaveBean(KafkaMessagingConfig.class);
                });
    }
}
