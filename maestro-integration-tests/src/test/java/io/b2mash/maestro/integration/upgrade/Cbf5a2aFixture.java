package io.b2mash.maestro.integration.upgrade;

import io.b2mash.maestro.core.model.EventType;
import io.b2mash.maestro.core.model.WorkflowStatus;
import io.b2mash.maestro.integration.support.MaestroEngineHarness;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Approval;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Invocations;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.OrderRequest;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.OrderResult;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Receipt;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedFailingActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedFailingWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedOrderActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedOrderWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedRiskActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedSagaActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedSagaWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedTypedActivities;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.SeedTypedResultWorkflow;
import io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.COMPENSATING_CRASHED;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.CRASHED;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.PARKED_APPROVAL;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.PARKED_EXPRESS;
import static io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.Seeds.PARKED_TIMER;

/**
 * The seeded history written by the build at {@code cbf5a2a} (main, WP9): the
 * first fixture, and the one WP1 and WP3 also assert through. The values below
 * moved here unchanged from {@code SeededHistory} and
 * {@code SeededHistoryUpgradeIT} when the harness was parameterised by fixture
 * (pilot-readiness P4).
 */
public final class Cbf5a2aFixture implements SeededFixture {

    /** The one instance. */
    public static final Cbf5a2aFixture INSTANCE = new Cbf5a2aFixture();

    /**
     * SHA-256 of the raw bytes of {@code upgrade/cbf5a2a/seeded-history.sql} as
     * the capture at {@code cbf5a2a} wrote it (the value the capture script
     * printed). The row-level guards (counts, statuses, event types, the WP1
     * decimal) cannot see a hand-edited payload or timer row; this digest can.
     * Never update it without re-capturing under the regeneration rules.
     */
    public static final String DUMP_SHA256 = "ee06b7f9359e2b4cf7ca5b6ff493ae42afc9624073ff58ba7b316476ebffa134";

    /**
     * SHA-256 of the raw bytes of {@code upgrade/cbf5a2a/capture.properties}
     * as committed with the dump. The manifest records the frozen workflow
     * source's path and SHA-256, and {@code theDumpWasCapturedAtItsRef} checks
     * that file's raw bytes against them; pinning the manifest therefore pins
     * the source too. Never update it without re-capturing under the
     * regeneration rules.
     */
    public static final String CAPTURE_MANIFEST_SHA256 = "b71ca8e04bf77f91baf7a0bd77b22799e7980a13f5127d5f7e23f562557f93af";

    /**
     * The seeded {@code PENDING} timer rows a replay needs <em>already due</em>:
     * {@code seed-parked-timer}'s 10-minute nap (fired by the timer poller) and
     * {@code seed-parked-express}'s 10-minute {@code express} deadline
     * (self-fired by {@code awaitSignal} during replay).
     */
    static final Set<String> MUST_BE_DUE = Set.of("seed-parked-timer/sleep-8002", "seed-parked-express/await-signal-8004");

    /** The latest {@code fire_at} among {@link #MUST_BE_DUE} ({@code seed-parked-express}'s {@code await-signal-8004}). */
    public static final Instant LATEST_MUST_BE_DUE = Instant.parse("2026-11-12T15:02:18.853649Z");

    /**
     * The earliest {@code fire_at} among every other seeded {@code PENDING}
     * timer row ({@code seed-parked-approval}'s 10-year {@code approval}
     * deadline; {@code seed-terminated}'s and the three {@code seed-wp1-*} ones
     * follow within seconds). {@code getDueTimers} has no service filter, so
     * from this instant on a timer poller would fire them.
     */
    public static final Instant EARLIEST_MUST_STAY_PENDING = Instant.parse("2036-11-09T14:52:08.619137Z");

    /**
     * Every event {@code cbf5a2a} wrote, per seeded workflow (all 14 instances
     * in the dump): {@code "<seq>:<type>"} in sequence order. Derived from the
     * committed dump by raw SQL over the restored tables ({@code string_agg} of
     * {@code maestro_workflow_event} joined to {@code maestro_workflow_instance}),
     * never through the store, so it can catch a read path that drops,
     * reorders or retypes an event. Never edit.
     */
    static final Map<String, String> RECORDED_EVENTS = Map.ofEntries(
            Map.entry("seed-activity-failed",
                    "1:ACTIVITY_FAILED,2:WORKFLOW_FAILED"),
            Map.entry("seed-alert-failed",
                    "1:ACTIVITY_FAILED,2:ACTIVITY_COMPLETED,3:WORKFLOW_FAILED"),
            Map.entry("seed-compensating-crashed",
                    "1:ACTIVITY_COMPLETED,2:ACTIVITY_COMPLETED,3:ACTIVITY_FAILED,4:COMPENSATION_STARTED,"
                    + "5000:COMPENSATION_STEP_COMPLETED,5001:ACTIVITY_COMPLETED"),
            Map.entry("seed-completed",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED,8005:SIGNAL_TIMEOUT,"
                    + "8006:TIMER_SCHEDULED,8007:TIMER_CANCELLED,8008:TIMER_SCHEDULED,8009:SIGNAL_RECEIVED,"
                    + "8010:ACTIVITY_COMPLETED,8011:SIGNAL_RECEIVED,8012:WORKFLOW_COMPLETED"),
            Map.entry("seed-crashed",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED,8005:SIGNAL_TIMEOUT,"
                    + "8006:TIMER_SCHEDULED,8007:TIMER_CANCELLED,8008:TIMER_SCHEDULED,8009:SIGNAL_RECEIVED"),
            Map.entry("seed-parked-approval",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED,8005:SIGNAL_TIMEOUT,"
                    + "8006:TIMER_SCHEDULED,8007:TIMER_CANCELLED,8008:TIMER_SCHEDULED"),
            Map.entry("seed-parked-express",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED"),
            Map.entry("seed-parked-timer",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED"),
            Map.entry("seed-saga-compensated",
                    "1:ACTIVITY_COMPLETED,2:ACTIVITY_COMPLETED,3:ACTIVITY_FAILED,4:COMPENSATION_STARTED,"
                    + "5000:COMPENSATION_STEP_COMPLETED,5001:ACTIVITY_COMPLETED,6000:COMPENSATION_STEP_COMPLETED,6001:ACTIVITY_COMPLETED,"
                    + "7001:COMPENSATION_COMPLETED,7002:WORKFLOW_FAILED"),
            Map.entry("seed-saga-compensation-failed",
                    "1:ACTIVITY_COMPLETED,2:ACTIVITY_COMPLETED,3:ACTIVITY_FAILED,4:COMPENSATION_STARTED,"
                    + "5000:COMPENSATION_STEP_FAILED,5001:ACTIVITY_FAILED,6000:COMPENSATION_STEP_COMPLETED,6001:ACTIVITY_COMPLETED,"
                    + "7001:COMPENSATION_COMPLETED,7002:WORKFLOW_FAILED"),
            Map.entry("seed-terminated",
                    "1:ACTIVITY_COMPLETED,2:SIDE_EFFECT,3:SIDE_EFFECT,4:VERSION_MARKER,"
                    + "5:SIDE_EFFECT,6001:ACTIVITY_COMPLETED,7001:ACTIVITY_COMPLETED,8001:ACTIVITY_FAILED,"
                    + "8002:TIMER_SCHEDULED,8003:TIMER_FIRED,8004:TIMER_SCHEDULED,8005:SIGNAL_TIMEOUT,"
                    + "8006:TIMER_SCHEDULED,8007:TIMER_CANCELLED,8008:TIMER_SCHEDULED"),
            Map.entry("seed-wp1-decimal",
                    "1:ACTIVITY_COMPLETED,2:TIMER_SCHEDULED"),
            Map.entry("seed-wp1-offset",
                    "1:ACTIVITY_COMPLETED,2:TIMER_SCHEDULED"),
            Map.entry("seed-wp1-set",
                    "1:ACTIVITY_COMPLETED,2:TIMER_SCHEDULED"));

    /**
     * What this build appends to each active run's history, after the events
     * {@code cbf5a2a} wrote: {@code "<seq> <type> <step>"}. Step 10's approval
     * is stored before the run reaches it, so it takes one slot; the saga's
     * second compensation goes to branch base 6000, as {@code cbf5a2a} placed
     * the first at 5000.
     */
    static final Map<String, List<String>> APPENDED = Map.of(
            PARKED_TIMER, List.of(
                    "8003 TIMER_FIRED $maestro:timer-fired",
                    "8004 TIMER_SCHEDULED $maestro:awaitSignal:express",
                    "8005 SIGNAL_TIMEOUT $maestro:awaitSignal:express",
                    "8006 TIMER_SCHEDULED $maestro:sleep",
                    "8007 TIMER_FIRED $maestro:timer-fired",
                    "8008 SIGNAL_RECEIVED $maestro:awaitSignal:approval",
                    "8009 ACTIVITY_COMPLETED seed-order.charge",
                    "8010 SIGNAL_RECEIVED $maestro:awaitSignal:note",
                    "8011 WORKFLOW_COMPLETED null"),
            PARKED_EXPRESS, List.of(
                    "8005 SIGNAL_TIMEOUT $maestro:awaitSignal:express",
                    "8006 TIMER_SCHEDULED $maestro:sleep",
                    "8007 TIMER_FIRED $maestro:timer-fired",
                    "8008 SIGNAL_RECEIVED $maestro:awaitSignal:approval",
                    "8009 ACTIVITY_COMPLETED seed-order.charge",
                    "8010 SIGNAL_RECEIVED $maestro:awaitSignal:note",
                    "8011 WORKFLOW_COMPLETED null"),
            PARKED_APPROVAL, List.of(
                    "8009 SIGNAL_RECEIVED $maestro:awaitSignal:approval",
                    "8010 ACTIVITY_COMPLETED seed-order.charge",
                    "8011 SIGNAL_RECEIVED $maestro:awaitSignal:note",
                    "8012 WORKFLOW_COMPLETED null"),
            CRASHED, List.of(
                    "8010 ACTIVITY_COMPLETED seed-order.charge",
                    "8011 SIGNAL_RECEIVED $maestro:awaitSignal:note",
                    "8012 WORKFLOW_COMPLETED null"),
            COMPENSATING_CRASHED, List.of(
                    "6000 COMPENSATION_STEP_COMPLETED seed-saga.releaseReservation",
                    "6001 ACTIVITY_COMPLETED seed-saga.releaseReservation",
                    "7001 COMPENSATION_COMPLETED $maestro:compensation",
                    "7002 WORKFLOW_FAILED null"));

    /** Finished runs and the three dormant WP1 runs (another service): nine. */
    static final List<String> UNTOUCHED_BY_RECOVERY = List.of(
            Seeds.COMPLETED_RUN, Seeds.TERMINATED_RUN, Seeds.SAGA_COMPENSATED, Seeds.SAGA_COMPENSATION_FAILED,
            Seeds.ACTIVITY_FAILED, Seeds.ALERT_FAILED, Seeds.WP1_DECIMAL, Seeds.WP1_OFFSET, Seeds.WP1_SET);

    private Cbf5a2aFixture() {
    }

    /**
     * The frozen activities over a counter the caller holds: for the WP1
     * tests, which count with {@code cbf5a2a}'s own {@link Invocations}.
     *
     * @param invocations counts every call
     * @return the activities, with an open gate, {@code explode} failing
     */
    public static FrozenActivities activities(Invocations invocations) {
        return new Activities(invocations, new SeedActivities(invocations,
                io.b2mash.maestro.integration.upgrade.cbf5a2a.SeededHistoryWorkflows.StepGate.OPEN, true));
    }

    @Override
    public String name() {
        return "cbf5a2a";
    }

    @Override
    public String ref() {
        return "cbf5a2a06acfba9b0f7ae23f37b3f1f1713928f7";
    }

    @Override
    public String dumpSha256() {
        return DUMP_SHA256;
    }

    @Override
    public String captureManifestSha256() {
        return CAPTURE_MANIFEST_SHA256;
    }

    @Override
    public Set<String> mustBeDue() {
        return MUST_BE_DUE;
    }

    @Override
    public Instant latestMustBeDue() {
        return LATEST_MUST_BE_DUE;
    }

    @Override
    public Instant earliestMustStayPending() {
        return EARLIEST_MUST_STAY_PENDING;
    }

    @Override
    public List<String> appliedVersionsAtCapture() {
        return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "100", "101");
    }

    @Override
    public Map<String, String> recordedEvents() {
        return RECORDED_EVENTS;
    }

    @Override
    public Map<String, List<String>> appended() {
        return APPENDED;
    }

    @Override
    public List<String> untouchedByRecovery() {
        return UNTOUCHED_BY_RECOVERY;
    }

    @Override
    public Map<String, WorkflowStatus> statusAtCapture() {
        return Seeds.STATUS_AT_CAPTURE;
    }

    @Override
    public Set<EventType> eventTypes() {
        return Seeds.EVENT_TYPES;
    }

    @Override
    public FrozenActivities activities(StepGate gate, boolean explodeFails) {
        var invocations = new Invocations();
        return new Activities(invocations, new SeedActivities(invocations, gate::enter, explodeFails));
    }

    @Override
    public Class<?> orderWorkflow() {
        return SeedOrderWorkflow.class;
    }

    @Override
    public Class<?> sagaWorkflow() {
        return SeedSagaWorkflow.class;
    }

    @Override
    public Class<?> failingWorkflow() {
        return SeedFailingWorkflow.class;
    }

    @Override
    public List<Class<?>> workflows() {
        return List.of(SeedOrderWorkflow.class, SeedSagaWorkflow.class, SeedFailingWorkflow.class,
                SeedTypedResultWorkflow.class);
    }

    @Override
    public Object approval(String approver, int level) {
        return new Approval(approver, level);
    }

    @Override
    public Object orderRequest(String orderId, List<String> skus, long napMillis, long expressWaitMillis,
                               long cancellableNapMillis) {
        return new OrderRequest(orderId, skus, napMillis, expressWaitMillis, cancellableNapMillis);
    }

    @Override
    public Class<?> orderResultType() {
        return OrderResult.class;
    }

    @Override
    public Object orderResult(String orderId, String chargeId, BigDecimal amount, List<String> reservations,
                              boolean verified, boolean napCancelled, String note, String token, Instant decidedAt,
                              int version, String approver) {
        return new OrderResult(orderId, new Receipt(orderId, chargeId, amount), reservations, verified, napCancelled,
                note, token, decidedAt, version, approver);
    }

    @Override
    public String toString() {
        return name();
    }

    private record Activities(Invocations invocations, SeedActivities impl) implements FrozenActivities {

        @Override
        public void registerOn(MaestroEngineHarness node) {
            node.registerActivities(SeedOrderActivities.class, impl);
            node.registerActivities(SeedRiskActivities.class, impl);
            node.registerActivities(SeedSagaActivities.class, impl);
            node.registerActivities(SeedFailingActivities.class, impl);
            node.registerActivities(SeedTypedActivities.class, impl);
        }

        @Override
        public int count(String step) {
            return invocations.count(step);
        }

        @Override
        public Map<String, Integer> snapshot() {
            return invocations.snapshot();
        }
    }
}
