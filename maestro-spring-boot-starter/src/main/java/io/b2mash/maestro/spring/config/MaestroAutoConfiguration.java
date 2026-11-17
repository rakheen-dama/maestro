package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.engine.ActivityProxyFactory;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.TaskQueueLimits;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.observe.CompositeEngineObserver;
import io.b2mash.maestro.core.observe.EngineObserver;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.spi.DistributedLock;
import io.b2mash.maestro.core.spi.SignalNotifier;
import io.b2mash.maestro.core.spi.WorkflowMessaging;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.spring.client.MaestroClient;
import io.b2mash.maestro.spring.proxy.ActivityStubBeanPostProcessor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Auto-configuration for the Maestro durable workflow engine.
 *
 * <p>Activates when:
 * <ul>
 *   <li>{@code maestro.enabled} is {@code true} (default)</li>
 *   <li>A {@link WorkflowStore} bean exists (provided by a store module
 *       such as {@code maestro-store-postgres})</li>
 * </ul>
 *
 * <p>Optional SPIs ({@link DistributedLock}, {@link WorkflowMessaging},
 * {@link SignalNotifier}) are injected when available — {@code maestro-store-postgres}
 * contributes the first and last, {@code maestro-messaging-kafka} the second.
 * When absent, the engine runs in single-node mode without distributed
 * locking, cross-service messaging or instant cross-node signal wake.
 *
 * @see MaestroProperties
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "maestro", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(WorkflowStore.class)
@EnableConfigurationProperties(MaestroProperties.class)
public class MaestroAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MaestroAutoConfiguration.class);

    /**
     * Logged at WARN once at startup while {@code maestro.replay.verify-arguments=false}:
     * a replayed activity then returns its recorded result even when it is being
     * called with different arguments than the recorded run — the silent failure
     * the fingerprint exists to catch. Package-private so the wording is pinned.
     */
    static final String REPLAY_VERIFICATION_DISABLED_WARNING =
            "maestro.replay.verify-arguments=false: replayed activities will NOT be checked against the "
                    + "arguments they were recorded with — a non-deterministic input (map order, clock, "
                    + "per-node config) replays the recorded result silently. Live outcomes still record "
                    + "their fingerprint; re-enable as soon as the drift is fixed.";

    @Bean
    @ConditionalOnMissingBean
    public PayloadSerializer maestroPayloadSerializer(ObjectMapper objectMapper) {
        return new PayloadSerializer(objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public RetryExecutor maestroRetryExecutor() {
        return new RetryExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public ActivityProxyFactory maestroActivityProxyFactory() {
        return new ActivityProxyFactory();
    }

    @Bean
    @ConditionalOnMissingBean
    public WorkflowExecutor maestroWorkflowExecutor(
            WorkflowStore store,
            @Nullable DistributedLock distributedLock,
            @Nullable WorkflowMessaging messaging,
            @Nullable SignalNotifier signalNotifier,
            PayloadSerializer serializer,
            MaestroProperties properties,
            ObjectProvider<EngineObserver> observers
    ) {
        var serviceName = properties.getServiceName();
        if (serviceName == null || serviceName.isBlank()) {
            throw new IllegalStateException(
                    "maestro.service-name must be set. Configure it in application.yml or application.properties.");
        }
        // Runtime proof of which DistributedLock implementation actually wired
        // up, independent of what maestro.lock.type SAYS: the auto-configuration
        // that won the @ConditionalOnProperty race injected this exact bean.
        // Logged at INFO (not DEBUG) so an operator - or an E2E harness grepping
        // this process's own log - can confirm the effective backend without
        // trusting configuration alone.
        log.info("Maestro distributed-lock backend: {}",
                distributedLock != null ? distributedLock.getClass().getName() : "none (single-node mode)");
        log.info("Maestro cross-node signal wake: {}",
                signalNotifier != null ? signalNotifier.getClass().getName()
                        : "none (cross-node wake falls back to maestro.signal.wake-recheck-interval)");
        if (!properties.getReplay().verifyArguments()) {
            log.warn(REPLAY_VERIFICATION_DISABLED_WARNING);
        }
        var lock = properties.getLock();
        var lifecycleEventsEnabled = properties.getAdmin().events().enabled();
        var shutdownTimeout = properties.getShutdown().timeout();
        var wakeRecheckInterval = properties.getSignal().wakeRecheckInterval();
        // Every EngineObserver bean in the context (MicrometerEngineObserver,
        // TracingEngineObserver, or any user-supplied observer) is collected
        // here and wrapped once — CompositeEngineObserver.of always wraps,
        // even a single delegate, so a throwing adapter can never corrupt
        // engine control flow (coordinator Ruling 4).
        var observer = CompositeEngineObserver.of(observers.orderedStream().toList());
        var worker = properties.getWorker();
        var queueLimits = taskQueueLimits(worker);
        var defaultLimits = defaultTaskQueueLimits(worker);
        log.info("Maestro worker limits: {}", describeWorkerLimits(queueLimits, defaultLimits));
        return WorkflowExecutor.builder(store, serializer, serviceName)
                .distributedLock(distributedLock)
                .messaging(messaging)
                .signalNotifier(signalNotifier)
                .lockKeyPrefix(lock.keyPrefix())
                .instanceLockTtl(lock.ttl())
                .lifecycleEventsEnabled(lifecycleEventsEnabled)
                .shutdownTimeout(shutdownTimeout)
                .wakeRecheckInterval(wakeRecheckInterval)
                .observer(observer)
                .taskQueueLimits(queueLimits)
                .defaultTaskQueueLimits(defaultLimits)
                .build();
    }

    /**
     * Builds the limits of every task queue not listed in
     * {@code maestro.worker.task-queues} from {@code maestro.worker.default-concurrency}
     * and {@code maestro.worker.default-activity-concurrency}. {@code 0} on both
     * means unlisted queues are unbounded ({@code null}). Fails startup when
     * exactly one is {@code 0} or either is negative — half a bound, or a
     * negative one, is not a configuration that means anything.
     */
    static @Nullable TaskQueueLimits defaultTaskQueueLimits(MaestroProperties.WorkerProperties worker) {
        var workflows = worker.defaultConcurrency();
        var activities = worker.defaultActivityConcurrency();
        if (workflows == 0 && activities == 0) {
            return null;
        }
        if (workflows < 1 || activities < 1) {
            throw new IllegalStateException(("maestro.worker.default-concurrency=%d and "
                    + "maestro.worker.default-activity-concurrency=%d: both must be >= 1, or both 0 to leave "
                    + "task queues not listed in maestro.worker.task-queues unbounded")
                    .formatted(workflows, activities));
        }
        return new TaskQueueLimits(workflows, activities);
    }

    /** The effective worker limits, as the startup INFO line states them. */
    static String describeWorkerLimits(Map<String, TaskQueueLimits> queueLimits,
                                       @Nullable TaskQueueLimits defaultLimits) {
        var text = defaultLimits == null
                ? "unbounded (maestro.worker.default-concurrency=0)"
                : "default %d/%d per task queue (workflows/activities)".formatted(
                        defaultLimits.workflowConcurrency(), defaultLimits.activityConcurrency());
        if (queueLimits.isEmpty()) {
            return text;
        }
        return text + "; configured: " + queueLimits.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().workflowConcurrency()
                        + "/" + e.getValue().activityConcurrency())
                .collect(Collectors.joining(", "));
    }

    /**
     * Builds the executor's per-task-queue limits from {@code maestro.worker.task-queues}
     * (F8 / Issue 25). Fails startup on an entry without a name, a queue named
     * twice, or a limit below 1 — a silently ignored or merged entry would bound
     * a queue differently from what the operator wrote.
     */
    static Map<String, TaskQueueLimits> taskQueueLimits(MaestroProperties.WorkerProperties worker) {
        var limits = new LinkedHashMap<String, TaskQueueLimits>();
        for (var queue : worker.taskQueues()) {
            var name = queue.name();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("maestro.worker.task-queues has an entry without a name");
            }
            TaskQueueLimits queueLimits;
            try {
                queueLimits = new TaskQueueLimits(queue.concurrency(), queue.activityConcurrency());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "maestro.worker.task-queues '%s': %s".formatted(name, e.getMessage()), e);
            }
            if (limits.putIfAbsent(name, queueLimits) != null) {
                throw new IllegalStateException(
                        "maestro.worker.task-queues names a queue twice: '%s'".formatted(name));
            }
        }
        return limits;
    }

    /**
     * Registers bare {@code @DurableWorkflow} classes as Spring beans via
     * classpath scanning. Static because {@code BeanDefinitionRegistryPostProcessor}
     * beans are instantiated very early, before this configuration class itself.
     *
     * <p>Also the engine's earliest unconditional hook, so it is where removed
     * properties are rejected ({@code rejectRemovedProperties}) — before
     * any other bean, transport included, is created, and whatever beans the
     * application overrides.
     *
     * @throws IllegalStateException if a removed {@code maestro.*} property is set
     */
    @Bean
    public static DurableWorkflowBeanRegistrar maestroDurableWorkflowBeanRegistrar(
            Environment environment
    ) {
        rejectRemovedProperties(environment);
        return new DurableWorkflowBeanRegistrar(environment);
    }

    /**
     * Fails startup when a removed property is still set, rather than letting
     * it bind to nothing and be silently ignored. Transport-neutral: checked
     * whichever messaging module (or none) is on the classpath.
     *
     * <ul>
     *   <li>{@code maestro.messaging.topics.tasks} — Maestro no longer
     *       dispatches work over a task topic (workflows start in-process), so
     *       the property and the operator's pre-created {@code maestro.tasks.*}
     *       topics would otherwise go silently unused.</li>
     *   <li>{@code maestro.lock.type} other than {@code postgres} — the Valkey
     *       lock backend was removed; the property stays so a value can be
     *       re-added later, but today only {@code postgres} exists and any
     *       other value would silently leave the engine with no lock.</li>
     *   <li>{@code maestro.messaging.type} other than {@code kafka} — the
     *       Postgres messaging transport was removed; likewise.</li>
     * </ul>
     *
     * <p>({@code maestro.store.table-prefix} is rejected by the Postgres store
     * module, before any DDL runs.)
     */
    static void rejectRemovedProperties(Environment environment) {
        if (environment.containsProperty("maestro.messaging.topics.tasks")) {
            throw new IllegalStateException("maestro.messaging.topics.tasks has been removed: Maestro no longer "
                    + "dispatches work over a task topic (workflows start in-process). Delete the property "
                    + "and the maestro.tasks.* topics.");
        }
        var lockType = environment.getProperty("maestro.lock.type");
        if (lockType != null && !lockType.strip().equalsIgnoreCase("postgres")) {
            throw new IllegalStateException("maestro.lock.type='" + lockType + "' is not supported: the Valkey lock "
                    + "backend was removed; only 'postgres' is available. Remove the property or set it to "
                    + "'postgres'.");
        }
        var messagingType = environment.getProperty("maestro.messaging.type");
        if (messagingType != null && !messagingType.strip().equalsIgnoreCase("kafka")) {
            throw new IllegalStateException("maestro.messaging.type='" + messagingType + "' is not supported: the "
                    + "Postgres messaging transport was removed; only 'kafka' is available. Remove the property "
                    + "or set it to 'kafka'.");
        }
    }

    @Bean
    public ActivityStubBeanPostProcessor maestroActivityStubBeanPostProcessor() {
        return new ActivityStubBeanPostProcessor();
    }

    @Bean
    public WorkflowRegistrar maestroWorkflowRegistrar(WorkflowExecutor executor) {
        return new WorkflowRegistrar(executor);
    }

    @Bean
    public StartupRecoveryRunner maestroStartupRecoveryRunner(
            WorkflowExecutor executor,
            WorkflowRegistrar registrar,
            MaestroProperties properties
    ) {
        return new StartupRecoveryRunner(executor, registrar, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AdminCommandDispatcher maestroAdminCommandDispatcher(
            WorkflowExecutor executor,
            WorkflowStore store,
            WorkflowRegistrar registrar
    ) {
        return new AdminCommandDispatcher(executor, store, registrar);
    }

    @Bean
    public SignalSubscriptionRunner maestroSignalSubscriptionRunner(
            WorkflowExecutor executor,
            AdminCommandDispatcher commandDispatcher,
            @Nullable WorkflowMessaging messaging,
            MaestroProperties properties
    ) {
        return new SignalSubscriptionRunner(executor, commandDispatcher, messaging, properties);
    }

    @Bean
    public GracefulShutdownHandler maestroGracefulShutdownHandler(WorkflowExecutor executor) {
        return new GracefulShutdownHandler(executor);
    }

    @Bean
    @ConditionalOnMissingBean
    public MaestroClient maestroClient(
            WorkflowExecutor executor,
            WorkflowRegistrar registrar,
            PayloadSerializer serializer,
            WorkflowStore store
    ) {
        return new MaestroClient(executor, registrar, serializer, store);
    }
}
