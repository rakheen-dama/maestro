package io.b2mash.maestro.admin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pins the audit F8 fix: {@code maestro.enabled=false} is documented as the
 * master kill-switch, but before this fix {@link AdminClientAutoConfiguration}
 * was gated <em>only</em> on {@code maestro.admin.events.enabled} — the
 * top-level {@code maestro.enabled} flag had no effect on it at all (RED
 * shape: beans present, since neither of this bean's dependencies needs
 * anything {@code MaestroAutoConfiguration} would otherwise supply).
 *
 * <p>The fix adds {@code maestro.enabled} as a <em>second</em>, independently
 * required property by stacking a second {@code @ConditionalOnProperty}
 * annotation on the class — Boot 4's {@code @ConditionalOnProperty} is
 * {@code @Repeatable} (see {@link AdminClientAutoConfiguration}'s own class
 * Javadoc), so two occurrences compose as AND: "If multiple names are
 * specified, all of the properties have to pass the test for the condition
 * to match." (Boot 4.0.5 {@code ConditionalOnProperty} Javadoc) — exactly
 * what's needed here. Both gates are exercised independently below.
 */
@DisplayName("AdminClientAutoConfiguration — maestro.enabled=false (audit F8)")
class AdminClientAutoConfigurationMaestroDisabledTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AdminClientAutoConfiguration.class))
            .withBean(KafkaTemplate.class, () -> mock(KafkaTemplate.class))
            .withBean(tools.jackson.databind.ObjectMapper.class, () -> JsonMapper.builder().build());

    @Test
    @DisplayName("maestro.enabled=false disables this module even though maestro.admin.events.enabled defaults to true")
    void maestroDisabledMeansNoPublisher() {
        runner.withPropertyValues("maestro.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(AdminEventPublisher.class);
                });
    }

    @Test
    @DisplayName("maestro.enabled=true but maestro.admin.events.enabled=false still disables this module (existing gate preserved)")
    void adminEventsDisabledStillWorks() {
        runner.withPropertyValues("maestro.enabled=true", "maestro.admin.events.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(AdminEventPublisher.class);
                });
    }

    @Test
    @DisplayName("both flags true (or unset, matching defaults) — the publisher registers")
    void bothEnabledMeansPublisherPresent() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(AdminEventPublisher.class);
        });
    }
}
