package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.TaskQueueLimits;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.exception.StoreUnavailableException;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link StartupRecoveryRunner}'s recovery-poller startup.
 *
 * <p>Whether the poller was started is observed through
 * {@link WorkflowExecutor#startRecoveryPoller}, which throws if the poller
 * is already running.
 *
 * <p>The runner adopts nothing itself: recovery runs on the recovery poller's
 * thread, or on a one-shot thread when the poller is disabled.
 */
class StartupRecoveryRunnerTest {

    private WorkflowExecutor executor;
    private MaestroProperties properties;
    private StartupRecoveryRunner runner;

    @BeforeEach
    void setUp() {
        var store = new InMemoryWorkflowStore();
        var serializer = new PayloadSerializer(new ObjectMapper());
        executor = WorkflowExecutor.builder(store, serializer, "test-service").build();
        properties = new MaestroProperties();
        properties.setServiceName("test-service");
        runner = new StartupRecoveryRunner(executor, new WorkflowRegistrar(executor), properties);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    @DisplayName("starts the periodic recovery poller by default")
    void startsRecoveryPollerByDefault() {
        runner.run(null);

        assertThrows(IllegalStateException.class,
                () -> executor.startRecoveryPoller(Map.of(), Duration.ofSeconds(60)),
                "the runner must have already started the recovery poller");
    }

    @Test
    @DisplayName("does not start the recovery poller when maestro.recovery.enabled=false")
    void skipsRecoveryPollerWhenDisabled() {
        properties.setRecovery(new MaestroProperties.RecoveryProperties(false, Duration.ofSeconds(60)));

        runner.run(null);

        assertDoesNotThrow(
                () -> executor.startRecoveryPoller(Map.of(), Duration.ofSeconds(60)),
                "the poller slot must still be free when recovery polling is disabled");
    }

    // ── The runner only starts recovery; it never runs a pass itself ──

    private static final String SERVICE = "test-service";

    private final List<WorkflowExecutor> ownExecutors = new ArrayList<>();
    /** Holds every {@link HoldWorkflow} run until the test ends. */
    private final CountDownLatch hold = new CountDownLatch(1);

    @AfterEach
    void shutDownOwnExecutors() {
        hold.countDown();
        ownExecutors.forEach(WorkflowExecutor::shutdown);
    }

    @Test
    @DisplayName("run() returns while the startup recovery pass is still reading")
    void theRunnerReturnsWhileTheStartupPassIsStillReading() throws Exception {
        var reading = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = onPageRead(new InMemoryWorkflowStore(), () -> {
            reading.countDown();
            release.await(30, SECONDS);
        });
        var runner = runnerFor(node(store, null), Map.of(), recovery(true, Duration.ofSeconds(60)));
        var returned = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            runner.run(null);
            returned.countDown();
        });
        try {
            assertTrue(reading.await(5, SECONDS), "the startup pass began reading");
            assertTrue(returned.await(2, SECONDS), "run() returned while the startup pass was still reading");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("with maestro.recovery.enabled=false the startup pass adopts every instance, past the bound")
    void withRecoveryDisabledTheStartupPassIsUngated() {
        var store = new InMemoryWorkflowStore();
        for (var i = 0; i < 3; i++) {
            seed(store, "wf-" + i);
        }
        var executor = node(store, new TaskQueueLimits(1, 1));
        runnerFor(executor, holdRegistrations(), recovery(false, Duration.ofSeconds(60))).run(null);

        assertEquals(3, valueWithin(executor::runningCount, 3, Duration.ofSeconds(5)),
                "every instance adopted although the queue runs one at a time");
        assertDoesNotThrow(() -> executor.startRecoveryPoller(Map.of(), Duration.ofSeconds(60)),
                "the one-shot pass does not occupy the recovery poller's slot");
    }

    @Test
    @DisplayName("with maestro.recovery.enabled=false a startup pass that fails is retried")
    void withRecoveryDisabledAFailedStartupPassIsRetried() {
        var reads = new AtomicInteger();
        var store = onPageRead(new InMemoryWorkflowStore(), () -> {
            if (reads.incrementAndGet() == 1) {
                throw new StoreUnavailableException("injected: the store is down for the first read",
                        new IllegalStateException("connection refused"));
            }
        });
        seed(store, "wf-1");
        var executor = node(store, null);
        runnerFor(executor, holdRegistrations(), recovery(false, Duration.ofMillis(50))).run(null);

        assertEquals(1, valueWithin(executor::runningCount, 1, Duration.ofSeconds(5)),
                "the retried pass adopted the instance");
    }

    @Test
    @DisplayName("a store failure in the startup recovery pass does not fail run()")
    void aStoreFailureInTheStartupPassDoesNotFailRun() {
        var store = onPageRead(new InMemoryWorkflowStore(), () -> {
            throw new StoreUnavailableException("injected: the store is down",
                    new IllegalStateException("connection refused"));
        });
        var runner = runnerFor(node(store, null), Map.of(), recovery(true, Duration.ofSeconds(60)));

        assertDoesNotThrow(() -> runner.run(null));
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Something to do before each recovery-page read; may block or throw. */
    @FunctionalInterface
    private interface PageReadHook {
        void beforeRead() throws Exception;
    }

    /**
     * A {@link WorkflowStore} proxy over {@code delegate} that runs {@code hook}
     * before every keyset recovery-page read (the three-argument
     * {@code getRecoverableInstances}, a default method a JDK proxy intercepts too).
     */
    private static WorkflowStore onPageRead(InMemoryWorkflowStore delegate, PageReadHook hook) {
        return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getRecoverableInstances") && method.getParameterCount() == 3) {
                        hook.beforeRead();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private WorkflowExecutor node(WorkflowStore store, @Nullable TaskQueueLimits defaults) {
        var executor = WorkflowExecutor.builder(store, new PayloadSerializer(new ObjectMapper()), SERVICE)
                .lifecycleEventsEnabled(false)
                .defaultTaskQueueLimits(defaults)
                .shutdownTimeout(Duration.ofSeconds(5))
                .build();
        ownExecutors.add(executor);
        return executor;
    }

    private static MaestroProperties recovery(boolean enabled, Duration pollInterval) {
        var properties = new MaestroProperties();
        properties.setServiceName(SERVICE);
        properties.setRecovery(new MaestroProperties.RecoveryProperties(enabled, pollInterval));
        return properties;
    }

    private static StartupRecoveryRunner runnerFor(WorkflowExecutor executor,
                                                   Map<String, WorkflowRegistration> registrations,
                                                   MaestroProperties properties) {
        var registrar = new WorkflowRegistrar(executor) {
            @Override
            public Map<String, WorkflowRegistration> getRegistrations() {
                return registrations;
            }
        };
        return new StartupRecoveryRunner(executor, registrar, properties);
    }

    private Map<String, WorkflowRegistration> holdRegistrations() {
        try {
            return Map.of("HoldWorkflow", new WorkflowRegistration("HoldWorkflow", "default",
                    () -> new HoldWorkflow(hold), HoldWorkflow.class.getMethod("run")));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void seed(WorkflowStore store, String workflowId) {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("HoldWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName(SERVICE)
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build());
    }

    /** Polls {@code read} until it equals {@code wanted} or {@code within} elapses; returns the last value read. */
    private static <T> T valueWithin(Supplier<T> read, T wanted, Duration within) {
        var deadline = System.nanoTime() + within.toNanos();
        var value = read.get();
        while (!wanted.equals(value) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(5); // a poll interval, not a wait for a condition
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return value;
            }
            value = read.get();
        }
        return value;
    }

    /** A run that stays running until the test ends. */
    @DurableWorkflow(name = "HoldWorkflow")
    public static final class HoldWorkflow {
        private final CountDownLatch hold;

        HoldWorkflow(CountDownLatch hold) {
            this.hold = hold;
        }

        /** @return a marker, once the test lets the run end */
        @WorkflowMethod
        public String run() throws InterruptedException {
            hold.await(30, SECONDS);
            return "done";
        }
    }
}
