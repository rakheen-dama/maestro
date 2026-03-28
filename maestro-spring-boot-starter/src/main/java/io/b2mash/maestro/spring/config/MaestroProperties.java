package io.b2mash.maestro.spring.config;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * Configuration properties for the Maestro durable workflow engine.
 *
 * <p>All properties live under the {@code maestro.*} namespace. Example:
 * <pre>{@code
 * maestro:
 *   service-name: order-service
 *   timer:
 *     poll-interval: 5s
 *     batch-size: 100
 *   retry:
 *     default-max-attempts: 3
 * }</pre>
 *
 * @see MaestroAutoConfiguration
 */
@ConfigurationProperties("maestro")
public class MaestroProperties {

    /**
     * Whether Maestro auto-configuration is enabled.
     */
    private boolean enabled = true;

    /**
     * Logical name of the owning service. Used for Kafka consumer groups,
     * lock key prefixes, and lifecycle event attribution.
     * <b>Required</b> — auto-configuration will fail if not set.
     */
    private @Nullable String serviceName;

    private StoreProperties store = new StoreProperties();

    private MessagingProperties messaging = new MessagingProperties();

    private LockProperties lock = new LockProperties();

    private WorkerProperties worker = new WorkerProperties();

    private TimerProperties timer = new TimerProperties();

    private RetryProperties retry = new RetryProperties();

    private AdminProperties admin = new AdminProperties();

    // ── Getters and setters ─────────────────────────────────────────

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public @Nullable String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public StoreProperties getStore() {
        return store;
    }

    public void setStore(StoreProperties store) {
        this.store = store;
    }

    public MessagingProperties getMessaging() {
        return messaging;
    }

    public void setMessaging(MessagingProperties messaging) {
        this.messaging = messaging;
    }

    public LockProperties getLock() {
        return lock;
    }

    public void setLock(LockProperties lock) {
        this.lock = lock;
    }

    public WorkerProperties getWorker() {
        return worker;
    }

    public void setWorker(WorkerProperties worker) {
        this.worker = worker;
    }

    public TimerProperties getTimer() {
        return timer;
    }

    public void setTimer(TimerProperties timer) {
        this.timer = timer;
    }

    public RetryProperties getRetry() {
        return retry;
    }

    public void setRetry(RetryProperties retry) {
        this.retry = retry;
    }

    public AdminProperties getAdmin() {
        return admin;
    }

    public void setAdmin(AdminProperties admin) {
        this.admin = admin;
    }

    // ── Nested configuration records ────────────────────────────────

    /**
     * Workflow store configuration.
     *
     * @param type        store implementation type (e.g., {@code "postgres"})
     * @param tablePrefix prefix for database tables
     * @param schema      database schema name
     */
    public record StoreProperties(
            @DefaultValue("postgres") String type,
            @DefaultValue("maestro_") String tablePrefix,
            @DefaultValue("maestro") String schema
    ) {
        public StoreProperties() {
            this("postgres", "maestro_", "maestro");
        }
    }

    /**
     * Messaging configuration for task dispatch, signals, and lifecycle events.
     *
     * @param type          messaging implementation type (e.g., {@code "kafka"})
     * @param consumerGroup Kafka consumer group; defaults to the service name at runtime
     * @param topics        topic name configuration
     */
    public record MessagingProperties(
            @DefaultValue("kafka") String type,
            @Nullable String consumerGroup,
            @DefaultValue TopicsProperties topics
    ) {
        public MessagingProperties() {
            this("kafka", null, new TopicsProperties());
        }
    }

    /**
     * Kafka topic names for Maestro messaging.
     *
     * @param tasks      topic for workflow task dispatch
     * @param signals    topic for cross-service signal delivery
     * @param adminEvents topic for admin dashboard lifecycle events
     */
    public record TopicsProperties(
            @Nullable String tasks,
            @Nullable String signals,
            @DefaultValue("maestro.admin.events") String adminEvents
    ) {
        public TopicsProperties() {
            this(null, null, "maestro.admin.events");
        }
    }

    /**
     * Distributed lock configuration.
     *
     * @param type      lock implementation type (e.g., {@code "valkey"})
     * @param keyPrefix prefix for lock keys in Redis/Valkey
     * @param ttl       lock time-to-live before expiration
     */
    public record LockProperties(
            @DefaultValue("valkey") String type,
            @DefaultValue("maestro:lock:") String keyPrefix,
            @DefaultValue("30s") Duration ttl
    ) {
        public LockProperties() {
            this("valkey", "maestro:lock:", Duration.ofSeconds(30));
        }
    }

    /**
     * Worker pool configuration.
     *
     * @param taskQueues per-queue concurrency settings
     */
    public record WorkerProperties(
            @DefaultValue List<TaskQueueProperties> taskQueues
    ) {
        public WorkerProperties() {
            this(List.of());
        }
    }

    /**
     * Per-task-queue concurrency configuration.
     *
     * @param name                the task queue name
     * @param concurrency         max concurrent workflow executions
     * @param activityConcurrency max concurrent activity executions
     */
    public record TaskQueueProperties(
            String name,
            @DefaultValue("10") int concurrency,
            @DefaultValue("20") int activityConcurrency
    ) {}

    /**
     * Timer poller configuration.
     *
     * @param pollInterval interval between timer polling cycles
     * @param batchSize    maximum timers to process per cycle
     */
    public record TimerProperties(
            @DefaultValue("5s") Duration pollInterval,
            @DefaultValue("100") int batchSize
    ) {
        public TimerProperties() {
            this(Duration.ofSeconds(5), 100);
        }
    }

    /**
     * Default retry policy applied to activities without explicit configuration.
     *
     * @param defaultMaxAttempts       maximum retry attempts (including initial)
     * @param defaultInitialInterval   initial backoff delay
     * @param defaultMaxInterval       maximum backoff delay
     * @param defaultBackoffMultiplier exponential backoff multiplier
     */
    public record RetryProperties(
            @DefaultValue("3") int defaultMaxAttempts,
            @DefaultValue("1s") Duration defaultInitialInterval,
            @DefaultValue("60s") Duration defaultMaxInterval,
            @DefaultValue("2.0") double defaultBackoffMultiplier
    ) {
        public RetryProperties() {
            this(3, Duration.ofSeconds(1), Duration.ofSeconds(60), 2.0);
        }
    }

    /**
     * Admin dashboard event publishing configuration.
     *
     * @param events lifecycle event settings
     */
    public record AdminProperties(
            @DefaultValue EventsProperties events
    ) {
        public AdminProperties() {
            this(new EventsProperties());
        }
    }

    /**
     * Lifecycle event publishing to the admin dashboard.
     *
     * @param enabled whether lifecycle events are published
     * @param topic   Kafka topic for admin events
     */
    public record EventsProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("maestro.admin.events") String topic
    ) {
        public EventsProperties() {
            this(true, "maestro.admin.events");
        }
    }
}
