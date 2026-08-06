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
 * }</pre>
 *
 * <p>The Kafka topic itself is normally set via the canonical
 * {@code maestro.messaging.topics.admin-events} property (see
 * {@link #getTopic()}), not under this block.
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
     * Deprecated alias for the Kafka topic used to publish admin dashboard
     * lifecycle events.
     *
     * <p>The canonical property is {@code maestro.messaging.topics.admin-events}.
     * This alias ({@code maestro.admin.events.topic}) is only honoured when
     * the canonical property is left at its default; if both are set to
     * different values, the canonical property wins and a WARN is logged.
     * See {@link AdminClientAutoConfiguration#resolveTopic}.
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
