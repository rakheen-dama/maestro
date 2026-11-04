package io.b2mash.maestro.messaging.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the derivation of {@link KafkaWorkflowMessaging#publishSignal}'s
 * acknowledgement wait from the producer's own delivery budget —
 * {@code max.block.ms + delivery.timeout.ms + 5s} — so the adapter never
 * reports a publish failed while the producer could still deliver the record
 * (which would invite a duplicate on retry). No broker: the template is never
 * sent through.
 */
@DisplayName("KafkaWorkflowMessaging derives its send timeout from the producer's delivery budget")
class KafkaWorkflowMessagingSendTimeoutTest {

    @Test
    @DisplayName("no producer settings → Kafka's defaults: 60s + 120s + 5s")
    void defaultsWhenAbsent() {
        assertThat(KafkaWorkflowMessaging.sendTimeoutFor(Map.of()))
                .isEqualTo(Duration.ofMillis(60_000 + 120_000).plusSeconds(5));
    }

    @Test
    @DisplayName("explicit values win, whether bound as numbers or as the strings Boot's properties.* produce")
    void overriddenValues() {
        assertThat(KafkaWorkflowMessaging.sendTimeoutFor(Map.of(
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "30000")))
                .isEqualTo(Duration.ofMillis(10_000 + 30_000).plusSeconds(5));
        assertThat(KafkaWorkflowMessaging.sendTimeoutFor(Map.of(
                ProducerConfig.MAX_BLOCK_MS_CONFIG, " 2000 ")))
                .as("one override, one default")
                .isEqualTo(Duration.ofMillis(2_000 + 120_000).plusSeconds(5));
    }

    @Test
    @DisplayName("an unparsable value falls back to Kafka's default rather than failing construction")
    void unparsableFallsBack() {
        assertThat(KafkaWorkflowMessaging.sendTimeoutFor(Map.of(
                ProducerConfig.MAX_BLOCK_MS_CONFIG, "soon",
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, new Object())))
                .isEqualTo(Duration.ofMillis(60_000 + 120_000).plusSeconds(5));
    }

    @Test
    @DisplayName("read from the real producer factory at construction — Maestro's auto-configured max.block.ms=10000 "
            + "with Kafka's default delivery timeout gives 135s")
    void readFromProducerFactoryAtConstruction() {
        var producerProps = new HashMap<String, Object>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:19092",
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.MAX_BLOCK_MS_CONFIG, 10_000));
        var template = new KafkaTemplate<String, byte[]>(new DefaultKafkaProducerFactory<>(producerProps));
        var consumerFactory = new DefaultKafkaConsumerFactory<String, byte[]>(Map.of(
                "bootstrap.servers", "localhost:19092",
                "key.deserializer", StringDeserializer.class,
                "value.deserializer", ByteArrayDeserializer.class));
        var config = new KafkaMessagingConfig(null, "maestro.admin.events", "g", 3,
                Duration.ofSeconds(1), 2.0, Duration.ofSeconds(30), ".DLT", true);

        var messaging = new KafkaWorkflowMessaging(template, consumerFactory, JsonMapper.builder().build(), config);

        assertThat(messaging.sendTimeout()).isEqualTo(Duration.ofMillis(10_000 + 120_000).plusSeconds(5));
        assertThat(messaging.missingTopicsFatal())
                .as("nothing verified yet → containers tolerant until applyTopicVerification says otherwise")
                .isFalse();
        messaging.applyTopicVerification(new KafkaTopicVerifier.Result(java.util.List.of("x"),
                java.util.List.of(), java.util.List.of()));
        assertThat(messaging.missingTopicsFatal()).as("fail + confirmed present").isTrue();
        messaging.applyTopicVerification(KafkaTopicVerifier.Result.inconclusive(java.util.List.of("x")));
        assertThat(messaging.missingTopicsFatal()).as("fail + inconclusive").isFalse();
    }
}
