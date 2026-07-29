package io.b2mash.maestro.admin.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminProperties} defaults and setters — the plain-old
 * getter/setter surface Spring Boot binds {@code maestro.admin.*} onto.
 */
@DisplayName("AdminProperties")
class AdminPropertiesTest {

    @Test
    @DisplayName("defaults match the values documented in application.yml")
    void defaults() {
        var properties = new AdminProperties();

        assertThat(properties.getEventsTopic()).isEqualTo("maestro.admin.events");
        assertThat(properties.getConsumerGroup()).isEqualTo("maestro-admin");
        assertThat(properties.getSignalTopicPrefix()).isEqualTo("maestro.signals.");
    }

    @Test
    @DisplayName("setters override the defaults")
    void settersOverrideDefaults() {
        var properties = new AdminProperties();

        properties.setEventsTopic("custom.events");
        properties.setConsumerGroup("custom-group");
        properties.setSignalTopicPrefix("custom.signals.");

        assertThat(properties.getEventsTopic()).isEqualTo("custom.events");
        assertThat(properties.getConsumerGroup()).isEqualTo("custom-group");
        assertThat(properties.getSignalTopicPrefix()).isEqualTo("custom.signals.");
    }
}
