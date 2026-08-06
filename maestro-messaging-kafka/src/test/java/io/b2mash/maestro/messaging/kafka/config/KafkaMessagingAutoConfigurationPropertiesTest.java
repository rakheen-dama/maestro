package io.b2mash.maestro.messaging.kafka.config;

import io.b2mash.maestro.spring.config.MaestroProperties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins Issue 23 part 1: {@code spring.kafka.producer.*} / {@code consumer.*}
 * reach Maestro's engine clients, while the engine's wire-format invariants
 * stay forced.
 *
 * <p>Boot's real {@link KafkaAutoConfiguration} is included (not stubbed) so
 * the suppression-plus-property-honouring contract — Maestro's typed
 * factories must register before Boot's type-conditioned ones back off — is
 * pinned against the genuine {@code AutoConfigurations} ordering, the same
 * lesson the tracing test in this package already applies.
 */
class KafkaMessagingAutoConfigurationPropertiesTest {

    // Real auto-configurations, Boot's INCLUDED, so the suppression-plus-
    // property-honouring contract is pinned against the genuine ordering.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    KafkaMessagingAutoConfiguration.class,
                    KafkaAutoConfiguration.class))
            .withUserConfiguration(PropertiesConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues(
                    "maestro.service-name=props-test",
                    "spring.kafka.bootstrap-servers=broker-from-props:9092");

    @Test
    void producerPropertiesReachMaestroFactory() {
        runner.withPropertyValues(
                        "spring.kafka.producer.compression-type=gzip",
                        "spring.kafka.producer.batch-size=32768",
                        "spring.kafka.producer.properties.linger.ms=7")
                .run(ctx -> {
                    var pf = (DefaultKafkaProducerFactory<?, ?>)
                            ctx.getBean("maestroKafkaProducerFactory");
                    var cfg = pf.getConfigurationProperties();
                    assertThat(cfg).containsEntry(ProducerConfig.COMPRESSION_TYPE_CONFIG, "gzip");
                    assertThat(cfg).containsEntry(ProducerConfig.LINGER_MS_CONFIG, "7");
                    assertThat(cfg.get(ProducerConfig.BATCH_SIZE_CONFIG)).hasToString("32768");
                    assertThat(cfg.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
                            .hasToString("[broker-from-props:9092]");
                });
    }

    @Test
    void engineInvariantsAlwaysWin() {
        runner.withPropertyValues(
                        // A user serializer must never corrupt engine topics
                        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                        "spring.kafka.producer.acks=1",
                        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer")
                .run(ctx -> {
                    var pf = (DefaultKafkaProducerFactory<?, ?>)
                            ctx.getBean("maestroKafkaProducerFactory");
                    assertThat(pf.getConfigurationProperties())
                            .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
                            .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class)
                            .containsEntry(ProducerConfig.ACKS_CONFIG, "all");
                    var cf = (DefaultKafkaConsumerFactory<?, ?>)
                            ctx.getBean("maestroKafkaConsumerFactory");
                    assertThat(cf.getConfigurationProperties()
                            .get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                            .isEqualTo(ByteArrayDeserializer.class);
                });
    }

    @Test
    void consumerAutoOffsetResetIsOverridableButGroupIdStaysEngineOwned() {
        runner.withPropertyValues(
                        "spring.kafka.consumer.auto-offset-reset=latest",
                        "spring.kafka.consumer.group-id=user-group")
                .run(ctx -> {
                    var cf = (DefaultKafkaConsumerFactory<?, ?>)
                            ctx.getBean("maestroKafkaConsumerFactory");
                    var cfg = cf.getConfigurationProperties();
                    assertThat(cfg)
                            .as("a user's explicit spring.kafka.consumer.auto-offset-reset must win "
                                    + "over Maestro's earliest default")
                            .containsEntry(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
                    assertThat(cfg)
                            .as("group.id stays engine-owned (maestro.messaging.consumer-group / "
                                    + "maestro-{serviceName}) — spring.kafka.consumer.group-id must not win")
                            .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "maestro-props-test");
                });
    }

    @Test
    void bootsOwnTemplateStaysSuppressed_deliberately() {
        runner.run(ctx -> {
            assertThat(ctx).hasBean("maestroKafkaTemplate");
            assertThat(ctx).doesNotHaveBean("kafkaTemplate");
            assertThat(ctx).doesNotHaveBean("kafkaProducerFactory");
        });
    }

    /** Binds {@link MaestroProperties} the way the starter's auto-configuration would. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MaestroProperties.class)
    static class PropertiesConfiguration {
    }
}
