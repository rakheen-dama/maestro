package io.b2mash.maestro.admin;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Creates test topics and returns only once the broker <em>describes</em>
 * them. {@code createTopics(...).all().get()} completes when the controller has
 * created a topic, which can be before the broker's metadata cache serves it: a
 * {@code describeTopics} right after — the startup topic verifier's probe, or
 * Spring Kafka's {@code missingTopicsFatal} check — can still answer
 * {@link UnknownTopicOrPartitionException}, which Maestro rightly reads as
 * "confirmed missing" (PR #78 CI run 44613617971:
 * {@code KafkaTopicVerificationStartupTest} failed with both just-created
 * topics reported missing).
 *
 * <p>No sleep: the wait is a bounded series of describe calls, each a broker
 * round trip, until every topic is described.
 *
 * <p>Thread-safe (stateless).
 */
public final class KafkaTopicsForTests {

    /** The longest to wait for the broker to describe a topic it has created. */
    static final Duration VISIBLE_WITHIN = Duration.ofSeconds(30);

    private KafkaTopicsForTests() {
    }

    /**
     * Creates {@code names} (one partition, replication factor one) and waits
     * until the broker describes every one of them.
     *
     * @param admin an admin client on the test broker
     * @param names the topics to create
     * @throws ExecutionException   if creation fails, or a topic is still unknown after {@link #VISIBLE_WITHIN}
     * @throws InterruptedException if interrupted while waiting
     */
    public static void create(Admin admin, Collection<String> names) throws ExecutionException, InterruptedException {
        admin.createTopics(names.stream().map(name -> new NewTopic(name, 1, (short) 1)).toList()).all().get();
        awaitDescribed(admin, names);
    }

    /**
     * Waits until the broker describes every topic in {@code names}.
     *
     * @param admin an admin client on the test broker
     * @param names the topics
     * @throws ExecutionException   if describing fails for another reason, or a topic is still unknown
     *                              after {@link #VISIBLE_WITHIN}
     * @throws InterruptedException if interrupted while waiting
     */
    public static void awaitDescribed(Admin admin, Collection<String> names)
            throws ExecutionException, InterruptedException {
        var deadline = System.nanoTime() + VISIBLE_WITHIN.toNanos();
        var topics = List.copyOf(names);
        while (true) {
            // Each request and each wait takes only what is left of the budget,
            // so a broker that stops answering cannot stretch the wait past
            // VISIBLE_WITHIN by the client's 60 s default API timeout.
            var leftMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            try {
                admin.describeTopics(topics, new DescribeTopicsOptions().timeoutMs((int) leftMs))
                        .allTopicNames().get(leftMs, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException e) {
                throw new ExecutionException("topics " + topics + " not described within " + VISIBLE_WITHIN, e);
            } catch (ExecutionException e) {
                if (!(e.getCause() instanceof UnknownTopicOrPartitionException) || System.nanoTime() > deadline) {
                    throw e;
                }
            }
        }
    }
}
