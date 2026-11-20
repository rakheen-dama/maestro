package io.b2mash.maestro.integration.upgrade.rolling;

import io.b2mash.maestro.integration.support.KafkaTopicsForTests;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jspecify.annotations.Nullable;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The one Kafka broker of the rolling-upgrade test's Kafka scenarios, started
 * on first use: a single KRaft broker that never auto-creates a topic (the
 * production posture, CLAUDE.md), with every topic a rolling node needs
 * pre-created, one partition each.
 *
 * <p>Each scenario takes a consumer group of its own ({@link #freshGroup()}),
 * whose offsets are committed at the end of every topic before any node
 * joins it: a node then reads exactly the records the scenario publishes,
 * and none of an earlier scenario's.
 *
 * <p><b>Thread safety:</b> the producer and admin clients are thread-safe;
 * one test thread drives a scenario.
 */
final class RollingKafka {

    /** The engine's signal topic for {@code maestro.service-name=rolling}. */
    static final String SIGNALS_TOPIC = "maestro.signals.rolling";

    /** The rolling node's {@code @MaestroSignalListener} topic. */
    static final String EVENTS_TOPIC = "rolling.events";

    /** Every topic a rolling node with Kafka verifies at startup: both, with their dead-letter companions. */
    static final List<String> TOPICS = List.of(SIGNALS_TOPIC, SIGNALS_TOPIC + ".DLT", EVENTS_TOPIC,
            EVENTS_TOPIC + ".DLT");

    private static final String HEADER = "maestro-message-id";

    private static @Nullable RollingKafka instance;

    private final KafkaContainer container;
    private final KafkaProducer<String, byte[]> producer;

    private RollingKafka(KafkaContainer container) {
        this.container = container;
        this.producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, container.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class,
                ProducerConfig.ACKS_CONFIG, "all"));
    }

    /** @return the broker, started and with every topic created, on the first call */
    @SuppressWarnings("resource")
    static synchronized RollingKafka get() {
        if (instance == null) {
            var container = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
                    .withKraft()
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");
            container.start();
            var kafka = new RollingKafka(container);
            try (var admin = kafka.admin()) {
                KafkaTopicsForTests.create(admin, TOPICS);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot create the rolling topics " + TOPICS, e);
            }
            instance = kafka;
        }
        return instance;
    }

    /** @return the bootstrap servers a node connects to */
    String bootstrapServers() {
        return container.getBootstrapServers();
    }

    /**
     * A consumer group no node has joined yet, its offsets committed at the
     * current end of every topic.
     *
     * @return the group id
     */
    String freshGroup() {
        var group = "maestro-rolling-" + UUID.randomUUID().toString().substring(0, 8);
        try (var admin = admin()) {
            var partitions = TOPICS.stream().map(t -> new TopicPartition(t, 0)).toList();
            var ends = admin.listOffsets(partitions.stream()
                            .collect(Collectors.toMap(p -> p, p -> OffsetSpec.latest())))
                    .all().get(30, TimeUnit.SECONDS);
            var offsets = new LinkedHashMap<TopicPartition, OffsetAndMetadata>();
            ends.forEach((partition, info) -> offsets.put(partition, new OffsetAndMetadata(info.offset())));
            admin.alterConsumerGroupOffsets(group, offsets).all().get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot prepare consumer group " + group, e);
        }
        return group;
    }

    /**
     * Publishes one domain record to {@value #EVENTS_TOPIC}, waiting for the
     * broker's acknowledgement.
     *
     * @param workflowId the workflow the record signals
     * @param payload    the signal's payload
     * @param messageId  the {@code maestro-message-id} header, or {@code null} for none
     * @return the record's offset
     */
    long publish(String workflowId, String payload, @Nullable String messageId) {
        var json = "{\"workflowId\":\"" + workflowId + "\",\"payload\":\"" + payload + "\"}";
        var record = new ProducerRecord<>(EVENTS_TOPIC, 0, workflowId, json.getBytes(StandardCharsets.UTF_8));
        if (messageId != null) {
            record.headers().add(HEADER, messageId.getBytes(StandardCharsets.UTF_8));
        }
        try {
            return producer.send(record).get(30, TimeUnit.SECONDS).offset();
        } catch (ExecutionException e) {
            throw new IllegalStateException("Cannot publish to " + EVENTS_TOPIC, e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot publish to " + EVENTS_TOPIC, e);
        }
    }

    /** @return {@value #EVENTS_TOPIC}'s KIP-516 topic id, as {@code kafka:} message ids carry it */
    String eventsTopicId() {
        try (var admin = admin()) {
            return admin.describeTopics(List.of(EVENTS_TOPIC)).allTopicNames().get(30, TimeUnit.SECONDS)
                    .get(EVENTS_TOPIC).topicId().toString();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot describe " + EVENTS_TOPIC, e);
        }
    }

    /**
     * @return the end offset of {@value #EVENTS_TOPIC}'s dead-letter topic: a
     *         duplicate the listener failed on, instead of suppressing it,
     *         lands there once its retries are spent
     */
    long eventsDeadLetterEnd() {
        var partition = new TopicPartition(EVENTS_TOPIC + ".DLT", 0);
        try (var admin = admin()) {
            return admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get(30, TimeUnit.SECONDS)
                    .get(partition).offset();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the end of " + partition, e);
        }
    }

    /**
     * @param group a consumer group
     * @return the group's committed offset on {@value #EVENTS_TOPIC}, or {@code -1} for none
     */
    long committedEventsOffset(String group) {
        try (var admin = admin()) {
            var committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                    .get(30, TimeUnit.SECONDS).get(new TopicPartition(EVENTS_TOPIC, 0));
            return committed == null ? -1 : committed.offset();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read the offsets of " + group, e);
        }
    }

    /**
     * Moves the group's committed offset on {@value #EVENTS_TOPIC} back to
     * {@code offset}, so its next member reads that record again: the
     * redelivery a consumer sees after a rebalance when the previous owner
     * stored the signal but its offset commit was lost. The group must have no
     * member.
     *
     * @param group  the consumer group
     * @param offset the offset to read from next
     */
    void rewindEvents(String group, long offset) {
        // The coordinator refuses while it still counts the member that just
        // left; that clears within the session timeout, so retry until then.
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try (var admin = admin()) {
            while (true) {
                try {
                    admin.alterConsumerGroupOffsets(group,
                                    Map.of(new TopicPartition(EVENTS_TOPIC, 0), new OffsetAndMetadata(offset)))
                            .all().get(30, TimeUnit.SECONDS);
                    return;
                } catch (ExecutionException e) {
                    if (System.nanoTime() > deadline) {
                        throw new IllegalStateException("Cannot rewind " + group + " to " + offset, e.getCause());
                    }
                    Thread.sleep(250);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted rewinding " + group, e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot rewind " + group + " to " + offset, e);
        }
    }

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, container.getBootstrapServers()));
    }
}
