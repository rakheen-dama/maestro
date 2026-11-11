package io.b2mash.maestro.integration.engine;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowInstance;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.core.observe.AbandonReason;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.support.PostgresIntegrationSupport;
import io.b2mash.maestro.integration.support.RecordingObserver;
import io.b2mash.maestro.integration.workflows.CountingActivities;
import io.b2mash.maestro.integration.workflows.CountingActivities.SagaActivities;
import io.b2mash.maestro.integration.workflows.TestWorkflows;
import io.b2mash.maestro.store.postgres.PostgresWorkflowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue 34 end to end on real PostgreSQL: a connection-pool fault that begins
 * <em>inside</em> an activity body — after its side effect, before its outcome
 * is recorded — never fails the workflow.
 *
 * <p>The fault is a {@link DataSource} whose {@code getConnection()} throws
 * {@link SQLTransientConnectionException} with no SQLSTATE, as a pool's
 * acquisition timeout does. {@code charge()}'s first invocation switches it on
 * from inside its body, so the {@code ACTIVITY_COMPLETED} append for
 * {@code charge} meets a store that is down. The store classifies the fault as
 * a {@code StoreUnavailableException}, and the store-fault guard retries that
 * append in place (three attempts, about 1.2 s in all):
 * <ul>
 *   <li>a <b>blip</b> (one failed connection) is absorbed by the retry — the
 *       run carries on and completes, {@code charge} runs once;</li>
 *   <li>an <b>outage</b> ({@value #OUTAGE_SECONDS} s) outlasts the retries, so
 *       the run is abandoned as {@link AbandonReason#STORE_UNAVAILABLE} —
 *       nothing recorded, nothing compensated, the instance left active — and
 *       once the store answers again the recovery poller adopts it, replays
 *       {@code reserve}, re-executes {@code charge} (its outcome was never
 *       recorded: the at-least-once window activities must be idempotent for)
 *       and completes.</li>
 * </ul>
 *
 * <p>Before Issue 34 the fault reached the workflow as an ordinary exception
 * and the run went into failure handling: a blip let that handling write, so
 * the workflow ended {@code FAILED} and its saga released a reservation for a
 * charge that had in fact succeeded.
 */
@Tag("integration")
@DisplayName("Issue 34: a store fault mid-step on real Postgres never fails the workflow")
class StoreOutageMidStepIT extends PostgresIntegrationSupport {

    private static final long OUTAGE_SECONDS = 3;
    private static final Duration RECOVERY_POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Duration BOUND = Duration.ofSeconds(30);

    private final ScheduledExecutorService outageClock = Executors.newSingleThreadScheduledExecutor();
    private final PoolOutage pool = new PoolOutage();
    private final CountingActivities.Recorder recorder = new CountingActivities.Recorder();
    private final RecordingObserver observer = new RecordingObserver();

    @AfterEach
    void stopOutageClock() {
        outageClock.shutdownNow();
    }

    @Test
    @DisplayName("a pool outage after an activity body never fails the workflow — no compensation, "
            + "completed by recovery within three poll intervals")
    void poolOutageAfterActivityBodyNeverFailsTheWorkflow() throws Exception {
        var outageCleared = new CountDownLatch(1);
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("store-outage");

        try (var node = node(() -> {
            pool.down.set(true);
            outageClock.schedule(() -> {
                pool.down.set(false);
                outageCleared.countDown();
            }, OUTAGE_SECONDS, TimeUnit.SECONDS);
        })) {
            node.startRecoveryPoller(RECOVERY_POLL_INTERVAL);
            var handle = node.start(workflowId, TestWorkflows.SagaWorkflow.class, "book");

            assertTrue(outageCleared.await(BOUND.toSeconds(), TimeUnit.SECONDS), "the outage never began");
            var clearedAt = System.nanoTime();
            var finished = awaitTerminal(workflowId, BOUND);
            var finishedAfterOutage = Duration.ofNanos(System.nanoTime() - clearedAt);

            var charges = recorder.count("charge");
            // The blip test below is the one that covers the charges == 1 side.
            assertAll(
                    () -> assertTrue(finishedAfterOutage.compareTo(RECOVERY_POLL_INTERVAL.multipliedBy(3)) <= 0,
                            "recovery must finish the workflow within three poll intervals of the store "
                                    + "answering again; took " + finishedAfterOutage),
                    () -> assertNeverFailed(finished),
                    () -> assertTrue(Set.of(1, 2).contains(charges),
                            "charge runs once, or twice when its outcome was lost to the outage; was "
                                    + charges),
                    () -> assertEquals(charges == 2,
                            observer.abandoned().contains(AbandonReason.STORE_UNAVAILABLE),
                            "a re-executed charge must be explained by a STORE_UNAVAILABLE abandon, and "
                                    + "only then; charges=" + charges + ", abandoned=" + observer.abandoned()),
                    () -> assertEquals(1, recorder.count("reserve"),
                            "reserve's outcome was recorded before the outage and must be replayed"),
                    () -> assertEquals(1, recorder.count("ship")));
        }
    }

    @Test
    @DisplayName("a single failed connection on the outcome append is retried in place — the run "
            + "completes without abandoning and charge runs once")
    void poolBlipOnOutcomeAppendIsRetriedInPlace() throws Exception {
        var workflowId = MaestroEngineHarness.uniqueWorkflowId("store-blip");

        // No recovery poller: its store reads would race the workflow for the
        // one failing connection, and nothing here should need recovering.
        try (var node = node(() -> pool.blips.set(1))) {
            node.start(workflowId, TestWorkflows.SagaWorkflow.class, "book");

            var finished = awaitTerminal(workflowId, BOUND);

            assertAll(
                    () -> assertEquals(1, pool.injected.get(), "exactly one connection must have failed"),
                    () -> assertNeverFailed(finished),
                    () -> assertEquals(1, recorder.count("charge"),
                            "the outcome append was retried in place, so charge must not re-run"),
                    () -> assertEquals(List.of(), observer.abandoned(),
                            "a blip absorbed by the in-place retry must not abandon the run"),
                    () -> assertEquals(1, recorder.count("ship")));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────

    /** A node over the faulty pool whose first {@code charge()} runs {@code startFault} in its body. */
    private MaestroEngineHarness node(Runnable startFault) {
        var faultyStore = new PostgresWorkflowStore(pool.wrap(newDataSource()), objectMapper);
        var node = MaestroEngineHarness.builder(faultyStore, objectMapper)
                .serviceName("store-outage")
                .observer(observer)
                .build();
        node.registerActivities(SagaActivities.class, new FaultOnFirstChargeActivities(recorder, startFault));
        node.registerWorkflow(TestWorkflows.SagaWorkflow.class);
        return node;
    }

    /** The claims both faults share, read through the healthy store. */
    private void assertNeverFailed(WorkflowInstance finished) {
        assertAll(
                () -> assertEquals(WorkflowStatus.COMPLETED, finished.status(),
                        "a store fault must never be recorded as the workflow's failure"),
                () -> assertEquals(0, store.getEvents(finished.id()).stream()
                                .filter(e -> e.eventType() == EventType.WORKFLOW_FAILED).count(),
                        "no WORKFLOW_FAILED may be written for an infrastructure fault"),
                () -> assertEquals(0, recorder.count("releaseReservation"),
                        "the reservation must not be compensated for a charge that succeeded"),
                () -> assertEquals(List.of(), observer.failed()),
                () -> assertEquals(List.of(), observer.compensating()));
    }

    /**
     * The pool switch: while {@link #down} is set, or for the next {@link #blips}
     * calls, {@code getConnection()} fails as a pool's acquisition timeout does.
     *
     * <h2>Thread Safety</h2>
     * <p>Thread-safe: the switch and the counters are atomics.
     */
    private static final class PoolOutage {
        final AtomicBoolean down = new AtomicBoolean();
        final AtomicInteger blips = new AtomicInteger();
        final AtomicInteger injected = new AtomicInteger();

        DataSource wrap(DataSource delegate) {
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(),
                    new Class<?>[]{DataSource.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getConnection") && failThisCall()) {
                            injected.incrementAndGet();
                            throw new SQLTransientConnectionException(
                                    "injected: connection is not available, request timed out");
                        }
                        try {
                            return method.invoke(delegate, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
        }

        private boolean failThisCall() {
            return down.get() || blips.getAndUpdate(n -> Math.max(0, n - 1)) > 0;
        }
    }

    /**
     * Saga activities that record every call; the first {@code charge()}
     * starts the pool fault from inside its body, after its side effect.
     */
    private static final class FaultOnFirstChargeActivities implements SagaActivities {
        private final CountingActivities.Recorder recorder;
        private final Runnable startFault;

        FaultOnFirstChargeActivities(CountingActivities.Recorder recorder, Runnable startFault) {
            this.recorder = recorder;
            this.startFault = startFault;
        }

        @Override
        public String reserve(String item) {
            recorder.record("reserve");
            return "reserved:" + item;
        }

        @Override
        public void releaseReservation(String item) {
            recorder.record("releaseReservation");
        }

        @Override
        public String charge(String item) {
            if (recorder.record("charge") == 1) {
                startFault.run();
            }
            return "charged:" + item;
        }

        @Override
        public void refund(String item) {
            recorder.record("refund");
        }

        @Override
        public String ship(String item) {
            recorder.record("ship");
            return "shipped:" + item;
        }
    }
}
