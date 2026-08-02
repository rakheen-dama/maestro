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

    private ShutdownProperties shutdown = ShutdownProperties.defaults();

    private SignalProperties signal = SignalProperties.defaults();

    private ObservabilityProperties observability = ObservabilityProperties.defaults();

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

    public ShutdownProperties getShutdown() {
        return shutdown;
    }

    public void setShutdown(ShutdownProperties shutdown) {
        this.shutdown = shutdown;
    }

    public SignalProperties getSignal() {
        return signal;
    }

    public void setSignal(SignalProperties signal) {
        this.signal = signal;
    }

    public ObservabilityProperties getObservability() {
        return observability;
    }

    public void setObservability(ObservabilityProperties observability) {
        this.observability = observability;
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
     * @param redelivery    handler-failure redelivery and dead-letter policy
     */
    public record MessagingProperties(
            @DefaultValue("kafka") String type,
            @Nullable String consumerGroup,
            @DefaultValue TopicsProperties topics,
            @DefaultValue RedeliveryProperties redelivery
    ) {
        /** @return the defaults documented above */
        public static MessagingProperties defaults() {
            return new MessagingProperties("kafka", null,
                    TopicsProperties.defaults(), RedeliveryProperties.defaults());
        }
    }

    /**
     * Redelivery and dead-letter policy applied when a message handler throws.
     *
     * <p>A handler exception means the message was <em>not</em> processed — on
     * the engine signal channel it means the signal is not yet durable — so the
     * transport must not acknowledge it. Every transport therefore redelivers
     * the message with exponential backoff and, once the attempt budget is
     * exhausted, routes it to a durable, inspectable dead-letter destination
     * rather than dropping it or looping on it forever.
     *
     * <p>The delay before the attempt following the <i>n</i>-th failure is
     * {@code min(initialInterval × multiplier^(n-1), maxInterval)}, and
     * {@code maxAttempts} counts the initial attempt (matching
     * {@code RetryPolicy} semantics). The defaults give roughly
     * 1s, 2s, 4s, 8s, 16s, 30s, 30s, 30s, 30s between 10 attempts — about
     * 2.5 minutes of tolerance for a transient store outage before a message
     * is parked. Raise {@code max-attempts} for longer outage tolerance;
     * lower it to contain a poison message sooner.
     *
     * <p>Dead-letter destinations are never created by Maestro on Kafka: the
     * {@code <topic><dead-letter-suffix>} topics are pre-declared by the
     * operator like every other topic. The Postgres transport needs nothing
     * created (it parks rows in {@code DEAD_LETTER} status).
     *
     * @param maxAttempts        total delivery attempts, including the first
     * @param initialInterval    backoff before the second attempt
     * @param multiplier         factor applied to the backoff after each failure
     * @param maxInterval        ceiling for the computed backoff
     * @param deadLetterSuffix   Kafka only — suffix appended to the source topic
     */
    public record RedeliveryProperties(
            @DefaultValue("10") int maxAttempts,
            @DefaultValue("1s") Duration initialInterval,
            @DefaultValue("2.0") double multiplier,
            @DefaultValue("30s") Duration maxInterval,
            @DefaultValue(".DLT") String deadLetterSuffix
    ) {
        /** @return the defaults documented above */
        public static RedeliveryProperties defaults() {
            return new RedeliveryProperties(10, Duration.ofSeconds(1), 2.0,
                    Duration.ofSeconds(30), ".DLT");
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
     * @param enabled whether {@code WORKFLOW_STARTED}/{@code _COMPLETED}/{@code _FAILED}
     *                lifecycle events are published at all. {@code false} disables
     *                publishing entirely, independent of the messaging topic — a
     *                legitimate choice for deployments that do not run the admin
     *                dashboard and want no lifecycle traffic on the broker.
     * @param topic   <b>Deprecated alias</b> for {@link TopicsProperties#adminEvents()}
     *                ({@code maestro.messaging.topics.admin-events}), kept for
     *                deployments that only disable the dashboard's own events and
     *                never touched the messaging block. If both properties are set,
     *                {@code maestro.messaging.topics.admin-events} wins and a WARN is
     *                logged at startup. Prefer configuring the topic there.
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

    /**
     * Graceful shutdown configuration.
     *
     * @param timeout how long {@code WorkflowExecutor.shutdown()} waits for
     *                in-flight workflows to drain before returning; workflows
     *                that overrun it are left running and their instance
     *                locks expire by TTL
     */
    public record ShutdownProperties(
            @DefaultValue("30s") Duration timeout
    ) {
        /** @return the defaults documented above */
        public static ShutdownProperties defaults() {
            return new ShutdownProperties(Duration.ofSeconds(30));
        }
    }

    /**
     * Signal handling configuration.
     *
     * @param wakeRecheckInterval how often a workflow parked on
     *                            {@code awaitSignal()} re-checks the store for
     *                            a signal persisted without a notification
     *                            reaching this instance — e.g. ingested on
     *                            another node in a deployment with no
     *                            {@code SignalNotifier} (Kafka messaging
     *                            without Valkey). Bounds cross-node
     *                            signal latency in those cases.
     */
    public record SignalProperties(
            @DefaultValue("30s") Duration wakeRecheckInterval
    ) {
        /** @return the defaults documented above */
        public static SignalProperties defaults() {
            return new SignalProperties(Duration.ofSeconds(30));
        }
    }

    /**
     * Observability configuration: Micrometer meters and tracing.
     *
     * @param metrics meter registration and emission
     * @param tracing span creation and Kafka trace propagation
     */
    public record ObservabilityProperties(
            @DefaultValue MetricsProperties metrics,
            @DefaultValue TracingProperties tracing
    ) {
        /** @return the defaults documented above */
        public static ObservabilityProperties defaults() {
            return new ObservabilityProperties(
                    MetricsProperties.defaults(), TracingProperties.defaults());
        }
    }

    /**
     * @param enabled whether Maestro registers and emits Micrometer meters
     *                (requires a {@code MeterRegistry} on the classpath and in
     *                the context; silently inert otherwise)
     */
    public record MetricsProperties(@DefaultValue("true") boolean enabled) {
        /** @return the defaults documented above */
        public static MetricsProperties defaults() {
            return new MetricsProperties(true);
        }
    }

    /**
     * @param enabled whether Maestro creates spans and propagates W3C trace
     *                context through Kafka headers (requires a Micrometer
     *                {@code Tracer} in the context; silently inert otherwise)
     */
    public record TracingProperties(@DefaultValue("true") boolean enabled) {
        /** @return the defaults documented above */
        public static TracingProperties defaults() {
            return new TracingProperties(true);
        }
    }
}
