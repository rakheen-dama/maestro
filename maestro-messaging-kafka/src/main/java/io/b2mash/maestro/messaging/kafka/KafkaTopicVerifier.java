package io.b2mash.maestro.messaging.kafka;

import io.b2mash.maestro.spring.config.MaestroProperties.TopicsProperties.TopicVerification;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeTopicsOptions;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Startup verification that every Kafka topic a service needs actually exists
 * — the enforcement behind "Maestro never auto-creates topics".
 *
 * <h2>Why this exists</h2>
 * <p>Maestro never creates topics; the operator pre-declares them. Before this
 * class, a forgotten topic surfaced late and indirectly: the producer blocked
 * for {@code max.block.ms} on <em>every</em> publish to a missing admin-events
 * topic (the never-explained six-of-six loan E2E failures), a consumer on a
 * missing signal topic polled nothing forever, and a missing {@code .DLT}
 * only showed up once a handler's attempt budget was first exhausted, as a
 * recoverer that could not publish. Its predecessor, a warn-only
 * dead-letter probe, ran per subscription and could never fail anything.
 *
 * <h2>What it checks</h2>
 * <p>{@link #requiredTopics} composes the set once, from the resolved
 * {@link KafkaMessagingConfig}: the engine's signal topic (fixed or
 * {@code maestro.signals.<service>}) plus its {@code .DLT} companion when
 * redelivery is enabled; the admin-events topic when lifecycle publishing is
 * enabled; and every {@code @MaestroSignalListener} topic plus its
 * {@code .DLT}. Plain {@code @KafkaListener} topics are not Maestro's and are
 * out of scope. {@link #verify} asks the broker about all of them in one
 * {@code describeTopics} call bounded by {@value #PROBE_TIMEOUT_SECONDS}
 * seconds.
 *
 * <h2>Missing versus inconclusive</h2>
 * <p>A topic is <b>missing</b> only when the broker says so
 * ({@link UnknownTopicOrPartitionException}). Anything else — a timeout, an
 * unreachable or unauthenticated broker, an interrupted probe — is
 * <b>inconclusive</b>: logged at WARN and never fatal, because a broker blip
 * at deploy time must not become a fleet-wide restart loop. Under
 * {@link TopicVerification#FAIL} the consumer containers are started with
 * {@code missingTopicsFatal}, which still guards consumption of a topic the
 * probe could not see.
 *
 * <h2>Where it runs</h2>
 * <p>Once, from {@code MaestroSignalListenerBeanPostProcessor.afterSingletonsInstantiated}
 * — after every singleton exists (so all listener registrations are known)
 * and before any container starts or {@code StartupRecoveryRunner} resumes a
 * workflow. A failure there fails context refresh with one
 * {@link IllegalStateException} that lists every missing topic and a ready
 * {@code kafka-topics.sh --create} line for each.
 *
 * <p><b>Thread safety:</b> This class is a stateless utility; the caller owns
 * the {@link Admin} client's lifecycle where one is passed in.
 */
@NullMarked
public final class KafkaTopicVerifier {

    private static final Logger logger = LoggerFactory.getLogger(KafkaTopicVerifier.class);

    /** Bound on the whole probe, in seconds, so a slow or unreachable broker cannot stall startup. */
    static final int PROBE_TIMEOUT_SECONDS = 5;

    /** Bound on the whole probe, so a slow or unreachable broker cannot stall startup. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(PROBE_TIMEOUT_SECONDS);

    /** The property this verifier enforces; named in every message it emits. */
    public static final String PROPERTY = "maestro.messaging.topics.verify-on-startup";

    private KafkaTopicVerifier() {
    }

    /**
     * The outcome of one {@link #verify} pass over a set of required topics.
     *
     * @param present      topics the broker confirmed exist
     * @param missing      topics the broker confirmed do <em>not</em> exist
     * @param inconclusive topics whose existence could not be determined (timeout,
     *                     unreachable broker, authorization failure, ...)
     */
    public record Result(List<String> present, List<String> missing, List<String> inconclusive) {

        /** @return a result in which every topic is inconclusive — the probe itself could not run */
        public static Result inconclusive(Collection<String> topics) {
            return new Result(List.of(), List.of(), List.copyOf(topics));
        }

        /** @return {@code true} when every required topic was confirmed present */
        public boolean allPresent() {
            return missing.isEmpty() && inconclusive.isEmpty();
        }
    }

    /**
     * Composes the topics a service needs before it can run, in a stable
     * order and without duplicates.
     *
     * @param config             the resolved messaging configuration
     * @param serviceName        {@code maestro.service-name}; when {@code null} or
     *                           blank and no fixed signal topic is configured, the
     *                           signal topic cannot be named and is left out
     * @param adminEventsEnabled {@code maestro.admin.events.enabled} — when
     *                           {@code false} nothing publishes lifecycle events,
     *                           so the admin-events topic is not required
     * @param listenerTopics     every {@code @MaestroSignalListener(topic = ...)}
     *                           this service declares
     * @return the required topics: the engine signal topic (+ {@code .DLT} when
     *         redelivery is on), the admin-events topic when enabled, and each
     *         listener topic (+ {@code .DLT} when redelivery is on)
     */
    public static List<String> requiredTopics(
            KafkaMessagingConfig config,
            @Nullable String serviceName,
            boolean adminEventsEnabled,
            Collection<String> listenerTopics
    ) {
        var required = new LinkedHashSet<String>();
        if (config.signalsTopic() != null || (serviceName != null && !serviceName.isBlank())) {
            var signalTopic = config.signalTopicFor(serviceName == null ? "" : serviceName);
            required.add(signalTopic);
            if (config.redeliveryEnabled()) {
                required.add(config.deadLetterTopicFor(signalTopic));
            }
        }
        if (adminEventsEnabled) {
            required.add(config.adminEventsTopic());
        }
        for (var topic : listenerTopics) {
            required.add(topic);
            if (config.redeliveryEnabled()) {
                required.add(config.deadLetterTopicFor(topic));
            }
        }
        return List.copyOf(required);
    }

    /**
     * The startup entry point: builds a throwaway {@link Admin} client from the
     * consumer factory's configuration (see {@link #adminConfigFrom}), verifies
     * {@code required}, and applies {@code config.verifyOnStartup()}.
     *
     * @param config          the resolved messaging configuration; its
     *                        {@link KafkaMessagingConfig#verifyOnStartup()} decides
     *                        what a missing topic means
     * @param consumerFactory the factory whose configuration — bootstrap servers,
     *                        security settings — the probe's client is derived from
     * @param required        the topics to verify, from {@link #requiredTopics}
     * @throws IllegalStateException under {@link TopicVerification#FAIL} when at
     *                               least one topic is confirmed missing; the message
     *                               names every missing topic with a
     *                               {@code kafka-topics.sh --create} line each
     */
    public static void verifyOnStartup(
            KafkaMessagingConfig config,
            ConsumerFactory<?, ?> consumerFactory,
            Collection<String> required
    ) {
        var mode = config.verifyOnStartup();
        if (mode == TopicVerification.OFF) {
            logger.debug("Kafka topic verification is off ({}=off); not checking {}", PROPERTY, required);
            return;
        }
        if (required.isEmpty()) {
            logger.debug("Kafka topic verification: nothing to verify");
            return;
        }
        var adminConfig = adminConfigFrom(consumerFactory);
        var bootstrapServers = bootstrapServersOf(adminConfig);
        Result result;
        try (var admin = Admin.create(adminConfig)) {
            result = verify(admin, required);
        } catch (Exception e) {
            // Admin.create itself failed (bad security config, say) — the probe
            // could not run, which is inconclusive, never "missing".
            logger.debug("Kafka topic verification could not create an Admin client: {}", e.getMessage(), e);
            result = Result.inconclusive(required);
        }
        enforce(mode, result, bootstrapServers);
    }

    /**
     * Applies the policy to a verification result: {@code FAIL} throws for
     * confirmed-missing topics, {@code WARN} logs them; inconclusive topics are
     * logged at WARN under either; a clean result is logged at INFO.
     *
     * <p>Package-visible so the policy can be unit-tested without a broker.
     *
     * @param mode             the configured policy; must not be {@link TopicVerification#OFF}
     * @param result           the verification outcome
     * @param bootstrapServers the broker address, for the suggested CLI lines
     * @throws IllegalStateException under {@code FAIL} with at least one missing topic
     */
    static void enforce(TopicVerification mode, Result result, String bootstrapServers) {
        if (!result.missing().isEmpty()) {
            var message = missingTopicsMessage(result, bootstrapServers);
            if (mode == TopicVerification.FAIL) {
                throw new IllegalStateException(message);
            }
            logger.warn("{}", message);
        }
        if (!result.inconclusive().isEmpty()) {
            logger.warn("Could not verify that {} Kafka topic(s) exist on {} within {}s — {} — the broker was "
                            + "unreachable, slow, or refused the request. Starting anyway ({}={}); a consumer on a "
                            + "topic that turns out to be missing {}.",
                    result.inconclusive().size(), bootstrapServers, PROBE_TIMEOUT_SECONDS, result.inconclusive(),
                    PROPERTY, mode.name().toLowerCase(),
                    mode == TopicVerification.FAIL
                            ? "fails at container start (missingTopicsFatal)"
                            : "polls nothing until the topic is created");
        }
        if (result.allPresent()) {
            logger.info("Verified {} Kafka topic(s) exist on {}: {}",
                    result.present().size(), bootstrapServers, result.present());
        }
    }

    /**
     * Asks the broker, in one bounded {@code describeTopics} call, which of the
     * required topics exist.
     *
     * @param admin    the admin client to probe with; the caller owns closing it
     * @param required the topics to verify
     * @return present / missing / inconclusive, each in {@code required}'s order;
     *         never throws — a probe that cannot run reports every topic inconclusive
     */
    public static Result verify(Admin admin, Collection<String> required) {
        if (required.isEmpty()) {
            return new Result(List.of(), List.of(), List.of());
        }
        var topics = new ArrayList<>(new LinkedHashSet<>(required));
        Map<String, KafkaFuture<org.apache.kafka.clients.admin.TopicDescription>> futures;
        try {
            var options = new DescribeTopicsOptions().timeoutMs((int) PROBE_TIMEOUT.toMillis());
            futures = admin.describeTopics(topics, options).topicNameValues();
        } catch (Exception e) {
            logger.debug("Kafka topic verification probe could not run: {}", e.getMessage(), e);
            return Result.inconclusive(topics);
        }

        var present = new ArrayList<String>();
        var missing = new ArrayList<String>();
        var inconclusive = new ArrayList<String>();
        for (var topic : topics) {
            var future = futures.get(topic);
            if (future == null) {
                inconclusive.add(topic);
                continue;
            }
            switch (outcome(future, topic)) {
                case PRESENT -> present.add(topic);
                case MISSING -> missing.add(topic);
                case INCONCLUSIVE -> inconclusive.add(topic);
            }
        }
        return new Result(List.copyOf(present), List.copyOf(missing), List.copyOf(inconclusive));
    }

    /**
     * Derives an {@link Admin} client config from a consumer factory's full
     * configuration, keeping only the keys {@link AdminClientConfig} accepts.
     *
     * <p>Copying only {@code bootstrap.servers} would silently drop every
     * connection-security setting — {@code security.protocol}, {@code sasl.*},
     * {@code ssl.*} — so on a SASL/SSL-secured cluster {@code Admin.create}
     * would fail every time and verification would be permanently inconclusive.
     * Filtering the consumer factory's <em>entire</em> configuration through
     * {@link AdminClientConfig#configNames()} carries those settings over
     * automatically, while still excluding consumer-only keys
     * {@code AdminClientConfig} does not recognise — {@code group.id}, the key/
     * value deserializers, {@code auto.offset.reset}, etc. — which
     * {@link Admin#create} would otherwise reject or ignore.
     *
     * @param consumerFactory the factory whose configuration to filter
     * @return the Admin-accepted subset of the consumer factory's configuration
     */
    static Map<String, Object> adminConfigFrom(ConsumerFactory<?, ?> consumerFactory) {
        var adminConfigNames = AdminClientConfig.configNames();
        var props = new HashMap<String, Object>();
        for (var entry : consumerFactory.getConfigurationProperties().entrySet()) {
            if (adminConfigNames.contains(entry.getKey())) {
                props.put(entry.getKey(), entry.getValue());
            }
        }
        return props;
    }

    /**
     * The startup-failure message: every missing topic, each with a ready
     * {@code kafka-topics.sh --create} line against the configured brokers.
     *
     * @param result           a result with at least one missing topic
     * @param bootstrapServers the broker address to put on the CLI lines
     * @return the message, multi-line
     */
    static String missingTopicsMessage(Result result, String bootstrapServers) {
        var total = result.present().size() + result.missing().size() + result.inconclusive().size();
        var sb = new StringBuilder();
        sb.append(result.missing().size()).append(" of ").append(total)
                .append(" Kafka topic(s) Maestro needs do not exist on ").append(bootstrapServers)
                .append(": ").append(result.missing()).append(". Maestro never creates topics — pre-create them")
                .append(" (or set ").append(PROPERTY).append("=warn to start without them):");
        for (var topic : result.missing()) {
            sb.append(System.lineSeparator())
                    .append("  kafka-topics.sh --bootstrap-server ").append(bootstrapServers)
                    .append(" --create --topic ").append(topic)
                    .append(" --partitions 3 --replication-factor 3");
        }
        sb.append(System.lineSeparator())
                .append("  (adjust --partitions/--replication-factor to the cluster; a single-broker ")
                .append("development cluster needs --replication-factor 1)");
        return sb.toString();
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private enum Outcome { PRESENT, MISSING, INCONCLUSIVE }

    private static Outcome outcome(KafkaFuture<?> future, String topic) {
        try {
            future.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return Outcome.PRESENT;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                return Outcome.MISSING;
            }
            logger.debug("Could not determine whether Kafka topic '{}' exists: {}", topic, e.getMessage(), e);
            return Outcome.INCONCLUSIVE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted while verifying Kafka topic '{}'", topic, e);
            return Outcome.INCONCLUSIVE;
        } catch (TimeoutException e) {
            logger.debug("Timed out verifying Kafka topic '{}'", topic, e);
            return Outcome.INCONCLUSIVE;
        }
    }

    private static String bootstrapServersOf(Map<String, Object> adminConfig) {
        var value = adminConfig.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
        if (value instanceof Collection<?> servers) {
            return servers.stream().map(String::valueOf).collect(Collectors.joining(","));
        }
        return value == null ? "<spring.kafka.bootstrap-servers>" : String.valueOf(value);
    }
}
