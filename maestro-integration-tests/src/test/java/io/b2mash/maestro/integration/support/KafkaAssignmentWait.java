package io.b2mash.maestro.integration.support;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;

/**
 * Waits for a consumer group to be assigned a topic's partitions — the
 * precondition a test that publishes right after subscribing depends on, read
 * from the broker itself rather than assumed after a fixed sleep (Issue 92).
 *
 * <p>The consumers under test start at {@code earliest} on fresh groups, so a
 * record published before the assignment is still read; the wait states the
 * precondition explicitly, so the tests keep working if that ever changes.
 *
 * <h2>Thread Safety</h2>
 * <p>Stateless and static; safe to call from any thread.
 */
public final class KafkaAssignmentWait {

    /** How long a fresh consumer may take to join its group and be assigned. */
    public static final Duration ASSIGNMENT_BOUND = Duration.ofSeconds(30);

    private KafkaAssignmentWait() {
    }

    /**
     * Waits until a member of {@code groupId} holds a partition of {@code topic}.
     *
     * @param bootstrapServers the broker to ask
     * @param groupId          the consumer group of the subscription under test
     * @param topic            the topic it subscribes to
     */
    public static void awaitAssigned(String bootstrapServers, String groupId, String topic) {
        try (var admin = Admin.create(Map.<String, Object>of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            await().atMost(ASSIGNMENT_BOUND)
                    .pollInterval(Duration.ofMillis(100))
                    .ignoreExceptions()
                    .until(() -> holdsTopic(
                            admin.describeConsumerGroups(List.of(groupId)).all().get().get(groupId), topic));
        }
    }

    private static boolean holdsTopic(ConsumerGroupDescription group, String topic) {
        return group != null && group.members().stream()
                .flatMap(m -> m.assignment().topicPartitions().stream())
                .anyMatch(tp -> tp.topic().equals(topic));
    }
}
