package io.b2mash.maestro.spring.config;

import io.b2mash.maestro.test.InMemoryDistributedLock;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.b2mash.maestro.core.annotation.DurableWorkflow;
import io.b2mash.maestro.core.annotation.WorkflowMethod;
import io.b2mash.maestro.core.engine.PayloadSerializer;
import io.b2mash.maestro.core.engine.WorkflowExecutor;
import io.b2mash.maestro.core.engine.WorkflowRegistration;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.spi.WorkflowStore;
import io.b2mash.maestro.test.InMemoryWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The startup service-name check as the starter runs it: {@link StartupRecoveryRunner}
 * starts it, it warns once per other service name holding active instances
 * of this node's workflow types, stays silent on a clean node, and never
 * delays or fails startup.
 */
class StartupForeignServiceCheckTest {

    private static final String SERVICE = "orders";
    private static final String CHECK_LOGGER = "io.b2mash.maestro.core.engine.ForeignServiceCheck";

    private final List<WorkflowExecutor> ownExecutors = new ArrayList<>();
    /** Holds every {@link HoldWorkflow} run until the test ends. */
    private final CountDownLatch hold = new CountDownLatch(1);
    /** Ends a blocked service-name read. */
    private final CountDownLatch release = new CountDownLatch(1);

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger checkLog;
    private Level previousLevel;

    @BeforeEach
    void captureTheChecksLog() {
        checkLog = (Logger) LoggerFactory.getLogger(CHECK_LOGGER);
        previousLevel = checkLog.getLevel();
        checkLog.setLevel(Level.DEBUG);
        appender.start();
        checkLog.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        hold.countDown();
        ownExecutors.forEach(WorkflowExecutor::shutdown);
        checkLog.detachAppender(appender);
        checkLog.setLevel(previousLevel);
    }

    @Test
    void aNodeBesideActiveInstancesUnderAnotherServiceNameWarnsOnceNamingItAndTheCount() {
        var store = new InMemoryWorkflowStore();
        seed(store, "old-1", "orders-old", WorkflowStatus.RUNNING);
        seed(store, "old-2", "orders-old", WorkflowStatus.WAITING_SIGNAL);
        seed(store, "old-3", "orders-old", WorkflowStatus.WAITING_TIMER);
        seed(store, "own-1", SERVICE, WorkflowStatus.RUNNING);

        runnerFor(node(store), holdRegistrations(), recovery(true, Duration.ofSeconds(60))).run(null);

        var warns = valueWithin(() -> warnings().size(), 1, Duration.ofSeconds(5));
        assertEquals(1, warns);
        assertTrue(warnings().getFirst().startsWith("3 active workflow instance(s) of a type this node runs are "
                + "recorded under maestro.service-name 'orders-old'"), warnings().getFirst());
    }

    @Test
    void aCleanNodeLogsNothingAboveDebug() {
        var store = new InMemoryWorkflowStore();
        seed(store, "own-1", SERVICE, WorkflowStatus.RUNNING);

        runnerFor(node(store), holdRegistrations(), recovery(true, Duration.ofSeconds(60))).run(null);

        // The check's last act on a clean result is this DEBUG line: once it
        // is logged, the check has finished.
        var finished = valueWithin(() -> messagesAt(Level.DEBUG).stream()
                .anyMatch(m -> m.startsWith("Maestro service-name check: no other service name")), true,
                Duration.ofSeconds(5));
        assertTrue(finished, "the service-name check finished: " + messagesAt(Level.DEBUG));
        assertEquals(List.of(), messagesAboveDebug());
    }

    @Test
    void moreThanTwentyForeignNamesWarnAboutTwentyAndSayThereAreMore() {
        var names = new java.util.LinkedHashMap<String, Long>();
        for (int i = 0; i < 21; i++) {
            names.put("orders-%02d".formatted(i), 21L - i);
        }
        var store = answering(new InMemoryWorkflowStore(), names);

        runnerFor(node(store), holdRegistrations(), recovery(true, Duration.ofSeconds(60))).run(null);

        var more = valueWithin(() -> infos().stream().anyMatch(m -> m.startsWith("More than 20 other service names")),
                true, Duration.ofSeconds(5));
        assertTrue(more, "an INFO says there are more: " + infos());
        assertEquals(20, warnings().size());
        assertTrue(warnings().getLast().contains("'orders-19'"), warnings().getLast());
    }

    @Test
    void runReturnsWhileTheCheckIsStillRunning() throws Exception {
        var entered = new CountDownLatch(1);
        var store = onCountsRead(new InMemoryWorkflowStore(), () -> {
            entered.countDown();
            release.await(30, SECONDS);
        }, new CountDownLatch(1));
        var runner = runnerFor(node(store), holdRegistrations(), recovery(true, Duration.ofSeconds(60)));
        var returned = new CountDownLatch(1);
        Thread.ofVirtual().start(() -> {
            runner.run(null);
            returned.countDown();
        });
        try {
            assertTrue(returned.await(2, SECONDS), "run() returned while the service-name read was blocked");
            assertTrue(entered.await(5, SECONDS), "the service-name check ran");
        } finally {
            release.countDown();
        }
    }

    @Test
    void aCheckThatThrowsNeverFailsStartup() {
        var store = onCountsRead(new InMemoryWorkflowStore(), () -> {
            throw new IllegalStateException("injected: the count query failed");
        }, new CountDownLatch(1));
        var runner = runnerFor(node(store), holdRegistrations(), recovery(true, Duration.ofSeconds(60)));

        assertDoesNotThrow(() -> runner.run(null));

        var skipped = valueWithin(() -> infos().stream()
                .anyMatch(m -> m.startsWith("Maestro service-name check skipped")), true, Duration.ofSeconds(5));
        assertTrue(skipped, "an INFO says the check was skipped: " + infos());
        assertEquals(List.of(), warnings());
    }

    // ── helpers ──────────────────────────────────────────────────────

    /** Something to do before the service-name read; may block or throw. */
    @FunctionalInterface
    private interface ReadHook {
        void beforeRead() throws Exception;
    }

    /**
     * A {@link WorkflowStore} proxy over {@code delegate} that runs
     * {@code hook} before the service-name read and counts {@code returned}
     * down once that read has returned.
     */
    private static WorkflowStore onCountsRead(InMemoryWorkflowStore delegate, ReadHook hook,
                                              CountDownLatch returned) {
        return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                    var countsRead = method.getName().equals("getActiveInstanceCountsUnderOtherServices");
                    if (countsRead) {
                        hook.beforeRead();
                    }
                    try {
                        var result = method.invoke(delegate, args);
                        if (countsRead) {
                            returned.countDown();
                        }
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    /**
     * A {@link WorkflowStore} proxy over {@code delegate} whose service-name
     * read answers {@code counts}.
     */
    private static WorkflowStore answering(InMemoryWorkflowStore delegate, Map<String, Long> counts) {
        return (WorkflowStore) Proxy.newProxyInstance(WorkflowStore.class.getClassLoader(),
                new Class<?>[]{WorkflowStore.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getActiveInstanceCountsUnderOtherServices")) {
                        return counts;
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    private List<String> messagesAboveDebug() {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    private List<String> warnings() {
        return messagesAt(Level.WARN);
    }

    private List<String> infos() {
        return messagesAt(Level.INFO);
    }

    /** The appender appends under its own lock, from the check's thread; read under the same lock. */
    private List<String> messagesAt(Level level) {
        synchronized (appender) {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == level)
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }
    }

    private WorkflowExecutor node(WorkflowStore store) {
        var executor = WorkflowExecutor.builder(store, new PayloadSerializer(new ObjectMapper()), SERVICE).distributedLock(new InMemoryDistributedLock())
                .lifecycleEventsEnabled(false)
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

    private static void seed(WorkflowStore store, String workflowId, String serviceName, WorkflowStatus status) {
        var now = Instant.now();
        store.createInstance(WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId(workflowId)
                .runId(UUID.randomUUID())
                .workflowType("HoldWorkflow")
                .taskQueue("default")
                .status(status)
                .serviceName(serviceName)
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
