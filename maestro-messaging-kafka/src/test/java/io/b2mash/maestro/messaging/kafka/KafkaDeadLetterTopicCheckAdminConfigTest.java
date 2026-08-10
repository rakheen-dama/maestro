package io.b2mash.maestro.messaging.kafka;

import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins CR-14: {@link KafkaDeadLetterTopicCheck#adminConfigFrom} must derive
 * the probe's {@code Admin} config from the <em>whole</em> consumer factory
 * configuration — not just {@code bootstrap.servers} — so security settings
 * (SASL/SSL) reach the probe on a secured cluster, while consumer-only keys
 * {@code AdminClientConfig} does not recognise are filtered out.
 *
 * <p>Plain unit test — no broker required, unlike {@link KafkaDeadLetterTopicCheckTest}.
 */
@DisplayName("KafkaDeadLetterTopicCheck.adminConfigFrom")
class KafkaDeadLetterTopicCheckAdminConfigTest {

    @Test
    @DisplayName("carries bootstrap servers and security settings over, and excludes consumer-only keys")
    void carriesSecuritySettingsAndExcludesConsumerOnlyKeys() {
        var consumerProps = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9093",
                ConsumerConfig.GROUP_ID_CONFIG, "maestro-signal-consumer",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                "security.protocol", "SASL_SSL",
                "sasl.mechanism", "PLAIN",
                "sasl.jaas.config", "org.apache.kafka.common.security.plain.PlainLoginModule required "
                        + "username=\"x\" password=\"y\";",
                "ssl.truststore.location", "/etc/kafka/truststore.jks"
        );
        var consumerFactory = new DefaultKafkaConsumerFactory<String, byte[]>(consumerProps);

        var adminConfig = KafkaDeadLetterTopicCheck.adminConfigFrom(consumerFactory);

        assertThat(adminConfig)
                .as("Admin-accepted keys, including security.*/sasl.*/ssl.*, must be carried over")
                .containsEntry(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "broker:9093")
                .containsEntry("security.protocol", "SASL_SSL")
                .containsEntry("sasl.mechanism", "PLAIN")
                .containsKey("sasl.jaas.config")
                .containsEntry("ssl.truststore.location", "/etc/kafka/truststore.jks");
        assertThat(adminConfig)
                .as("consumer-only keys AdminClientConfig does not recognise must not be forwarded")
                .doesNotContainKey(ConsumerConfig.GROUP_ID_CONFIG)
                .doesNotContainKey(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)
                .doesNotContainKey(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG)
                .doesNotContainKey(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG);
    }
}
