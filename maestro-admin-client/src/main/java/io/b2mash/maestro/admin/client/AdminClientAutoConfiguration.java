package io.b2mash.maestro.admin.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Auto-configuration for the Maestro Admin Client lifecycle event publisher.
 *
 * <p>Activates when:
 * <ul>
 *   <li>{@link KafkaTemplate} is on the classpath</li>
 *   <li>{@code maestro.admin.events.enabled} is {@code true} (default)</li>
 * </ul>
 *
 * <p>Registers an {@link AdminEventPublisher} bean that wraps the application's
 * {@code KafkaTemplate<String, byte[]>} to publish {@link io.b2mash.maestro.core.spi.WorkflowLifecycleEvent}
 * records to a configurable Kafka topic.
 *
 * @see AdminClientProperties
 * @see AdminEventPublisher
 */
@AutoConfiguration
@ConditionalOnClass(KafkaTemplate.class)
// Audit F8: maestro.enabled=false is documented as the master kill-switch
// (see MaestroAutoConfiguration), but this class was previously gated only
// on maestro.admin.events.enabled — the top-level flag had no effect on it
// at all. Both properties are now required (Boot 4's @ConditionalOnProperty
// is @Repeatable — see OnPropertyCondition — so stacking two occurrences
// composes as AND: both must independently match). See
// AdminClientAutoConfigurationMaestroDisabledTest.
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "maestro.admin.events", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AdminClientProperties.class)
public class AdminClientAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AdminClientAutoConfiguration.class);

    /**
     * The shared default for both the canonical
     * {@code maestro.messaging.topics.admin-events} property and the
     * deprecated {@code maestro.admin.events.topic} alias. Must match
     * {@code AdminClientProperties.topic}'s default and
     * {@code MaestroProperties.TopicsProperties.defaults().adminEvents()}
     * in the starter.
     */
    private static final String DEFAULT_TOPIC = "maestro.admin.events";

    /**
     * Creates the {@link AdminEventPublisher} bean if one does not already exist.
     *
     * @param kafkaTemplate the Kafka template for publishing serialized events
     * @param objectMapper  Jackson 3 ObjectMapper for event serialization
     * @param properties    admin client configuration properties
     * @param environment   used to read the canonical
     *                      {@code maestro.messaging.topics.admin-events} property;
     *                      see {@link #resolveTopic}
     * @return a configured {@link AdminEventPublisher}
     */
    @Bean
    @ConditionalOnMissingBean
    public AdminEventPublisher adminEventPublisher(
            KafkaTemplate<String, byte[]> kafkaTemplate,
            ObjectMapper objectMapper,
            AdminClientProperties properties,
            Environment environment
    ) {
        return new AdminEventPublisher(kafkaTemplate, objectMapper, resolveTopic(environment, properties));
    }

    /**
     * Resolves the admin-events topic, honouring the deprecated
     * {@code maestro.admin.events.topic} alias.
     *
     * <p>Twin of {@code KafkaMessagingAutoConfiguration.resolveAdminEventsTopic}
     * in {@code maestro-messaging-kafka} — that method resolves the same
     * precedence for the starter, which binds {@code MaestroProperties} via
     * relaxed binding. This module must not depend on the starter (see
     * module Javadoc / CLAUDE.md), so it re-derives the same result directly
     * from {@link Environment} instead of sharing code. Keep the two in sync
     * if the precedence rule ever changes.
     *
     * <p>{@code maestro.messaging.topics.admin-events} is the canonical
     * property. {@code maestro.admin.events.topic} (bound onto
     * {@link AdminClientProperties#getTopic()}) is kept as an alias for
     * deployments that only ever touched the admin block. Both properties
     * carry the same default ({@value #DEFAULT_TOPIC}), so a value that
     * differs from the default is treated as having been explicitly
     * configured:
     * <ul>
     *   <li>Neither customized — the shared default.</li>
     *   <li>Only one customized — that value is used.</li>
     *   <li>Both customized to the same value — that value, no conflict.</li>
     *   <li>Both customized to different values — the messaging property
     *       wins and a WARN is logged, so the conflict is visible rather
     *       than silently dropping the alias.</li>
     * </ul>
     *
     * @param env        the environment to read {@code maestro.messaging.topics.admin-events} from
     * @param properties the bound admin-client configuration
     * @return the topic to publish admin lifecycle events on
     * @throws IllegalArgumentException if {@code maestro.messaging.topics.admin-events}
     *                                  is explicitly set to a blank value —
     *                                  {@link Environment#getProperty(String, String)}
     *                                  only falls back to the default when the
     *                                  property is <em>absent</em>, not when it is
     *                                  present but blank, so an unvalidated blank
     *                                  value would otherwise flow through to
     *                                  {@link AdminEventPublisher} and fail later,
     *                                  opaquely, at first publish. Mirrors
     *                                  {@link AdminClientProperties#setTopic} for
     *                                  the deprecated alias.
     */
    static String resolveTopic(Environment env, AdminClientProperties properties) {
        var messagingTopic = env.getProperty("maestro.messaging.topics.admin-events", DEFAULT_TOPIC);
        if (messagingTopic.isBlank()) {
            throw new IllegalArgumentException("maestro.messaging.topics.admin-events must not be blank");
        }
        var aliasTopic = properties.getTopic();
        var messagingCustomized = !messagingTopic.equals(DEFAULT_TOPIC);
        var aliasCustomized = !aliasTopic.equals(DEFAULT_TOPIC);
        if (aliasCustomized && messagingCustomized && !aliasTopic.equals(messagingTopic)) {
            logger.warn("Both maestro.messaging.topics.admin-events ('{}') and the deprecated "
                            + "maestro.admin.events.topic ('{}') are configured — "
                            + "maestro.messaging.topics.admin-events wins. Remove the deprecated property.",
                    messagingTopic, aliasTopic);
            return messagingTopic;
        }
        return aliasCustomized && !messagingCustomized ? aliasTopic : messagingTopic;
    }
}
