package io.b2mash.maestro.admin.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Unit tests for {@link AdminClientProperties}, focused on the defaults and the
 * blank-topic guard — the one piece of validation logic this holder carries.
 */
@DisplayName("AdminClientProperties")
class AdminClientPropertiesTest {

    @Test
    @DisplayName("defaults to enabled=true and the standard topic name")
    void defaults() {
        var properties = new AdminClientProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getTopic()).isEqualTo("maestro.admin.events");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("rejects a null or blank topic")
    void setTopic_rejectsBlank(String blankTopic) {
        var properties = new AdminClientProperties();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> properties.setTopic(blankTopic))
                .withMessageContaining("maestro.admin.events.topic");
    }

    @Test
    @DisplayName("accepts a valid topic override")
    void setTopic_acceptsValidValue() {
        var properties = new AdminClientProperties();

        properties.setTopic("custom-events-topic");

        assertThat(properties.getTopic()).isEqualTo("custom-events-topic");
    }

    @Test
    @DisplayName("setEnabled toggles the flag")
    void setEnabled_toggles() {
        var properties = new AdminClientProperties();

        properties.setEnabled(false);

        assertThat(properties.isEnabled()).isFalse();
    }
}
