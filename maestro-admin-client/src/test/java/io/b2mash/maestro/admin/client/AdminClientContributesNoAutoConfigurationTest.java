package io.b2mash.maestro.admin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins WP7 Task 5: {@code maestro-admin-client} registers no auto-configuration.
 * Its former {@code AdminEventPublisher} bean was never called; a service's
 * lifecycle events are published by the engine's {@code WorkflowMessaging}
 * ({@code maestro-messaging-kafka}).
 */
class AdminClientContributesNoAutoConfigurationTest {

    @Test
    @DisplayName("maestro-admin-client contributes no auto-configuration: lifecycle events are published by the engine's WorkflowMessaging")
    void contributesNoAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .noneMatch(name -> name.startsWith("io.b2mash.maestro.admin.client"));
    }
}
