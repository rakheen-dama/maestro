package io.b2mash.maestro.admin.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Maestro Admin Client lifecycle event publisher.
 *
 * <p>All properties live under the {@code maestro.admin.events} namespace. Example:
 * <pre>{@code
 * maestro:
 *   admin:
 *     events:
 *       enabled: true
 *       topic: maestro.admin.events
 * }</pre>
 *
 * @see AdminClientAutoConfiguration
 */
@ConfigurationProperties("maestro.admin.events")
public class AdminClientProperties {

    /**
     * Whether lifecycle event publishing is enabled.
     */
    private boolean enabled = true;

    /**
     * Kafka topic for admin dashboard lifecycle events.
     */
    private String topic = "maestro.admin.events";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("maestro.admin.events.topic must not be blank");
        }
        this.topic = topic;
    }
}
