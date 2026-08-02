package io.b2mash.maestro.core.engine;

import io.b2mash.maestro.core.annotation.Activity;
import io.b2mash.maestro.core.context.WorkflowContext;
import io.b2mash.maestro.core.exception.UnknownWorkflowHistoryException;
import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowEvent;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.retry.RetryExecutor;
import io.b2mash.maestro.core.retry.RetryPolicy;
import io.b2mash.maestro.core.saga.CompensationStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every replay read whose result drives a memoization decision is guarded
 * (design §6.3). One test per site, each planting an event whose stored type
 * string only a <em>newer</em> node could have written and asserting that the
 * read raises {@link UnknownWorkflowHistoryException} — the control-flow
 * signal — rather than doing anything else.
 *
 * <h2>Why "anything else" is not good enough</h2>
 * <p>An unguarded unknown event does not merely produce a worse error later.
 * At most of these sites it silently fails the type comparison, falls through
 * to the <em>live</em> path, re-executes a step whose outcome is already
 * durable, and only then collides on the append — so the run touches the
 * outside world and then stands down for the wrong reason
 * ({@code STALE_RUN}). At {@code version()} it does not fail at all: the
 * instance quietly takes the pre-change branch. Each test therefore asserts
 * the positive fact (this exact signal, naming this exact sequence), never
 * merely "some exception".
 */
@DisplayName("Every replay read is guarded against an event type this build does not know")
class UnknownHistoryDetectionSitesTest {

    /** A type string no build of this repo will ever define (design §8.5, RULING 1). */
    private static final String FUTURE_TYPE = "EVT_FROM_A_NEWER_MAESTRO";

    private VersionedInMemoryStore store;
    private PayloadSerializer serializer;
    private WorkflowInstance instance;

    @BeforeEach
    void setUp() {
        store = new VersionedInMemoryStore();
        serializer = new PayloadSerializer(JsonMapper.builder().build());
        var now = Instant.now();
        instance = WorkflowInstance.builder()
                .id(UUID.randomUUID())
                .workflowId("guarded-wf")
                .runId(UUID.randomUUID())
                .workflowType("GuardedWorkflow")
                .taskQueue("default")
                .status(WorkflowStatus.RUNNING)
                .serviceName("test-service")
                .eventSequence(0)
                .startedAt(now)
                .updatedAt(now)
                .version(0)
                .build();
        store.createInstance(instance);
    }

    @Test
    @Timeout(20)
    @DisplayName("currentTime()'s replay read")
    void currentTimeStandsDown() {
        plantFutureEvent(1);
        assertStandsDownAt(1, ops -> ops.currentTime());
    }

    @Test
    @Timeout(20)
    @DisplayName("randomUUID()'s replay read")
    void randomUuidStandsDown() {
        plantFutureEvent(1);
        assertStandsDownAt(1, ops -> ops.randomUUID());
    }

    @Test
    @Timeout(20)
    @DisplayName("sleep()'s TIMER_SCHEDULED replay read")
    void sleepScheduledReadStandsDown() {
        plantFutureEvent(1);
        assertStandsDownAt(1, ops -> ops.sleep(Duration.ofMillis(10)));
    }

    @Test
    @Timeout(20)
    @DisplayName("sleep()'s terminal-outcome read at the NEXT sequence — the second read is guarded too")
    void sleepNextEventReadStandsDown() {
        store.injectRawEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), 1,
                EventType.TIMER_SCHEDULED, "$maestro:sleep",
                JsonMapper.builder().build()
                        .readTree("{\"timerId\":\"sleep-1\",\"duration\":\"PT0.01S\"}"),
                Instant.now()));
        plantFutureEvent(2);
        assertStandsDownAt(2, ops -> ops.sleep(Duration.ofMillis(10)));
    }

    @Test
    @Timeout(20)
    @DisplayName("parallel()'s fork-point replay read — before the duplicate append it would otherwise attempt")
    void parallelStandsDown() {
        plantFutureEvent(1);
        assertStandsDownAt(1, ops -> ops.parallel(List.<Callable<String>>of(() -> "a", () -> "b")));
    }

    @Test
    @Timeout(20)
    @DisplayName("version()'s peek — guarded BEFORE the predates-the-change classification")
    void versionPeekStandsDown() {
        plantFutureEvent(1);
        assertStandsDownAt(1, ops -> ops.version("shipping-v2", -1, 3));
    }

    @Test
    @Timeout(20)
    @DisplayName("awaitSignal()'s replay read — never a park, never a timeout")
    void awaitSignalStandsDown() {
        plantFutureEvent(1);
        assertStandsDownAt(1,
                ops -> ops.awaitSignal("approval", String.class, Duration.ofMillis(200)));
    }

    @Test
    @Timeout(20)
    @DisplayName("the activity memoization lookup — before handleReplay's type switch")
    void activityMemoizationLookupStandsDown() {
        plantFutureEvent(1);

        var proxy = new ActivityProxyFactory().createProxy(
                Greeter.class, name -> "hello " + name, store, null, null,
                RetryPolicy.defaultPolicy(), Duration.ofSeconds(5), serializer,
                new RetryExecutor());

        var thrown = boundedRun(() -> {
            var ops = newOperations();
            var ctx = newContext(ops);
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> proxy.greet("world"));
        });
        assertStandDown(thrown, 1);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** The activity interface the memoization-lookup test proxies. */
    @Activity
    public interface Greeter {
        /**
         * @param name whom to greet
         * @return the greeting
         */
        String greet(String name);
    }

    private void plantFutureEvent(int sequenceNumber) {
        store.injectRawEvent(new WorkflowEvent(UUID.randomUUID(), instance.id(), sequenceNumber,
                EventType.fromStoredName(FUTURE_TYPE), "$maestro:from-the-future", null,
                Instant.now()));
    }

    private void assertStandsDownAt(int sequenceNumber, Consumer<DefaultWorkflowOperations> body) {
        var thrown = boundedRun(() -> {
            var ops = newOperations();
            var ctx = newContext(ops);
            ScopedValue.where(WorkflowContext.scopedValue(), ctx).run(() -> body.accept(ops));
        });
        assertStandDown(thrown, sequenceNumber);
    }

    private void assertStandDown(Throwable thrown, int sequenceNumber) {
        var standDown = assertInstanceOf(UnknownWorkflowHistoryException.class, thrown,
                "the read must raise the stand-down control-flow signal, not "
                        + describe(thrown));
        assertAll(
                () -> assertEquals(UnknownWorkflowHistoryException.Kind.UNKNOWN_EVENT_TYPE,
                        standDown.kind()),
                () -> assertEquals(sequenceNumber, standDown.sequenceNumber(),
                        "the signal must name the sequence of the row it could not read"),
                () -> assertEquals(instance.workflowId(), standDown.workflowId()));
    }

    private static String describe(Throwable thrown) {
        return thrown == null
                ? "returning normally (the unknown event was silently ignored)"
                : thrown.getClass().getName() + ": " + thrown.getMessage();
    }

    /**
     * Runs {@code body} on a virtual thread and returns whatever it threw, or
     * {@code null} if it returned. An unguarded read can <em>park</em> (a
     * sleep whose terminal event is unreadable looks pending), so a bare call
     * would hang the suite instead of failing it.
     */
    private static Throwable boundedRun(Runnable body) {
        var thrown = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        var thread = Thread.ofVirtual().start(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                thrown.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(10, TimeUnit.SECONDS)) {
                thread.interrupt();
                fail("the guarded operation neither returned nor threw within 10s — an "
                        + "unguarded read parked on history it cannot interpret");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return thrown.get();
    }

    private DefaultWorkflowOperations newOperations() {
        var parkingLot = new ParkingLot();
        var signalManager = new SignalManager(store, null, null, serializer, parkingLot);
        return new DefaultWorkflowOperations(store, null, null, serializer, parkingLot,
                new CompensationStack(), signalManager);
    }

    private WorkflowContext newContext(DefaultWorkflowOperations ops) {
        return new WorkflowContext(
                instance.id(), instance.workflowId(), instance.runId(),
                instance.workflowType(), instance.taskQueue(), "test-service",
                0, true, ops);
    }
}
