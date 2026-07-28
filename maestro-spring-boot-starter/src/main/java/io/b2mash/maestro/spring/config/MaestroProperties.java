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

    /**
     * Packages to scan for {@code @DurableWorkflow} classes. When empty
     * (the default), the application's auto-configuration packages — the
     * package of the {@code @SpringBootApplication} class — are scanned.
     *
     * <p>Note: consumed early (before configuration-property binding) by
     * {@link DurableWorkflowBeanRegistrar} directly from the
     * {@link org.springframework.core.env.Environment}; declared here so the
     * property appears in configuration metadata.
     */
    private List<String> workflowPackages = List.of();

    private StoreProperties store = StoreProperties.defaults();

    private MessagingProperties messaging = MessagingProperties.defaults();

    private LockProperties lock = LockProperties.defaults();

    private WorkerProperties worker = WorkerProperties.defaults();

    private TimerProperties timer = TimerProperties.defaults();

    private RecoveryProperties recovery = RecoveryProperties.defaults();

    private RetryProperties retry = RetryProperties.defaults();

    private AdminProperties admin = AdminProperties.defaults();

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

    public List<String> getWorkflowPackages() {
        return workflowPackages;
    }

    public void setWorkflowPackages(List<String> workflowPackages) {
        this.workflowPackages = workflowPackages;
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

    public RecoveryProperties getRecovery() {
        return recovery;
    }

    public void setRecovery(RecoveryProperties recovery) {
        this.recovery = recovery;
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
    //
    // Every nested block is a record and is bound by Spring Boot's value-object
    // (constructor) binder. That binder is skipped for any type that also
    // declares a no-argument constructor — Boot then falls back to JavaBean
    // binding and, records having no setters, silently binds nothing. So these
    // records must expose exactly one constructor: the canonical one. Defaults
    // for the fields above therefore come from a static `defaults()` factory,
    // never from a no-arg constructor.
    //
    // Pinned by MaestroPropertiesBindingTest.

    /**
     * Workflow store configuration.
     *
     * <p>Tables live in the connection's default schema; use the JDBC URL's
     * {@code currentSchema} parameter to relocate them.
     *
     * @param type        store implementation type (e.g., {@code "postgres"})
     * @param tablePrefix prefix for database tables
     */
    public record StoreProperties(
            @DefaultValue("postgres") String type,
            @DefaultValue("maestro_") String tablePrefix
    ) {
        /** @return the defaults documented above */
        public static StoreProperties defaults() {
            return new StoreProperties("postgres", "maestro_");
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
        /** @return the defaults documented above */
        public static MessagingProperties defaults() {
            return new MessagingProperties("kafka", null, TopicsProperties.defaults());
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
        /** @return the defaults documented above */
        public static TopicsProperties defaults() {
            return new TopicsProperties(null, null, "maestro.admin.events");
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
        /** @return the defaults documented above */
        public static LockProperties defaults() {
            return new LockProperties("valkey", "maestro:lock:", Duration.ofSeconds(30));
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
        /** @return the defaults documented above */
        public static WorkerProperties defaults() {
            return new WorkerProperties(List.of());
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
        /** @return the defaults documented above */
        public static TimerProperties defaults() {
            return new TimerProperties(Duration.ofSeconds(5), 100);
        }
    }

    /**
     * Periodic workflow recovery configuration.
     *
     * <p>The recovery poller re-runs recovery at the given interval so that
     * workflows owned by a node that has since died (instance lock expired)
     * are adopted without a restart.
     *
     * @param enabled      whether the periodic recovery poller runs
     * @param pollInterval interval between recovery cycles
     */
    public record RecoveryProperties(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60s") Duration pollInterval
    ) {
        /** @return the defaults documented above */
        public static RecoveryProperties defaults() {
            return new RecoveryProperties(true, Duration.ofSeconds(60));
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
        /** @return the defaults documented above */
        public static RetryProperties defaults() {
            return new RetryProperties(3, Duration.ofSeconds(1), Duration.ofSeconds(60), 2.0);
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
        /** @return the defaults documented above */
        public static AdminProperties defaults() {
            return new AdminProperties(EventsProperties.defaults());
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
        /** @return the defaults documented above */
        public static EventsProperties defaults() {
            return new EventsProperties(true, "maestro.admin.events");
        }
    }
}
