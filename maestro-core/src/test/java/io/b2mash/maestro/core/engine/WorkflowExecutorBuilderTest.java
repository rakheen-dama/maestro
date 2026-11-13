package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.observe.EngineObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 37: {@link WorkflowExecutor.Builder} — its defaults are exactly the
 * values the removed six-argument form supplied, and {@code build()} keeps
 * the validation the removed constructors performed.
 */
@DisplayName("WorkflowExecutor.Builder")
class WorkflowExecutorBuilderTest {

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private final List<WorkflowExecutor> built = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        built.forEach(WorkflowExecutor::shutdown);
    }

    private WorkflowExecutor keep(WorkflowExecutor executor) {
        built.add(executor);
        return executor;
    }

    @Test
    void defaultsAreTheValuesTheSixArgumentConstructorSupplied() {
        var b = WorkflowExecutor.builder(store, serializer, "svc");
        assertNull(b.distributedLock);
        assertNull(b.messaging);
        assertNull(b.signalNotifier);
        assertEquals("maestro:lock:", b.lockKeyPrefix);
        assertSame(WorkflowInstanceLockManager.DEFAULT_KEY_PREFIX, b.lockKeyPrefix);
        assertEquals(Duration.ofSeconds(30), b.instanceLockTtl);
        assertSame(WorkflowInstanceLockManager.DEFAULT_LOCK_TTL, b.instanceLockTtl);
        assertTrue(b.lifecycleEventsEnabled);
        assertEquals(Duration.ofSeconds(30), b.shutdownTimeout);
        assertSame(WorkflowExecutor.DEFAULT_SHUTDOWN_TIMEOUT, b.shutdownTimeout);
        assertEquals(Duration.ofSeconds(30), b.wakeRecheckInterval);
        assertSame(SignalManager.DEFAULT_WAKE_RECHECK_INTERVAL, b.wakeRecheckInterval);
        assertSame(EngineObserver.NOOP, b.observer);
        assertEquals(Map.of(), b.taskQueueLimits);
        assertNull(b.defaultTaskQueueLimits);
        assertSame(InstantSource.system(), b.instantSource);
    }

    @Test
    void aDefaultBuildIsUnboundedOnTheSystemClock() {
        var e = keep(WorkflowExecutor.builder(store, serializer, "svc").build());
        assertSame(InstantSource.system(), e.instantSource());
        assertSame(TaskQueueGovernor.UNBOUNDED, e.taskQueueGovernor());
    }

    @Test
    void defaultTaskQueueLimitsAloneBoundsAnUnlistedQueue() {
        var e = keep(WorkflowExecutor.builder(store, serializer, "svc")
                .defaultTaskQueueLimits(new TaskQueueLimits(2, 3)).build());
        assertNotSame(TaskQueueGovernor.UNBOUNDED, e.taskQueueGovernor());
        assertEquals(OptionalInt.of(2), e.taskQueueGovernor().availableWorkflowSlots("never-configured"));
        assertEquals(OptionalInt.of(3), e.taskQueueGovernor().availableActivitySlots("never-configured"));
    }

    @Test
    void buildRejectsNonPositiveDurationsWithTodaysMessages() {
        Map<String, BiFunction<WorkflowExecutor.Builder, Duration, WorkflowExecutor.Builder>> setters = Map.of(
                "instanceLockTtl", WorkflowExecutor.Builder::instanceLockTtl,
                "shutdownTimeout", WorkflowExecutor.Builder::shutdownTimeout,
                "wakeRecheckInterval", WorkflowExecutor.Builder::wakeRecheckInterval);
        for (var setter : setters.entrySet()) {
            for (var value : Arrays.asList(Duration.ZERO, Duration.ofSeconds(-5), null)) {
                var b = WorkflowExecutor.builder(store, serializer, "svc");
                // The setter stores the value and nothing else; build() rejects it.
                setter.getValue().apply(b, value);
                var e = assertThrows(IllegalArgumentException.class, b::build,
                        setter.getKey() + " = " + value);
                assertEquals(setter.getKey() + " must be positive, got " + value, e.getMessage());
            }
        }
        assertEquals("shutdownTimeout must be positive, got PT-5S",
                assertThrows(IllegalArgumentException.class,
                        WorkflowExecutor.builder(store, serializer, "svc")
                                .shutdownTimeout(Duration.ofSeconds(-5))::build).getMessage());
        assertEquals("wakeRecheckInterval must be positive, got null",
                assertThrows(IllegalArgumentException.class,
                        WorkflowExecutor.builder(store, serializer, "svc")
                                .wakeRecheckInterval(null)::build).getMessage());
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void buildChecksDurationsBeforeReferences() {
        // Pins the whole order: instanceLockTtl before a null reference and
        // before shutdownTimeout; shutdownTimeout before wakeRecheckInterval;
        // and wakeRecheckInterval, the last duration check, before every
        // reference check (lockKeyPrefix, observer, taskQueueLimits,
        // instantSource) — so no null check runs before or between them.
        var nullObserver = assertThrows(IllegalArgumentException.class,
                WorkflowExecutor.builder(store, serializer, "svc")
                        .observer(null)
                        .instanceLockTtl(Duration.ZERO)::build);
        assertTrue(nullObserver.getMessage().startsWith("instanceLockTtl must be positive"),
                nullObserver.getMessage());

        var twoBadDurations = assertThrows(IllegalArgumentException.class,
                WorkflowExecutor.builder(store, serializer, "svc")
                        .instanceLockTtl(Duration.ZERO)
                        .shutdownTimeout(Duration.ZERO)::build);
        assertTrue(twoBadDurations.getMessage().startsWith("instanceLockTtl must be positive"),
                twoBadDurations.getMessage());

        var lastTwoDurations = assertThrows(IllegalArgumentException.class,
                WorkflowExecutor.builder(store, serializer, "svc")
                        .shutdownTimeout(Duration.ZERO)
                        .wakeRecheckInterval(Duration.ZERO)::build);
        assertTrue(lastTwoDurations.getMessage().startsWith("shutdownTimeout must be positive"),
                lastTwoDurations.getMessage());

        var everyReferenceNull = assertThrows(IllegalArgumentException.class,
                WorkflowExecutor.builder(store, serializer, "svc")
                        .wakeRecheckInterval(Duration.ZERO)
                        .lockKeyPrefix(null)
                        .observer(null)
                        .taskQueueLimits(null)
                        .instantSource(null)::build);
        assertTrue(everyReferenceNull.getMessage().startsWith("wakeRecheckInterval must be positive"),
                everyReferenceNull.getMessage());
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void requiredArgumentsAndReferenceOptionsAreNeverNull() {
        assertEquals("store", assertThrows(NullPointerException.class,
                () -> WorkflowExecutor.builder(null, serializer, "svc")).getMessage());
        assertEquals("serializer", assertThrows(NullPointerException.class,
                () -> WorkflowExecutor.builder(store, null, "svc")).getMessage());
        assertEquals("serviceName", assertThrows(NullPointerException.class,
                () -> WorkflowExecutor.builder(store, serializer, null)).getMessage());

        assertEquals("observer", assertThrows(NullPointerException.class,
                WorkflowExecutor.builder(store, serializer, "svc").observer(null)::build).getMessage());
        assertEquals("lockKeyPrefix", assertThrows(NullPointerException.class,
                WorkflowExecutor.builder(store, serializer, "svc").lockKeyPrefix(null)::build).getMessage());
        assertEquals("taskQueueLimits", assertThrows(NullPointerException.class,
                WorkflowExecutor.builder(store, serializer, "svc").taskQueueLimits(null)::build).getMessage());
        assertEquals("instantSource", assertThrows(NullPointerException.class,
                WorkflowExecutor.builder(store, serializer, "svc").instantSource(null)::build).getMessage());
    }

    @Test
    void aBuiltExecutorKeepsItsSettingsWhenTheBuilderChangesLater() {
        var b = WorkflowExecutor.builder(store, serializer, "svc");
        var first = keep(b.build());
        var second = keep(b.defaultTaskQueueLimits(new TaskQueueLimits(1, 1)).build());
        assertSame(TaskQueueGovernor.UNBOUNDED, first.taskQueueGovernor());
        assertNotSame(TaskQueueGovernor.UNBOUNDED, second.taskQueueGovernor());
    }
}
