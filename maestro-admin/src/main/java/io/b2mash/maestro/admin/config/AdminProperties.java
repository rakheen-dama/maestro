package io.b2mash.maestro.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Maestro Admin dashboard.
 *
 * <p>All properties live under the {@code maestro.admin.*} namespace. Example:
 * <pre>{@code
 * maestro:
 *   admin:
 *     events-topic: maestro.admin.events
 *     consumer-group: maestro-admin
 *     signal-topic-prefix: maestro.signals.
 * }</pre>
 *
 * @see AdminConfiguration
 */
@ConfigurationProperties("maestro.admin")
public class AdminProperties {

    /**
     * Kafka topic from which workflow lifecycle events are consumed.
     */
    private String eventsTopic = "maestro.admin.events";

    /**
     * Kafka consumer group ID for the admin event consumer.
     */
    private String consumerGroup = "maestro-admin";

    /**
     * Prefix for per-service signal topics. Used when the admin dashboard
     * needs to send signals back to workflow services.
     */
    private String signalTopicPrefix = "maestro.signals.";

    // ── Getters and setters ─────────────────────────────────────────

    public String getEventsTopic() {
        return eventsTopic;
    }

    public void setEventsTopic(String eventsTopic) {
        this.eventsTopic = eventsTopic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }

    public String getSignalTopicPrefix() {
        return signalTopicPrefix;
    }

    public void setSignalTopicPrefix(String signalTopicPrefix) {
        this.signalTopicPrefix = signalTopicPrefix;
    }
}
